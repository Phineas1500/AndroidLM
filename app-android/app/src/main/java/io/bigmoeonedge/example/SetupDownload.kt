package io.bigmoeonedge.example

// AndroidLM: the setup downloader of the "online" build. It fetches the model and corpus files
// straight into app storage, from the manifest's servers or one the user names, resuming what an
// interrupted download left (SetupFiles.download, in the research module, where it is tested).
// It is the only code in the app that uses the network; the "offline" build has no INTERNET
// permission and does not declare this service, and research questions never reach it.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.androidlm.research.SetupFile
import org.androidlm.research.SetupFiles
import java.util.concurrent.CancellationException

/** The server the files come from: the manifest's own URLs, or another one the user names. */
object SetupServer {
    private const val PREFS = "androidlm_setup"
    private const val KEY = "server"

    /** The other server's base URL, or "" for the manifest's (Hugging Face). */
    fun get(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(ctx: Context, base: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, base.trim()).apply()
    }

    /** Where [f] is fetched from: its manifest URL, or `<server>/<file name>` from another server. */
    fun urlOf(base: String, f: SetupFile): String {
        val b = base.trim().trimEnd('/')
        return if (b.isEmpty()) f.url else "$b/${f.name}"
    }

    /** An http(s) URL with a host, or "" (the default). */
    fun valid(base: String): Boolean {
        val b = base.trim()
        if (b.isEmpty()) return true
        val u = runCatching { java.net.URL(b) }.getOrNull() ?: return false
        return (u.protocol == "https" || u.protocol == "http") && u.host.isNotEmpty()
    }

    enum class Network { NONE, METERED, UNMETERED }

    /** The phone's connection right now: none, metered (mobile data) or not (Wi-Fi). */
    fun network(ctx: Context): Network {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Network.UNMETERED
        if (cm.activeNetwork == null) return Network.NONE
        return if (cm.isActiveNetworkMetered) Network.METERED else Network.UNMETERED
    }
}

/**
 * Downloads the named setup files one after another, as a foreground service: a full set is
 * 37GB and takes an hour or more, with the screen off. Progress goes to the setup card through
 * SetupBus, as an import's does; a cancelled or dropped download keeps its part file, and the
 * next Download resumes it.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelled = true
            else -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent?) {
        startForeground(NOTIF_ID, notification("Preparing the download…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES)
        if (names.isNullOrEmpty()) { finish(); return }
        cancelled = false
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidLM:download").apply { acquire(12 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndroidLM:download")?.apply { acquire() }
        SetupBus.update { ImportProgress(running = true, verb = "Downloading") }
        job = scope.launch {
            try {
                downloadAll(names.mapNotNull { SetupFiles.byName(it) })
            } finally {
                finish()
            }
        }
    }

    private fun downloadAll(files: List<SetupFile>) {
        val errors = mutableListOf<String>()
        val done = mutableListOf<String>()
        val status = SetupLocator.status(this).associateBy { it.file.name }
        val todo = files.filter { f ->
            val there = status[f.name]?.found != null
            if (there) done += "${f.label}: already on this phone"
            !there
        }
        val server = SetupServer.get(this)
        SetupBus.update { it.copy(done = done.toList(), count = todo.size) }
        for ((i, f) in todo.withIndex()) {
            if (cancelled) break
            val dest = SetupLocator.destination(this, f)
            val have = SetupFiles.partOf(dest).length()
            val free = dest.parentFile!!.usableSpace
            if (f.bytes - have + SPARE > free) {
                errors += "Not enough space for ${f.name}: it needs ${ModelManager.gbLabel(f.bytes - have + SPARE)} more, " +
                    "the phone has ${ModelManager.gbLabel(free)} free"
                SetupBus.update { it.copy(errors = errors.toList()) }
                continue
            }
            val url = SetupServer.urlOf(server, f)
            SetupBus.update { it.copy(file = f.label, index = i, copied = have, total = f.bytes, checking = false) }
            var last = 0L
            val t0 = System.nanoTime()
            try {
                SetupFiles.download(
                    url, f, dest,
                    onProgress = { got ->
                        val now = System.nanoTime()
                        if (now - last > 500_000_000L || got == f.bytes) {
                            last = now
                            SetupBus.update { s -> s.copy(copied = got) }
                            notify("${f.label} (${i + 1} of ${todo.size})", (got * 100 / f.bytes).toInt())
                        }
                    },
                    onChecking = {
                        SetupBus.update { s -> s.copy(copied = f.bytes, checking = true) }
                        notify("Checking ${f.label}", -1)
                    },
                    cancelled = { cancelled },
                )
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(RunService.LOG_TAG, "download ${f.name}: ${f.bytes - have} bytes in $ms ms from ${java.net.URL(url).host} -> ${dest.path}")
                done += "${f.label}: downloaded and checked"
            } catch (_: CancellationException) {
                break
            } catch (t: Throwable) {
                Log.w(RunService.LOG_TAG, "download ${f.name} from $url failed", t)
                errors += t.message ?: "${f.name}: the download failed"
            }
            SetupBus.update { it.copy(done = done.toList(), errors = errors.toList(), checking = false) }
        }
        if (cancelled) errors += "Download paused: Download resumes it"
        SetupBus.update { it.copy(running = false, file = null, checking = false, done = done.toList(), errors = errors.toList()) }
    }

    private fun finish() {
        SetupBus.update { it.copy(running = false, file = null, checking = false) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val s = SetupBus.state.value
        if (s.done.isNotEmpty() || s.errors.isNotEmpty()) {
            val text = if (s.errors.isEmpty()) "Download finished: the files are ready" else s.errors.last()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(DONE_ID, notification(text, -1, ongoing = false))
        }
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean = true): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (ongoing) "Downloading AndroidLM's files" else "AndroidLM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
        if (ongoing) {
            b.setProgress(100, percent.coerceAtLeast(0), percent < 0)
            val cancel = PendingIntent.getService(
                this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "Pause", cancel).build())
        }
        return b.build()
    }

    private fun notify(text: String, percent: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text, percent))
    }

    companion object {
        private const val ACTION_CANCEL = "io.bigmoeonedge.example.DOWNLOAD_CANCEL"
        private const val EXTRA_NAMES = "names"
        private const val CHANNEL = "setup"
        private const val NOTIF_ID = 4
        private const val DONE_ID = 5
        /** Room left on the phone after a download, so it never fills it. */
        private const val SPARE = 500_000_000L

        fun start(ctx: Context, files: List<SetupFile>) {
            ctx.startForegroundService(
                Intent(ctx, DownloadService::class.java).putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name })),
            )
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
