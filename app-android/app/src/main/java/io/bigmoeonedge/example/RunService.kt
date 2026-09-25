package io.bigmoeonedge.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.androidlm.research.BackgroundSearch
import org.androidlm.research.Engine
import org.androidlm.research.Generation
import org.androidlm.research.ResearchConfig
import org.androidlm.research.ResearchEvent
import org.androidlm.research.ResearchListener
import org.androidlm.research.ResearchPhase
import org.androidlm.research.ResearchPipeline
import org.androidlm.research.android.AndroidCorpora
import org.androidlm.research.android.CorpusFiles
import org.androidlm.research.android.CorpusLocator
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Hosts ONE persistent bmoe-cli session as a foreground service. The native CLI (shipped as
 * libbmoe-cli.so) is exec'd once with `--session`; the model load and the expert-cache warm-up
 * are paid a single time, and every subsequent prompt is sent as a JSON line on the process's
 * stdin, keeping the cache warm between prompts. Its BMOE_* stdout drives [RunBus].
 *
 * Lifecycle: START_SESSION spawns the process (LOADING → READY); GENERATE sends one prompt
 * (GENERATING → READY); CANCEL interrupts the current generation without unloading; SHUTDOWN (or
 * an idle timeout) closes the process and frees the model.
 *
 * AndroidLM adds research mode: RESEARCH (or a question riding START_SESSION) runs a
 * [ResearchPipeline] in the service's coroutine scope. The pipeline drives the same process
 * through [engine], an adapter that turns one generate request and its BMOE_* lines into a
 * suspend call; the state stays GENERATING (wakelock held, no idle unload) for the whole run,
 * searches included, and its progress is published as [UiState.research].
 */
class RunService : Service() {

    private val telemetry = TelemetryParser()
    // Last throttled screen update of a research generation (telemetry panel, streamed text).
    @Volatile private var lastUiMs = 0L
    @Volatile private var lastTextMs = 0L
    /** True at most once per UI_FRAME_MS: whether a streamed research token should reach the screen. */
    private fun textFrameDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTextMs < UI_FRAME_MS) return false
        lastTextMs = now
        return true
    }
    @Volatile private var proc: Process? = null
    @Volatile private var procWriter: BufferedWriter? = null
    @Volatile private var wake: PowerManager.WakeLock? = null

    @Volatile private var sessionSig: String? = null
    @Volatile private var shuttingDown = false
    // Bumped every time a new session process is (re)started. The runSession thread carries the
    // epoch it was launched with and only touches shared state (proc, RunBus, foreground) while it
    // is still current — so an old session being torn down (on a model/settings change) cannot
    // clobber the fresh session that replaced it with a stale IDLE/ERROR or a nulled process.
    @Volatile private var epoch = 0
    private val nextId = AtomicInteger(1) // requests come from the main thread and from the research coroutine

    // A prompt supplied with START_SESSION runs as soon as the process reports READY.
    @Volatile private var pending: Req? = null

    private val writeLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val idleUnload = Runnable { shutdownSession() }

    // The backstop that force-kills a session which did not exit on its own after a `close`. It is a
    // field, not an inline lambda, so startSession can cancel it: a shutdown followed quickly by a
    // new prompt would otherwise let a stale kill land on the fresh process.
    private val forceKill = Runnable { killProcess() }

    // The CPU thermal-zone `temp` node, discovered once on the first sample and reused thereafter.
    @Volatile private var cpuThermalZone: File? = null

    private data class Req(val prompt: String, val nPredict: Int, val think: Boolean, val clearKv: Boolean)

    // ── AndroidLM research mode (see startResearch) ──

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var researchJob: Job? = null
    private val researchRuns = AtomicInteger(0)

    // A question supplied with START_SESSION starts its research run at READY (cf. [pending]).
    @Volatile private var pendingResearch: String? = null

    // The generation the research pipeline is waiting on; null while plain chat owns the process.
    @Volatile private var inflight: EngineCall? = null

    // Id of a cancelled research generation whose BMOE_DONE has not arrived yet (0 = none).
    @Volatile private var staleId = 0
    private val engineLock = Mutex()

    // A Corpus is bound to one connection and one thread: every corpus call, the lazy open and
    // the close included, runs on this single thread. The databases stay open for the life of
    // the service, which ends with the session. `corpora`/`corporaFiles`: corpus thread only.
    private val corpusExecutor: Lazy<ExecutorService> =
        lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "research-corpus") } }
    private val corpusDispatcher by lazy { corpusExecutor.value.asCoroutineDispatcher() }
    private var corpora: AndroidCorpora? = null
    private var corporaFiles: CorpusFiles? = null

    // A second connection on its own thread for the background search (ResearchPipeline's
    // BackgroundSearch): the slow, flash-bound part of a search runs while the engine writes the
    // plan and the draft. It runs at background priority, which on Android keeps it on the little
    // cores, off the engine's compute cores, and is raised when the run waits for it.
    // `searchCorpora`/`searchFiles`: search thread only.
    @Volatile private var searchTid = 0
    private val searchExecutor: Lazy<ExecutorService> = lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread({
                searchTid = android.os.Process.myTid()
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "research-search")
        }
    }
    private val searchDispatcher by lazy { searchExecutor.value.asCoroutineDispatcher() }
    private var searchCorpora: AndroidCorpora? = null
    private var searchFiles: CorpusFiles? = null

    private fun setSearchPriority(background: Boolean) {
        val tid = searchTid
        if (tid == 0) return
        runCatching {
            android.os.Process.setThreadPriority(
                tid,
                if (background) android.os.Process.THREAD_PRIORITY_BACKGROUND else android.os.Process.THREAD_PRIORITY_DEFAULT,
            )
        }
    }

    /** Search thread only. */
    private fun searchCorporaFor(files: CorpusFiles): AndroidCorpora {
        searchCorpora?.let { if (searchFiles == files) return it else it.close() }
        return AndroidCorpora(files).also { searchCorpora = it; searchFiles = files }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> sendGenerate(reqFrom(intent))
            ACTION_RESEARCH -> startResearch(intent.getStringExtra(EXTRA_QUESTION) ?: "")
            // Cancelling the research coroutine reaches the process through the engine adapter.
            ACTION_CANCEL -> researchJob?.takeIf { it.isActive }?.cancel() ?: send(CANCEL_JSON)
            ACTION_SHUTDOWN -> shutdownSession()
            else -> startSession(intent)
        }
        return START_NOT_STICKY
    }

    // ── session lifecycle ──

    /** Context of the running session, parsed from its argv at start (see startSession). */
    private var sessionCtx = AppSettings.SESSION_CTX

    private fun startSession(intent: Intent?) {
        val model = intent?.getStringExtra(EXTRA_MODEL) ?: run { fail("no model"); return }
        val argv = intent.getStringArrayListExtra(EXTRA_ARGV) ?: run { fail("no argv"); return }
        val sig = intent.getStringExtra(EXTRA_SIG)
        val req = if (intent.hasExtra(EXTRA_PROMPT)) reqFrom(intent) else null
        val question = intent.getStringExtra(EXTRA_QUESTION)

        // Already running the requested session? Just generate against the warm process.
        if (proc != null && sig == sessionSig && !shuttingDown) {
            if (question != null) startResearch(question) else if (req != null) sendGenerate(req)
            return
        }
        researchJob?.cancel() // a run on the session being replaced
        // Different model/settings (or nothing running): tear down and start fresh. A fresh session
        // has an empty KV, so its first turn always clears; and the conversation starts over.
        // Supersede any old session FIRST (bump epoch before detaching its process), so the old
        // thread — which unblocks once that process exits — sees a newer epoch in its finally and
        // skips the cleanup that would otherwise clobber this fresh session.
        val myEpoch = ++epoch
        val dying = detachProcess()
        shuttingDown = false
        pending = if (question == null) req?.copy(clearKv = true) else null
        pendingResearch = question
        staleId = 0
        sessionSig = sig
        main.removeCallbacks(idleUnload)
        main.removeCallbacks(forceKill)

        val streaming = argv.contains("--moe-stream")
        // The context this session was actually opened with, for the "ctx used/total" readout.
        // Taken from the argv rather than re-read from settings, so the number always describes
        // the running process even if the setting changed after it started.
        sessionCtx = argv.indexOf("-c").let { i ->
            if (i >= 0 && i + 1 < argv.size) argv[i + 1].toIntOrNull() ?: AppSettings.SESSION_CTX
            else AppSettings.SESSION_CTX
        }
        startForeground(NOTIF_ID, buildNotification("Loading model…"))
        RunBus.update {
            // ioMode is re-sniffed from the new session's stderr, so clear it: it describes the
            // session being replaced, and the sniffer's first-writer-wins guard would keep it.
            // thinkControl is a property of the model being loaded, so it goes the same way — the
            // incoming session reports its own at BMOE_READY.
            it.copy(state = EngineState.LOADING, error = null, sessionSig = sig, answer = "", summary = "",
                transcript = emptyList(), streaming = streaming, ioMode = null, thinkControl = null,
                research = question?.let { q -> ResearchUi(q) })
        }

        thread(name = "bmoe-session") { runSession(argv, model, myEpoch, dying) }
    }

    /** True while this session thread is still the current one and not shutting down. */
    private fun current(myEpoch: Int) = epoch == myEpoch && !shuttingDown

    /** BMOE_* lines of [DEV_ENGINE_ENV], if the file exists and is readable; empty otherwise. */
    private fun devEngineEnv(): Map<String, String> = try {
        File(DEV_ENGINE_ENV).takeIf { it.canRead() }?.readLines().orEmpty()
            .map { it.trim() }
            .filter { it.startsWith("BMOE_") && '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun runSession(argv: ArrayList<String>, model: String, myEpoch: Int, dying: Process?) {
        try {
            // The superseded process must be gone before this one starts — see awaitExit. Done here
            // rather than in startSession because that runs on the main thread, which must not block.
            awaitExit(dying)

            val nativeDir = applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(argv)
            pb.redirectErrorStream(false)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:/system/lib64:/vendor/lib64"
            // Pin the compute threads to the big cores (see CpuTopology); the engine reads the
            // mask from BMOE_CPUMASK when it builds its thread pool.
            val threads = argv.indexOf("-t").let { i -> if (i >= 0 && i + 1 < argv.size) argv[i + 1].toIntOrNull() else null } ?: 4
            CpuTopology.computeMask(threads)?.let { pb.environment()["BMOE_CPUMASK"] = it }
            // Run the engine above the UI threads (nice -10 on Android). Its compute threads meet at
            // a barrier after every operation, so a UI frame that preempts one of them stalls all.
            // Pixel 8 Pro, same question: draft 3.49 -> 3.85 tok/s, source check 2.54 -> 2.91.
            pb.environment()["BMOE_NICE"] = ENGINE_NICE.toString()
            // Dev builds only: extra BMOE_* engine environment from DEV_ENGINE_ENV (KEY=VALUE per
            // line), so engine tuning can be measured on a phone without rebuilding the app.
            if (BuildConfig.SHARED_STORAGE) {
                devEngineEnv().forEach { (k, v) -> pb.environment()[k] = v }
            }
            Log.i(LOG_TAG, "engine env: " + pb.environment().filterKeys { it.startsWith("BMOE_") })
            pb.directory(File(model).parentFile)

            val p = pb.start().also { proc = it }
            procWriter = BufferedWriter(OutputStreamWriter(p.outputStream))

            // Drain stderr; surface the tail on unexpected exit and sniff the effective read mode.
            // Guarded: killProcess() closes this stream and would otherwise crash the whole app.
            val errTail = StringBuilder()
            thread(name = "bmoe-session-err") {
                try {
                    BufferedReader(InputStreamReader(p.errorStream)).forEachLine { line ->
                        if (errTail.length < 4000) errTail.append(line).append('\n')
                        // Thread placement and priority reports, so they can be checked over adb.
                        if ("affinity" in line || "BMOE_NICE" in line) Log.i(LOG_TAG, "engine: $line")
                        when {
                            "O_DIRECT returns wrong data" in line ->
                                if (current(myEpoch)) RunBus.update { it.copy(ioMode = "buffered (O_DIRECT unsupported on this storage)") }
                            "expert streaming ON" in line ->
                                Regex("""o_direct=(\d)""").find(line)?.groupValues?.get(1)?.let { d ->
                                    if (current(myEpoch)) RunBus.update {
                                        // First writer wins: this line trails the fallback notice above,
                                        // whose reason is worth more than the plain "buffered" here.
                                        if (it.ioMode != null) it
                                        else it.copy(ioMode = if (d == "1") "direct (O_DIRECT)" else "buffered")
                                    }
                                }
                        }
                    }
                } catch (_: Throwable) {
                    // stream closed on shutdown — nothing to surface.
                }
            }

            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                lines.forEach { if (epoch == myEpoch) handleLine(it) }
            }

            val code = p.waitFor()
            if (current(myEpoch) && code != 0) {
                RunBus.update {
                    it.copy(state = EngineState.ERROR,
                        error = if (it.error == null) "bmoe-cli exited $code\n${errTail.takeLast(1200)}" else it.error)
                }
            }
        } catch (t: Throwable) {
            if (current(myEpoch)) RunBus.update { it.copy(state = EngineState.ERROR, error = t.message ?: t.toString()) }
        } finally {
            // A superseded thread (a newer session took over on a model/settings change) must not
            // touch the shared process handles, the UI state, or the foreground service — the new
            // session owns them now.
            if (epoch == myEpoch) {
                failInflight("the engine exited mid-generation")
                releaseWake()
                procWriter = null
                proc = null
                if (!shuttingDown) RunBus.update { if (it.state != EngineState.ERROR) it.copy(state = EngineState.IDLE, sessionSig = null) else it.copy(sessionSig = null) }
                main.post {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    // ── stdout line protocol (docs/telemetry.md) ──

    private fun handleLine(line: String) {
        val t = line.trim()
        val stale = staleId
        if (stale != 0 && t.startsWith("BMOE_")) {
            // A cancelled research generation is still running: its output is dropped, up to and
            // including the line that ends it, so it is never taken for a chat turn or for the
            // result of the request queued behind it.
            val id = LINE_ID.find(t)?.groupValues?.get(1)?.toIntOrNull()
            val isError = t.startsWith("BMOE_ERROR ")
            when {
                (t.startsWith("BMOE_DONE ") || isError) && id == stale -> {
                    staleId = 0
                    // Only a fatal error still concerns the state machine.
                    if (!(isError && "\"fatal\":true" in t)) return
                }
                // Another generation begins, so the end of the stale one was missed: stop dropping.
                t.startsWith("BMOE_BEGIN ") && id != stale -> staleId = 0
                else -> return
            }
        }
        when {
            t.startsWith("BMOE_READY ") -> {
                val ctl = Regex(""""think_ctl":"([a-z_]+)"""").find(t)?.groupValues?.get(1)
                val topk = Regex(""""n_expert_used":(\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull()
                RunBus.update { it.copy(state = EngineState.READY, thinkControl = ctl, nExpertUsed = topk) }
                main.post { notify("Model ready") }
                val question = pendingResearch
                pendingResearch = null
                if (question != null) startResearch(question)
                else pending?.let { p -> pending = null; sendGenerate(p) } ?: scheduleIdleUnload()
            }
            t.startsWith("BMOE_BEGIN ") -> {
                telemetry.reset()
                acquireWake()
                main.removeCallbacks(idleUnload)
                RunBus.update {
                    it.copy(state = EngineState.GENERATING, telemetry = telemetry.current.copy(),
                        answer = "", reasoning = "", summary = "", error = null)
                }
                // (a research run words its own notification, per phase)
                if (inflight == null) main.post { notify("Generating…") }
            }
            telemetry.onLine(t) -> {
                val call = inflight
                if (call != null) {
                    // A research generation: the telemetry panel stays live, but the text belongs
                    // to the pipeline (a plan is not an answer), which places it through its events.
                    // Screen updates are throttled (UI_FRAME_MS): every update recomposes the screen
                    // and re-renders the growing answer on the UI thread, which shares the fast cores
                    // with the engine's compute threads (measured +2-3% decode on a Pixel 8 Pro; the
                    // larger share of that contention is fixed by ENGINE_NICE).
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiMs >= UI_FRAME_MS) {
                        lastUiMs = now
                        sampleCpuTemp()
                        RunBus.update { it.copy(telemetry = telemetry.current.copy()) }
                    }
                    val delta = telemetry.lastDeltaText
                    if (!call.abandoned && delta.isNotEmpty()) call.onToken(delta)
                } else {
                    sampleCpuTemp()
                    RunBus.update {
                        it.copy(telemetry = telemetry.current.copy(), answer = telemetry.current.text,
                            reasoning = telemetry.current.reasoning)
                    }
                }
            }
            t.startsWith("BMOE_DONE ") -> onDone(t.removePrefix("BMOE_DONE "))
            t.startsWith("BMOE_ERROR ") -> onError(t.removePrefix("BMOE_ERROR "))
        }
    }

    private fun onDone(json: String) {
        // Set when this generation belongs to the research pipeline: it gets the result, and the
        // chat transcript, the READY state and the idle timer are left alone (the run goes on).
        val call = inflight
        // A malformed summary must not leave the UI in GENERATING forever with no turn committed
        // and nothing said. The parse is allowed to fail, but the failure has to reach the user and
        // the state machine has to return to READY, which the epilogue below does unconditionally.
        runCatching {
            val o = JSONObject(json)
            val tokens = o.optInt("tokens")
            val tokS = o.optDouble("tok_s")
            val hit = o.optDouble("cache_hit_pct", -1.0)
            val prefill = o.optDouble("prefill_s")
            val nPrompt = o.optInt("n_prompt", -1)
            val nPast = o.optInt("n_past", -1)
            val avgComputeMs = o.optDouble("compute_s_tok", -0.001) * 1000.0
            val avgMgmtMs = o.optDouble("mgmt_s_tok", -0.001) * 1000.0
            // The measured flash terms for the summary panel — serial reads io, overlap reads
            // stall — so end-of-run attribution uses the same measured numbers the live panel
            // does instead of inverting the clamped compute residual (issue #98).
            val avgIoMs = o.optDouble("io_s_tok", -0.001) * 1000.0
            val avgStallMs = o.optDouble("stall_s_tok", -0.001) * 1000.0
            val prefillTps = o.optDouble("prefill_tps", -1.0)
            val loadS = o.optDouble("load_s", -1.0)
            val readMib = o.optDouble("read_mib", -1.0)
            val cacheResidentMib = o.optDouble("cache_resident_mib", -1.0)
            val cacheBudgetMib = o.optDouble("cache_budget_mib", -1.0)
            val majfltPerTok = o.optDouble("majflt_tok", -1.0)
            val cpuSPerTok = o.optDouble("cpu_s_tok", -1.0)
            // Self-speculation. All 0 with MTP off, which is what makes them safe to read
            // unconditionally — and reading them is the only way the UI can say whether
            // speculation ran and what it earned.
            val mtpDrafted = o.optLong("mtp_drafted", 0)
            val mtpAccepted = o.optLong("mtp_accepted", 0)
            val mtpDecodes = o.optLong("mtp_decodes", 0)
            val mtpDraftSTok = o.optDouble("mtp_draft_s_tok", 0.0)
            val draftedSteps = o.optLong("drafted_steps", 0)
            val loopOverheadSTok = o.optDouble("loop_overhead_s_tok", 0.0)
            // What the user actually waits: tok_s counts decode time only, so drafting — which
            // happens between decodes — is invisible to it. With MTP off the gap is ~0 and this
            // equals tokS; with it on the two can differ by tens of percent.
            val effTokS = if (tokS > 0) 1.0 / (1.0 / tokS + loopOverheadSTok) else -1.0
            // Time-to-first-token: the model load plus this turn's prompt prefill.
            val ttft = if (loadS >= 0 && prefill >= 0) loadS + prefill else -1.0
            val cancelled = o.optBoolean("cancelled")
            val text = o.optString("text")
            val reasoning = o.optString("reasoning")
            val loc = java.util.Locale.US
            val summary = buildString {
                append(String.format(loc, "generation: %d tokens (%.2f tok/s)", tokens, tokS))
                if (prefill > 0) {
                    append(String.format(loc, " | prefill %.2fs", prefill))
                    if (prefillTps > 0) append(String.format(loc, " (%.1f tok/s)", prefillTps))
                }
                if (ttft >= 0) append(String.format(loc, " | TTFT %.2fs", ttft))
                if (hit >= 0) append(String.format(loc, " | cache %.0f%%", hit))
                if (cancelled) append(" | cancelled")
                if (mtpDecodes > 0 && mtpDrafted > 0) {
                    append(String.format(loc, "\nGuessing: %d/%d kept (%.0f%%), %.2f tok per pass",
                        mtpAccepted, mtpDrafted, 100.0 * mtpAccepted / mtpDrafted,
                        tokens.toDouble() / mtpDecodes))
                    if (mtpDraftSTok > 0) {
                        append(String.format(loc, " | guessing costs %.3fs/tok → %.2f tok/s real",
                            mtpDraftSTok, effTokS))
                    }
                    // Only the n-gram source ever abstains, so a coverage below every step is what
                    // says the rest of the turn ran at the plain, unspeculated cost.
                    if (draftedSteps in 1 until mtpDecodes) {
                        append(String.format(loc, " | guessed on %.0f%% of passes",
                            100.0 * draftedSteps / mtpDecodes))
                    }
                }
            }
            // Compact one-line metrics shown under this turn's answer in the transcript.
            val turnMetrics = buildString {
                append(String.format(loc, "%.1f tok/s · %d tok", tokS, tokens))
                if (prefill > 0) {
                    append(String.format(loc, " · prefill %.1fs", prefill))
                    if (nPrompt >= 0) append(String.format(loc, " (%d tok)", nPrompt))
                }
                if (nPast >= 0) append(String.format(loc, " · ctx %d/%d", nPast, sessionCtx))
                if (hit >= 0) append(String.format(loc, " · cache %.0f%%", hit))
                // With speculation on, the headline rate leaves out the drafting between decodes;
                // show what the turn really ran at, and how often the guesses were right.
                if (mtpDecodes > 0 && mtpDrafted > 0) {
                    if (effTokS > 0) append(String.format(loc, " · %.1f real", effTokS))
                    append(String.format(loc, " · %.0f%% kept", 100.0 * mtpAccepted / mtpDrafted))
                }
                if (cancelled) append(" · cancelled")
            }
            val tel = telemetry.current.copy(
                avgTokensPerSecond = tokS, avgComputeMs = avgComputeMs,
                avgMgmtMs = avgMgmtMs, avgIoMs = avgIoMs, avgStallMs = avgStallMs,
                prefillTps = prefillTps, ttftS = ttft, readMib = readMib,
                cacheResidentMib = cacheResidentMib, cacheBudgetMib = cacheBudgetMib,
                avgMajfltPerTok = majfltPerTok, avgCpuSPerTok = cpuSPerTok,
                mtpDrafted = mtpDrafted, mtpAccepted = mtpAccepted, mtpDecodes = mtpDecodes,
                draftedSteps = draftedSteps,
                mtpDraftSPerTok = mtpDraftSTok, loopOverheadSPerTok = loopOverheadSTok,
            )
            if (call != null) {
                RunBus.update { it.copy(telemetry = tel, summary = summary) }
                val wallS = (System.nanoTime() - call.startNanos) / 1e9
                // An engine-side cancel (not ours: ours has abandoned the call) ends the run too.
                if (cancelled && !call.abandoned) call.done.completeExceptionally(CancellationException("generation cancelled"))
                else call.done.complete(Generation(text.ifEmpty { telemetry.current.text }, tokens, tokS, nPrompt, wallS))
                return@runCatching
            }
            RunBus.update {
                val answer = if (text.isNotEmpty()) text else it.answer
                // The final BMOE_DONE reasoning may be empty (some models drop it from the summary);
                // fall back to the last streamed thinking span so the committed turn keeps it.
                val think = if (reasoning.isNotEmpty()) reasoning else it.reasoning
                // Commit the assistant turn (skip a cancelled empty turn); the user turn was added on send.
                val transcript =
                    if (answer.isNotEmpty() || !cancelled)
                        it.transcript + ChatTurn("assistant", answer, turnMetrics, think)
                    else it.transcript
                it.copy(state = EngineState.READY, telemetry = tel, answer = "", reasoning = "",
                    summary = summary, transcript = transcript)
            }
        }.onFailure { e ->
            if (call != null) {
                // Unreadable summary: the streamed text is the result, without figures.
                call.done.complete(Generation(telemetry.current.text))
                return@onFailure
            }
            // Commit whatever was streamed so the answer is not lost, say what happened, and go
            // back to READY. Anything else strands the session in a state only a restart clears.
            RunBus.update {
                val transcript =
                    if (it.answer.isNotEmpty())
                        it.transcript + ChatTurn("assistant", it.answer, "", it.reasoning)
                    else it.transcript
                it.copy(state = EngineState.READY, answer = "", reasoning = "", transcript = transcript,
                    error = "The engine's end-of-turn summary could not be read (${e.message}). " +
                        "The answer above is what streamed before it.")
            }
        }
        sampleCpuTemp()
        if (call != null) return
        releaseWake()
        main.post { notify("Model ready") }
        scheduleIdleUnload()
    }

    /**
     * Sample the SoC/CPU temperature and publish it to [RunBus]. Read from the kernel thermal zones
     * (`/sys/class/thermal/thermal_zone*`), which expose the on-die sensors that track compute load
     * directly — a far better proxy for streaming heat than the battery pack, which lags behind and
     * reflects charging as much as compute. No permission is required and the read is best-effort:
     * the CPU zone is discovered once by matching its `type`, and if no zone is readable (some
     * vendors lock the sysfs node down) we fall back to the battery temperature so the figure never
     * goes blank. It does not travel through the engine; it is read on the Android side while
     * streaming.
     */
    private fun sampleCpuTemp() {
        val cpu = readCpuThermalZone()
        val celsius = cpu ?: readBatteryTemp()
        if (celsius != null) RunBus.update { it.copy(cpuTempC = celsius) }
    }

    /**
     * Locate and read the CPU thermal zone. The zone path is resolved once (its `type` name contains
     * "cpu" — e.g. "cpu-0-0-usr", "cpu_thermal", "mtktscpu") and cached; subsequent samples just read
     * the `temp` node. Kernel thermal `temp` is conventionally millidegrees Celsius, but a few
     * vendors report tenths or whole degrees, so the raw value is normalised by magnitude.
     */
    private fun readCpuThermalZone(): Double? {
        val zone = cpuThermalZone ?: discoverCpuThermalZone()?.also { cpuThermalZone = it } ?: return null
        val raw = runCatching { zone.readText().trim().toDouble() }.getOrNull() ?: return null
        return normalizeThermal(raw).takeIf { it in 1.0..150.0 }
    }

    private fun discoverCpuThermalZone(): File? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return null
        return zones.firstOrNull { z ->
            runCatching { File(z, "type").readText().trim().contains("cpu", ignoreCase = true) }
                .getOrDefault(false)
        }?.let { File(it, "temp") }
    }

    /** Normalise a kernel thermal reading to Celsius: millidegrees, tenths, or already-degrees. */
    private fun normalizeThermal(raw: Double): Double = when {
        raw > 1000.0 -> raw / 1000.0
        raw > 200.0  -> raw / 10.0
        else         -> raw
    }

    /** Battery pack temperature (°C) from the sticky ACTION_BATTERY_CHANGED broadcast, in tenths. */
    private fun readBatteryTemp(): Double? {
        val tenths = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        }.getOrDefault(Int.MIN_VALUE)
        return if (tenths != Int.MIN_VALUE) tenths / 10.0 else null
    }

    private fun onError(json: String) {
        val fatal = runCatching { JSONObject(json).optBoolean("fatal", true) }.getOrDefault(true)
        val msg = runCatching { JSONObject(json).optString("msg") }.getOrDefault("engine error")
        // A research generation fails its run with the engine's message (see startResearch).
        failInflight(msg)
        releaseWake()
        if (fatal) {
            RunBus.update { it.copy(state = EngineState.ERROR, error = msg) }
            shutdownSession()
        } else {
            // Bad request (e.g. context overflow): the session stays loaded and usable.
            RunBus.update { it.copy(state = EngineState.READY, error = msg) }
            main.post { notify("Model ready") }
            scheduleIdleUnload()
        }
    }

    // ── requests ──

    private fun reqFrom(intent: Intent): Req = Req(
        prompt = intent.getStringExtra(EXTRA_PROMPT) ?: "",
        nPredict = intent.getIntExtra(EXTRA_NPREDICT, AppSettings.DEFAULT_N_PREDICT),
        think = intent.getBooleanExtra(EXTRA_THINK, false),
        clearKv = intent.getBooleanExtra(EXTRA_CLEAR_KV, true),
    )

    private fun sendGenerate(req: Req) {
        // One generation at a time: while a research run owns the process, plain chat waits.
        if (researchJob?.isActive == true) return
        // Show the user's turn immediately. clear_kv = "new chat" resets the transcript to this turn.
        RunBus.update {
            val user = ChatTurn("user", req.prompt)
            it.copy(transcript = if (req.clearKv) listOf(user) else it.transcript + user, answer = "",
                research = null)
        }
        if (!send(generateJson(nextId.getAndIncrement(), req))) fail("session not ready")
    }

    private fun generateJson(id: Int, req: Req): String = buildString {
        append("""{"cmd":"generate","id":""").append(id)
        append(""","n_predict":""").append(req.nPredict)
        append(""","think":""").append(req.think)
        append(""","clear_kv":""").append(req.clearKv)
        append(""","prompt":"""").append(jsonEscape(req.prompt)).append("\"}")
    }

    private fun send(json: String): Boolean = synchronized(writeLock) {
        val w = procWriter ?: return false
        return try {
            w.write(json); w.write("\n"); w.flush(); true
        } catch (_: Throwable) {
            false
        }
    }

    // ── AndroidLM research mode ──

    /** One generate request of the research pipeline, completed by the BMOE_* lines that answer it. */
    private class EngineCall(val id: Int, val onToken: (String) -> Unit) {
        val done = CompletableDeferred<Generation>()
        val startNanos = System.nanoTime()

        // Set once the caller was cancelled: nothing more is streamed to it, and the BMOE_DONE
        // that the cancel provokes is awaited only so that it is not taken for a chat turn.
        @Volatile var abandoned = false
    }

    /**
     * The session process as the pipeline's [Engine]: one generate request per call, on a fresh
     * KV and with reasoning off (as scripts/bmoe_session.py sends them). Completes on BMOE_DONE,
     * fails on BMOE_ERROR or when the process goes away, streams each `delta_text`, and turns
     * coroutine cancellation into the protocol's `cancel`. After a cancel it waits (briefly) for
     * the engine's BMOE_DONE; if that takes too long the generation's id goes into [staleId] and
     * handleLine drops the rest of its output, so it is never taken for a chat turn or for the
     * next request's result.
     */
    private val engine = object : Engine {
        override suspend fun generate(prompt: String, nPredict: Int, onToken: (String) -> Unit, continueChat: Boolean): Generation =
            engineLock.withLock {
                val call = EngineCall(nextId.getAndIncrement(), onToken)
                inflight = call
                try {
                    // clear_kv=false continues the engine-held conversation: it re-renders the chat
                    // template over the whole history and prefills only what is new
                    if (!send(generateJson(call.id, Req(prompt, nPredict, think = false, clearKv = !continueChat)))) {
                        throw IllegalStateException("the engine session is not running")
                    }
                    call.done.await()
                } catch (e: CancellationException) {
                    call.abandoned = true
                    if (!call.done.isCompleted) {
                        send(CANCEL_JSON)
                        withContext(NonCancellable) { withTimeoutOrNull(CANCEL_DRAIN_MS) { call.done.join() } }
                        // Still running (a prefill batch is not interruptible): disown its output.
                        if (!call.done.isCompleted) staleId = call.id
                    }
                    throw e
                } finally {
                    inflight = null
                }
            }
    }

    private fun failInflight(msg: String) {
        inflight?.done?.completeExceptionally(IllegalStateException(msg))
    }

    /**
     * Run the research pipeline on [question] against the loaded session. The foreground
     * service is already up (the session owns it); the wakelock is taken for the whole run and
     * the idle unload is held off, because the searches between generations are part of it.
     */
    private fun startResearch(question: String) {
        if (researchJob?.isActive == true) return
        if (procWriter == null) { fail("session not ready"); return }
        val runId = researchRuns.incrementAndGet()
        main.removeCallbacks(idleUnload)
        acquireWake()
        RunBus.update {
            // Every research generation clears the KV, so a chat in progress cannot continue.
            it.copy(state = EngineState.GENERATING, research = ResearchUi(question, runId = runId),
                transcript = emptyList(), answer = "", reasoning = "", summary = "", error = null)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val files = withContext(Dispatchers.IO) { CorpusLocator.find(this@RunService) }
                    ?: throw IllegalStateException("no corpus found (${CorpusLocator.WIKI} in a \"${CorpusLocator.DIR}\" directory)")
                val open = withContext(corpusDispatcher) { corporaFor(files) }
                val searchOpen = withContext(searchDispatcher) { searchCorporaFor(files) }
                val background = BackgroundSearch(searchOpen, searchDispatcher, ::setSearchPriority)
                // (a preference of the method, not of the session: read per run, never in the argv)
                val prefs = AppSettings.load(this@RunService)
                val config = ResearchConfig(travelRoute = prefs.researchTravelRoute, checkContinue = prefs.researchCheckContinue)
                ResearchPipeline(engine, open, corpusDispatcher, config, background).run(question, researchListener(runId))
            } catch (e: CancellationException) {
                publishResearch(runId) { if (it.running) it.copy(phase = ResearchPhase.CANCELLED) else it }
                throw e
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                RunBus.update {
                    // (not when another run or session has replaced this one on screen)
                    val r = it.research
                    if (r == null || r.runId != runId) it
                    else it.copy(research = r.copy(phase = ResearchPhase.FAILED, error = msg),
                        error = it.error ?: "Research failed: $msg")
                }
            } finally {
                onResearchEnded(coroutineContext.job)
            }
        }
        // Lazy, so that the job is on record before its body (and onResearchEnded) can run.
        researchJob = job
        job.start()
    }

    /** Corpus thread only. Reopens when the files on the device are not the ones that are open. */
    private fun corporaFor(files: CorpusFiles): AndroidCorpora {
        corpora?.let { if (corporaFiles == files) return it else it.close() }
        return AndroidCorpora(files).also { corpora = it; corporaFiles = files }
    }

    private fun onResearchEnded(job: Job) {
        if (researchJob !== job) return // a newer run (or session) has taken over
        researchJob = null
        RunBus.update { if (it.state == EngineState.GENERATING) it.copy(state = EngineState.READY) else it }
        releaseWake()
        if (proc != null && !shuttingDown) {
            main.post { notify("Model ready") }
            scheduleIdleUnload()
        }
    }

    private fun publishResearch(runId: Int, block: (ResearchUi) -> ResearchUi) = RunBus.update {
        val r = it.research
        if (r != null && r.runId == runId) it.copy(research = block(r)) else it
    }

    /**
     * Pipeline events into [UiState.research]. Token events arrive on the session's reader thread,
     * right after the telemetry parser took the same line, so the text shown while streaming is
     * the parser's accumulation: it already honours the protocol's `reset` lines, which a plain
     * concatenation of deltas would not.
     */
    private fun researchListener(runId: Int): ResearchListener {
        // Phase timings go to logcat (tag AndroidLM) so scripted runs can be timed over adb:
        //   adb logcat -s AndroidLM
        val t0 = SystemClock.elapsedRealtime()
        var sawAnswer = false
        var sawCheck = false
        fun log(msg: String) = Log.i(LOG_TAG, "run=$runId t=${SystemClock.elapsedRealtime() - t0}ms $msg")
        return ResearchListener { e ->
        when (e) {
            is ResearchEvent.PhaseChanged -> log("phase=${e.phase}")
            is ResearchEvent.Planned -> log("planned=${e.titles}")
            is ResearchEvent.Routed -> log("route=${e.decision.route} views=${e.decision.views} travel=${e.decision.travel}")
            is ResearchEvent.SourcesFound ->
                log("sources=${e.sources.size} dropped=${e.dropped} [" + e.sources.joinToString(" | ") { "${it.title} — ${it.section}" } + "]")
            is ResearchEvent.AnswerToken -> if (!sawAnswer) { sawAnswer = true; log("first_answer_token") }
            is ResearchEvent.CheckToken -> if (!sawCheck) { sawCheck = true; log("first_check_token") }
            is ResearchEvent.PhaseCompleted -> e.timing.let { tm ->
                log("phase_done=${tm.phase} wall=${tm.wallMs}ms " + (tm.workMs?.let { "work=${it}ms " } ?: "") + (tm.generation?.let {
                    "tokens=${it.tokens} tok_s=${"%.2f".format(it.tokensPerSecond)} prompt_tokens=${it.promptTokens}"
                } ?: ""))
            }
            is ResearchEvent.Completed -> {
                log("completed")
                e.result.text.chunked(900).forEachIndexed { i, part -> Log.i(LOG_TAG, "run=$runId text[$i]=$part") }
            }
            is ResearchEvent.Failed -> log("failed phase=${e.phase} ${e.message}")
            else -> Unit
        }
        when (e) {
            is ResearchEvent.PhaseChanged -> {
                publishResearch(runId) {
                    it.copy(phase = e.phase, check = if (e.phase == ResearchPhase.CHECKING) "" else it.check)
                }
                researchNotice(e.phase)?.let { text -> main.post { notify(text) } }
            }
            is ResearchEvent.Planned -> publishResearch(runId) { it.copy(titles = e.titles) }
            is ResearchEvent.Routed -> publishResearch(runId) { it.copy(route = e.decision, routeThreshold = e.threshold) }
            is ResearchEvent.SourcesFound -> publishResearch(runId) { it.copy(sources = e.sources, sourcesDropped = e.dropped) }
            is ResearchEvent.AnswerToken -> if (textFrameDue()) telemetry.current.text.let { text -> publishResearch(runId) { it.copy(answer = text) } }
            is ResearchEvent.AnswerCompleted -> publishResearch(runId) { it.copy(answer = e.text) }
            is ResearchEvent.CheckToken -> if (textFrameDue()) telemetry.current.text.let { text -> publishResearch(runId) { it.copy(check = text) } }
            is ResearchEvent.CheckCompleted -> publishResearch(runId) { it.copy(check = e.text) }
            is ResearchEvent.PhaseCompleted -> publishResearch(runId) { it.copy(timings = it.timings + e.timing) }
            is ResearchEvent.Completed -> Unit // everything in it has been published piecewise
            is ResearchEvent.Failed -> Unit    // startResearch reports the failure it rethrows
        }
        }
    }

    private fun researchNotice(phase: ResearchPhase): String? = when (phase) {
        ResearchPhase.PLANNING -> "Research: planning lookups…"
        ResearchPhase.SEARCHING -> "Research: searching the corpus…"
        ResearchPhase.DRAFTING -> "Research: drafting an answer…"
        ResearchPhase.ANSWERING -> "Research: answering from the sources…"
        ResearchPhase.CHECKING -> "Research: checking the draft against the sources…"
        else -> null // terminal phases: onResearchEnded says "Model ready"
    }

    // ── teardown ──

    private fun shutdownSession() {
        shuttingDown = true
        researchJob?.cancel()
        pendingResearch = null
        main.removeCallbacks(idleUnload)
        requestClose()
        main.postDelayed(forceKill, FORCE_KILL_MS)
        RunBus.update { it.copy(state = EngineState.IDLE, sessionSig = null) }
    }

    /**
     * Ask the session to wind down on its own terms, so it runs the ordered teardown — unhook,
     * join the IO pool, free the expert cache — rather than leaving it all to the kernel.
     * `close` is queued behind an in-flight generate, hence the cancel first: the
     * engine applies that off its reader thread and aborts the decode, letting close land at once.
     * Callers back this up with a deadline ([forceKill] or [awaitExit]).
     */
    private fun requestClose() {
        send(CANCEL_JSON)
        send("""{"cmd":"close"}""")
    }

    private fun killProcess() {
        synchronized(writeLock) {
            runCatching { procWriter?.close() }
            procWriter = null
        }
        runCatching { proc?.destroy() }
        proc = null
        failInflight("the engine was stopped")
    }

    /** Wind the current session down and hand its process off, without waiting, so the caller can
     *  install a fresh session immediately while the old one drains. */
    private fun detachProcess(): Process? {
        requestClose()
        synchronized(writeLock) {
            runCatching { procWriter?.close() } // EOF: also closes a session that ignored the command
            procWriter = null
        }
        val p = proc
        proc = null
        // Its remaining output is ignored from here on (the epoch moved), BMOE_DONE included.
        failInflight("the session was replaced")
        return p
    }

    /**
     * Block until a superseded process is really gone. destroy() only signals; until the kernel has
     * reaped it, it still holds its model and expert cache. The replacement sizes its own cache from
     * MemAvailable as it starts, so overlapping the two makes the fresh session read a deflated
     * figure and quietly starve its cache — and on a >RAM model the combined footprint risks an OOM
     * kill. Waiting here costs the teardown time once, on a path that is already reloading a model.
     */
    private fun awaitExit(p: Process?) {
        p ?: return
        runCatching {
            if (!p.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(EXIT_FORCE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun scheduleIdleUnload() {
        main.removeCallbacks(idleUnload)
        main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
    }

    private fun fail(msg: String) {
        RunBus.update { it.copy(state = EngineState.ERROR, error = msg) }
        stopForegroundCompat()
        stopSelf()
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bmoe:gen")
            .apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) }
    }

    @Synchronized
    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onDestroy() {
        shuttingDown = true
        main.removeCallbacks(idleUnload)
        main.removeCallbacks(forceKill)
        scope.cancel()
        killProcess()
        releaseWake()
        if (corpusExecutor.isInitialized()) {
            // Behind whatever search is still running: the corpora close on their own thread.
            corpusExecutor.value.execute { corpora?.close(); corpora = null }
            corpusExecutor.value.shutdown()
        }
        if (searchExecutor.isInitialized()) {
            searchExecutor.value.execute { searchCorpora?.close(); searchCorpora = null }
            searchExecutor.value.shutdown()
        }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Generation", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("AndroidLM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_GENERATE = "io.bigmoeonedge.example.GENERATE"
        const val ACTION_CANCEL = "io.bigmoeonedge.example.CANCEL"
        const val ACTION_SHUTDOWN = "io.bigmoeonedge.example.SHUTDOWN"

        /** AndroidLM: run the research pipeline on [EXTRA_QUESTION] against the loaded session. */
        const val ACTION_RESEARCH = "io.bigmoeonedge.example.RESEARCH"

        const val LOG_TAG = "AndroidLM"
        /** Engine scheduling priority (nice value); see runSession. */
        const val ENGINE_NICE = -16
        /** Minimum interval between screen updates while a research generation streams. */
        const val UI_FRAME_MS = 250L
        /** Dev builds: optional extra engine environment (BMOE_* KEY=VALUE lines). */
        const val DEV_ENGINE_ENV = "/data/local/tmp/androidlm-engine.env"

        /** With [ACTION_RESEARCH], or with START_SESSION to research as soon as the model is ready. */
        const val EXTRA_QUESTION = "question"
        const val EXTRA_MODEL = "model"
        const val EXTRA_ARGV = "argv"
        const val EXTRA_SIG = "sig"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_NPREDICT = "n_predict"
        const val EXTRA_THINK = "think"
        const val EXTRA_CLEAR_KV = "clear_kv"
        private const val CHANNEL = "gen"
        private val LINE_ID = Regex(""""id":(\d+)""")
        private const val CANCEL_JSON = """{"cmd":"cancel"}"""

        // How long a cancelled research generation waits for the engine's BMOE_DONE.
        private const val CANCEL_DRAIN_MS = 5000L
        private const val NOTIF_ID = 1

        // Free the model after this long with no generation, so an idle session does not hold
        // ~model-sized RAM and a foreground service indefinitely. The next prompt reloads.
        private const val IDLE_UNLOAD_MS = 10 * 60 * 1000L

        // How long a session gets to honour `close` and tear down cleanly before it is killed.
        private const val FORCE_KILL_MS = 1500L

        // Budget for a superseded process to exit before the replacement spawns. The grace window
        // covers an ordered teardown; past it the process is SIGKILLed and the kernel reclaims,
        // which is quick but not instant — hence the second, shorter wait.
        private const val EXIT_GRACE_MS = 2000L
        private const val EXIT_FORCE_MS = 3000L

        private fun jsonEscape(s: String): String {
            val o = StringBuilder(s.length + 8)
            for (c in s) when (c) {
                '"' -> o.append("\\\"")
                '\\' -> o.append("\\\\")
                '\n' -> o.append("\\n")
                '\r' -> o.append("\\r")
                '\t' -> o.append("\\t")
                else -> if (c < ' ') o.append(String.format("\\u%04x", c.code)) else o.append(c)
            }
            return o.toString()
        }
    }
}
