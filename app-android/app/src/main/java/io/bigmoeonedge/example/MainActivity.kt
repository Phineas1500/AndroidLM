package io.bigmoeonedge.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.androidlm.research.android.CorpusFiles
import org.androidlm.research.PlacesText
import org.androidlm.research.Translation
import org.androidlm.research.android.AndroidLocator
import org.androidlm.research.android.CorpusLocator
import java.io.File
import java.util.Locale

/**
 * AndroidLM: a chat with an offline research assistant (ChatScreen), with the model and its files
 * (ModelScreen), the engine's tunables (SettingsScreen) and per-run metrics one screen away.
 */
class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // All-files access is NOT requested at startup: downloaded, imported and picked models
        // live in the app-specific dir and need no permission. The dev flavor asks for it only
        // when the user explicitly rescans device storage (Refresh) for adb-pushed models.
        setContent {
            MaterialTheme(colorScheme = if (isSystemDark()) darkColorScheme() else lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
        // (not when the activity is recreated, or reopened from Recents with the intent that started it)
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) autoResearch(intent)
    }

    override fun onStart() {
        super.onStart()
        preloadModel()
    }

    /**
     * Loads the model as soon as the app is opened, so the first question does not wait for it
     * (about 25 s on a Pixel 8 Pro): only in research mode with a corpus, and only when no session
     * is loaded or loading. A session unloaded after [RunService]'s idle timeout is loaded again
     * the next time the app comes to the screen.
     */
    private fun preloadModel() {
        val ui = RunBus.state.value
        if (ui.sessionSig != null || ui.state != EngineState.IDLE) return
        Thread {
            val settings = AppSettings.load(this)
            if (!settings.researchMode || CorpusLocator.find(this) == null) return@Thread
            val model = ModelManager.preferredModel(this, ModelManager.listMoeModels(this)) ?: return@Thread
            runOnUiThread {
                val now = RunBus.state.value
                if (now.sessionSig == null && now.state == EngineState.IDLE) preloadSession(this, model, settings)
            }
        }.start()
    }

    /** Dev builds: a question sent to the running app (am start --activity-single-top) is a follow-up. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoResearch(intent)
    }

    /**
     * Dev builds only: start a research run from adb, for scripted timing runs (phase timings are
     * logged under the AndroidLM tag), on the chosen model or the first whose file name contains
     * the optional research_model extra:
     *   adb shell am start -S -n io.github.phineas1500.androidlm.dev/io.bigmoeonedge.example.MainActivity \
     *     --es research_question "..." [--es research_model Flash-Next]
     */
    private fun autoResearch(intent: Intent?) {
        if (!BuildConfig.SHARED_STORAGE) return
        val question = intent?.getStringExtra(EXTRA_AUTO_RESEARCH)?.takeIf { it.isNotBlank() } ?: return
        val want = intent.getStringExtra(EXTRA_AUTO_MODEL)?.takeIf { it.isNotBlank() }
        Thread {
            val models = ModelManager.listMoeModels(this)
            val model = (want?.let { w -> models.firstOrNull { it.name.contains(w) } }
                ?: ModelManager.preferredModel(this, models)) ?: return@Thread
            val settings = AppSettings.load(this)
            runOnUiThread { launchResearch(this, model, question, settings, RunBus.state.value.sessionSig) }
        }.start()
    }

    companion object {
        const val EXTRA_AUTO_RESEARCH = "research_question"
        const val EXTRA_AUTO_MODEL = "research_model"
    }

    private fun isSystemDark(): Boolean {
        val flag = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return flag == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

/**
 * Request all-files access, needed only by the dev flavor to scan device storage for adb-pushed
 * models. Called on an explicit user action (Refresh), never at startup. No-op on the Play flavor
 * and when access is already granted.
 */
fun requestSharedStorageAccess(context: android.content.Context) {
    if (!BuildConfig.SHARED_STORAGE) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    // the screen over the chat, if any: "settings", "metrics" (from "model") or "model"
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(AppSettings.load(context)) }

    // Model-scan state lives here, above the screen switch, so opening Settings and coming back
    // does NOT dispose it and trigger a fresh scan. The scan runs once (and again only when
    // refreshKey changes: an explicit Refresh, or after a download/import completes).
    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var modelIdx by remember { mutableStateOf(0) }
    // AndroidLM: the retrieval corpus research mode needs, looked for along with the models.
    var corpus by remember { mutableStateOf<CorpusFiles?>(null) }

    // Probing gguf headers to keep only MoE models does blocking reads — off the main thread.
    LaunchedEffect(refreshKey) {
        scanning = true
        corpus = withContext(Dispatchers.IO) { CorpusLocator.find(context) }
        models = withContext(Dispatchers.IO) { ModelManager.listMoeModels(context) }
        modelIdx = ModelManager.preferredIndex(context, models)
        scanning = false
    }

    // the system Back goes from Metrics to Model and files, and from there or Settings to the chat
    BackHandler(enabled = screen != null) { screen = if (screen == "metrics") "model" else null }
    val idx = modelIdx.coerceIn(0, maxOf(0, models.size - 1))
    when (screen) {
        "settings" -> SettingsScreen(
            current = settings,
            onChange = { settings = it; it.save(context) },
            onBack = { screen = null },
        )
        "metrics" -> MetricsScreen(onBack = { screen = "model" })
        "model" -> ModelScreen(
            settings = settings,
            onSettingsChange = { settings = it; it.save(context) },
            corpus = corpus,
            models = models,
            scanning = scanning,
            modelIdx = idx,
            onSelectModel = { modelIdx = it; models.getOrNull(it)?.let { f -> ModelManager.setPreferred(context, f.name) } },
            onRefresh = { refreshKey++ },
            onOpenMetrics = { screen = "metrics" },
            onBack = { screen = null },
        )
        else -> ChatScreen(
            settings = settings,
            corpus = corpus,
            models = models,
            scanning = scanning,
            modelIdx = idx,
            onRefresh = { refreshKey++ },
            onOpenSettings = { screen = "settings" },
            onOpenModel = { screen = "model" },
        )
    }
}

/**
 * One turn of a chat without sources: the user's message as a bubble; the model's answer with,
 * for a reasoning model, a collapsible thinking block above it. [reasoningExpanded] seeds
 * the block open (used for the in-flight turn, so the reasoning is visible as it streams); committed
 * turns default it closed so the transcript stays readable.
 */
@Composable
internal fun TurnView(turn: ChatTurn, reasoningExpanded: Boolean = false) {
    if (turn.role == "user") { UserBubble(turn.text); return }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (turn.reasoning.isNotEmpty()) ReasoningBlock(turn.reasoning, reasoningExpanded)
        // only the model's answer is read as Markdown (its speed is in Model and files)
        SelectionContainer { MarkdownText(turn.text, fontSize = 16.sp) }
    }
}

/**
 * The model's internal reasoning, rendered as a dimmed, collapsible block distinct from the answer.
 * A thinking model spends its first tokens here; surfacing it (instead of dropping it, or worse,
 * letting it leak into the answer) is what makes a Thinking-on run legible while it reasons. Tapping
 * the header toggles it; [initiallyExpanded] is the starting state.
 */
@Composable
private fun ReasoningBlock(reasoning: String, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                Text(
                    "Thinking", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (expanded) "▾" else "▸", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                SelectionContainer {
                    Text(
                        reasoning, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One-line reminder of the active configuration. A glance, not the record — [configFlags] is what
 * states the run in full. What earns a place here is what makes this a different KIND of run from
 * the next one: the lossy levers above all (dropping experts and a narrowed top-k change the
 * ANSWER, not just the speed), then the residency policies that decide where the time goes.
 */
internal fun configSummary(s: AppSettings): String {
    val parts = mutableListOf<String>()
    if (s.mmap) {
        parts += "mmap baseline (no streaming)"
    } else {
        // AndroidLM: the memory preset first, with the prompt width that comes with it
        parts += (if (s.memoryPreset == MemoryPreset.AUTO) "memory auto" else "memory ${s.memoryPreset} GB") + " (ubatch ${s.ubatch})"
        parts += when {
            s.cacheMb == AppSettings.CACHE_AUTO -> if (s.cacheCeilMb > 0) "cache auto≤${s.cacheCeilMb}" else "cache auto"
            s.cacheMb == 0 -> "cache off"
            else -> "cache ${s.cacheMb} MiB"
        }
        parts += "${s.ioThreads} lanes"
        if (s.overlap) parts += "overlap"
        if (!s.oDirect) parts += "buffered"
        parts += "dense ${s.denseWeights.flag}"
        // Gated exactly as sessionArgv gates the flags themselves: a lever the CLI will not be told
        // about must not be named here, or the line goes back to describing a run that never ran.
        val cacheOn = s.cacheMb == AppSettings.CACHE_AUTO || s.cacheMb > 0
        if (cacheOn) {
            if (s.prefetchLayers > 0) parts += "prefetch ${s.prefetchLayers}"
            else if (s.predictPrefetch) parts += "predict" + if (s.predictSpecMax > 0) " ${s.predictSpecMax}" else ""
            if (s.dropColdPct > 0) parts += "drop ${s.dropColdPct}%"
            if (s.substitutePct > 0) parts += "prefer-cached ${s.substitutePct}%"
        }
    }
    parts += "${s.effectiveThreads()} threads${if (s.threads == AppSettings.THREADS_AUTO) " (auto)" else ""}"
    if (s.nExpertUsed > 0) parts += "top-k ${s.nExpertUsed}"
    parts += "thinking ${if (s.thinking) "on" else "off"}"
    parts += "build ${BuildConfig.GIT_SHA}"
    return parts.joinToString(" · ")
}

/**
 * The whole configuration as (flag, value) pairs, read back from the argv these settings would
 * actually open the session with. Deliberately NOT a second hand-kept list beside
 * [AppSettings.sessionArgv]: a knob added there appears here on its own, and a knob the argv gates
 * off (dropping without a cache, prefetch under mmap) is absent here for the same reason it is
 * absent from the run. A curated subset is what left the metrics views unable to state their own
 * drop fraction (#136); this display starts out unable to drift.
 */
internal fun configFlags(s: AppSettings, modelPath: String, csv: Boolean): List<Pair<String, String>> {
    // Placeholders for the two paths the argv needs but that say nothing about the configuration;
    // the CSV one only has to be non-null for --csv to be emitted at all.
    val argv = s.sessionArgv("bmoe-cli", modelPath, if (csv) "metrics.csv" else null)
    val out = mutableListOf<Pair<String, String>>()
    var i = 1 // argv[0] is the binary
    while (i < argv.size) {
        val flag = argv[i]
        val next = argv.getOrNull(i + 1)
        // Every value here is a path, a number or a keyword — none of them start with a dash, so
        // the next token being one is what distinguishes a value from the following flag.
        if (next != null && !next.startsWith("-")) {
            // Paths are the user's own storage layout, not configuration: name the file, not where it lives.
            out += flag to (if (flag == "-m" || flag == "--csv") File(next).name else next)
            i += 2
        } else {
            out += flag to "on"
            i += 1
        }
    }
    return out
}

@Composable
internal fun TelemetryCard(ui: UiState, threads: Int, overlap: Boolean, ioThreads: Int) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.error != null) {
                Text("error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text(ui.error, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                // The context-overflow error is recoverable: the session stays loaded, but the
                // conversation is full. Point the user at New chat.
                if ("n_ctx" in ui.error) {
                    Text("Conversation is full — tap New chat to start over.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            val t = ui.telemetry
            // Once the run finishes the summary carries the aggregate average; show that as the
            // headline rate. While generating, show the live instantaneous (last-token) rate.
            val done = t.avgTokensPerSecond > 0
            Text(
                if (done) {
                    // t.step is the last token's 1-based index = tokens actually generated (which can be
                    // < t.steps, the n_predict target, when the model stops early on an end-of-text token).
                    String.format(Locale.US, "%.2f tok/s   avg (%d tokens)", t.avgTokensPerSecond, t.step)
                } else {
                    String.format(Locale.US, "%.2f tok/s   (token %d/%d)", t.tokensPerSecond, t.step, t.steps)
                },
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            if (ui.streaming) {
                // The compute-vs-flash split and cache hit rate only mean anything with the streamer
                // running. Under mmap the model faults in through the OS page cache, invisible here.
                // The split itself — live last token vs run average, and which term is measured
                // rather than residual — is derived in breakdown(); this only draws it. The busy
                // denominator counts the I/O lanes; the compute one does not (see breakdown()).
                val b = breakdown(t, overlap,
                    busyThreads = threads + if (overlap) ioThreads else 0, computeThreads = threads)
                val suffix = if (b.isAverage) " avg" else ""

                // Headline: token time and its inverse, so no mental arithmetic to get tok/s.
                if (b.wallMs > 0.0) {
                    Text(String.format(Locale.US, "%.0f ms/token  →  %.2f tok/s", b.wallMs, 1000.0 / b.wallMs),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                MeterRow("compute$suffix", b.computeMs, b.totalMs, MaterialTheme.colorScheme.primary)
                MeterRow("flash wait$suffix", b.flashWaitMs, b.totalMs, MaterialTheme.colorScheme.tertiary)
                MeterRow("cache mgmt$suffix", b.mgmtMs, b.totalMs, MaterialTheme.colorScheme.secondary)
                // The fourth bar is the wall time nobody claims: off-CPU cost (zram, preemption,
                // frequency caps) the residual `compute_ms` used to absorb silently. Muted color —
                // it is a question mark, not a workload.
                MeterRow("unattributed$suffix", b.unattributedMs, b.totalMs, MaterialTheme.colorScheme.outline)

                // Diagnostic line: WHY compute is what it is, plus cache hit. Near 100% busy is
                // genuinely compute-bound, well below means a throttled/preempted core (a frequency
                // cap, a co-resident process). Major faults/token > 0 means dense weights re-faulted
                // from flash inside the decode.
                val hit = if (t.cacheHitPct >= 0) String.format(Locale.US, "hit %.0f%%", t.cacheHitPct) else "hit —"
                val diag = buildString {
                    if (b.cpuBusyPct >= 0.0) {
                        append(String.format(Locale.US, "CPU %.0f%% busy", b.cpuBusyPct))
                        if (b.faultsPerToken >= 0.0) {
                            append(String.format(Locale.US, "  ·  %.0f faults/tok", b.faultsPerToken))
                        }
                        append("  ·  ")
                    }
                    append(hit)
                }
                Text(diag, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.ioMode != null) {
                    Text("I/O ${ui.ioMode}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // CPU temperature — live while generating, a proxy for thermal headroom.
                ui.cpuTempC?.let {
                    Text(String.format(Locale.US, "CPU %.1f°C", it), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // End-of-run figures from the summary: prefill rate, time-to-first-token, the flash
                // streamed this turn and the cache footprint. Only meaningful once generation finishes.
                if (done) {
                    if (t.prefillTps > 0 || t.ttftS >= 0) {
                        val prefill = if (t.prefillTps > 0) String.format(Locale.US, "prefill %.1f tok/s", t.prefillTps) else ""
                        val ttft = if (t.ttftS >= 0) String.format(Locale.US, "TTFT %.2fs", t.ttftS) else ""
                        Text(listOf(prefill, ttft).filter { it.isNotEmpty() }.joinToString("   ·   "), fontSize = 13.sp)
                    }
                    if (t.readMib >= 0 || t.cacheResidentMib >= 0) {
                        val streamed = if (t.readMib >= 0) String.format(Locale.US, "streamed %.0f MB", t.readMib) else ""
                        val cache = if (t.cacheResidentMib >= 0)
                            String.format(Locale.US, "cache %.0f/%.0f MiB", t.cacheResidentMib, t.cacheBudgetMib) else ""
                        Text(listOf(streamed, cache).filter { it.isNotEmpty() }.joinToString("   ·   "),
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text(
                    "mmap baseline — the model is read through the OS page cache, so per-token flash I/O, " +
                        "the compute split and cache hits are not observable in this mode.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.summary.isNotEmpty()) {
                Text(ui.summary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun MeterRow(label: String, value: Double, total: Double, color: androidx.compose.ui.graphics.Color) {
    val frac = if (total > 0) (value / total).toFloat().coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        LinearProgressIndicator(
            progress = { frac },
            color = color,
            modifier = Modifier.weight(1f).height(8.dp),
        )
        Text(String.format(Locale.US, "%.0f ms", value), fontSize = 12.sp, modifier = Modifier.width(56.dp))
    }
}

/**
 * Send [prompt] to the engine. If a session is already loaded for this exact model+settings
 * ([currentSig] matches), the prompt just goes to the warm process (no reload, cache intact);
 * otherwise the session is (re)started with this configuration and the prompt runs as soon as it
 * reports ready. Per-prompt options (n_predict, thinking) ride the request, not the session.
 */
/** Starts a session for [model] with nothing to run yet: the model loads ahead of the first question. */
private fun preloadSession(context: android.content.Context, model: File, settings: AppSettings) {
    val csv = if (settings.metricsCsv) AppSettings.newMetricsCsvPath(context) else null
    val argv = ArrayList(settings.sessionArgv(ModelManager.cliPath(context), model.absolutePath, csv))
    ContextCompat.startForegroundService(
        context,
        Intent(context, RunService::class.java)
            .putExtra(RunService.EXTRA_MODEL, model.absolutePath)
            .putStringArrayListExtra(RunService.EXTRA_ARGV, argv)
            .putExtra(RunService.EXTRA_SIG, settings.sessionSignature(model.absolutePath)),
    )
}

internal fun launchPrompt(
    context: android.content.Context,
    model: File,
    prompt: String,
    settings: AppSettings,
    currentSig: String?,
    clearKv: Boolean,
) {
    RunBus.resetGeneration()
    val sig = settings.sessionSignature(model.absolutePath)
    if (currentSig == sig) {
        context.startService(
            Intent(context, RunService::class.java)
                .setAction(RunService.ACTION_GENERATE)
                .putExtra(RunService.EXTRA_PROMPT, prompt)
                .putExtra(RunService.EXTRA_NPREDICT, settings.nPredict)
                .putExtra(RunService.EXTRA_THINK, settings.thinking)
                .putExtra(RunService.EXTRA_CLEAR_KV, clearKv)
        )
    } else {
        // A new session starts with an empty KV and a cleared transcript, so its first turn
        // always clears regardless of [clearKv].
        // One CSV per session: the engine holds it open across every turn, so it is opened here,
        // where a session is opened, and nowhere else.
        val csv = if (settings.metricsCsv) AppSettings.newMetricsCsvPath(context) else null
        val argv = ArrayList(settings.sessionArgv(ModelManager.cliPath(context), model.absolutePath, csv))
        ContextCompat.startForegroundService(
            context,
            Intent(context, RunService::class.java)
                .putExtra(RunService.EXTRA_MODEL, model.absolutePath)
                .putStringArrayListExtra(RunService.EXTRA_ARGV, argv)
                .putExtra(RunService.EXTRA_SIG, sig)
                .putExtra(RunService.EXTRA_PROMPT, prompt)
                .putExtra(RunService.EXTRA_NPREDICT, settings.nPredict)
                .putExtra(RunService.EXTRA_THINK, settings.thinking)
                .putExtra(RunService.EXTRA_CLEAR_KV, true)
        )
    }
}

/**
 * AndroidLM research mode: hand [question] to the research pipeline in [RunService]. Same two
 * paths as [launchPrompt]: a warm session for this exact model+settings gets the question at
 * once; otherwise the session is (re)started and the pipeline runs as soon as it reports ready.
 * The pipeline sets n_predict and thinking itself (they are part of the method, not preferences).
 */
internal fun launchResearch(
    context: android.content.Context,
    model: File,
    question: String,
    settings: AppSettings,
    currentSig: String?,
) {
    RunBus.resetGeneration()
    // the question opens a chat if none is open; RunService saves it as the run goes
    RunBus.update { if (it.chatId == null) it.copy(chatId = ChatStore.newId()) else it }
    val sig = settings.sessionSignature(model.absolutePath)
    if (currentSig == sig) {
        context.startService(
            Intent(context, RunService::class.java)
                .setAction(RunService.ACTION_RESEARCH)
                .putExtra(RunService.EXTRA_QUESTION, question)
        )
    } else {
        val csv = if (settings.metricsCsv) AppSettings.newMetricsCsvPath(context) else null
        val argv = ArrayList(settings.sessionArgv(ModelManager.cliPath(context), model.absolutePath, csv))
        ContextCompat.startForegroundService(
            context,
            Intent(context, RunService::class.java)
                .putExtra(RunService.EXTRA_MODEL, model.absolutePath)
                .putStringArrayListExtra(RunService.EXTRA_ARGV, argv)
                .putExtra(RunService.EXTRA_SIG, sig)
                .putExtra(RunService.EXTRA_QUESTION, question)
        )
    }
}
