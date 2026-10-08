package io.bigmoeonedge.example

// AndroidLM: setting the app up without adb. The model and the corpus files reach the phone some
// other way (the phone's browser, a USB drive) and are imported here: each picked file is
// recognised, copied into app storage and checked against its SHA-256 (SetupFiles, in the
// research module, where the copy is tested). The "online" build can also download them
// (SetupDownload.kt).

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.androidlm.research.SetupFile
import org.androidlm.research.SetupFiles
import org.androidlm.research.android.CorpusLocator
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Where [file] is on this phone: [found] is a copy that matches it; [outdated] when only other
 * versions are there; [partBytes], what an unfinished download of it holds so far.
 */
data class SetupStatus(val file: SetupFile, val found: File?, val outdated: Boolean, val partBytes: Long = 0)

object SetupLocator {
    /**
     * Each of the setup files, in manifest order. A copy on shared storage (/storage: Downloads,
     * the app's external dir) does not count: the engine's direct reads do not work there, so the
     * model still has to be imported. Blocking stats: call off the main thread.
     */
    fun status(ctx: Context): List<SetupStatus> = SetupFiles.ALL.map { f ->
        val copies = (if (f.dir == "models") ModelManager.copiesOf(ctx, f.name)
        else CorpusLocator.dirs(ctx).map { File(it, f.name) }.filter { it.isFile })
            .filter { !it.absolutePath.startsWith("/storage/") && !it.absolutePath.startsWith("/sdcard/") }
        val ok = copies.firstOrNull { it.canRead() && SetupFiles.matches(it, f) }
        val part = if (ok == null) SetupFiles.partOf(destination(ctx, f)).length() else 0L
        SetupStatus(f, ok, outdated = ok == null && copies.isNotEmpty(), partBytes = part)
    }

    /** Where an import puts [f]: the first directory each scan looks in. */
    fun destination(ctx: Context, f: SetupFile): File =
        if (f.dir == "models") File(ModelManager.internalModelsDir(ctx), f.name)
        else File(File(ctx.filesDir, CorpusLocator.DIR).apply { mkdirs() }, f.name)
}

/** What an import or a download is doing, for the setup card; the notification says the same. */
data class ImportProgress(
    val running: Boolean = false,
    /** "Copying" for an import, "Downloading" for a download. */
    val verb: String = "Copying",
    /** A downloaded file is being checked against its SHA-256. */
    val checking: Boolean = false,
    /** The label of the file being copied, its place in the run, and the bytes so far. */
    val file: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val copied: Long = 0,
    val total: Long = 0,
    /** One line per file this run imported, or found already there. */
    val done: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

object SetupBus {
    private val _state = MutableStateFlow(ImportProgress())
    val state: StateFlow<ImportProgress> = _state.asStateFlow()
    fun update(f: (ImportProgress) -> ImportProgress) = _state.update(f)
}

/**
 * Copies the picked files into app storage, one after another, as a foreground service: a full
 * set is 37GB and takes minutes, and the copy must go on with the screen off or the app in the
 * background. With [EXTRA_DELETE] each original on the phone's own storage is deleted once its
 * copy is checked, so the downloads do not take the space twice; a USB drive is never written.
 */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelled = true
            else -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent?) {
        startForeground(NOTIF_ID, notification("Preparing the import…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return
        val uris = intent?.let { IntentCompat.getParcelableArrayListExtra(it, EXTRA_URIS, Uri::class.java) }
        if (uris.isNullOrEmpty()) { finish(); return }
        val deleteOriginals = intent.getBooleanExtra(EXTRA_DELETE, false)
        cancelled = false
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidLM:import").apply { acquire(3 * 60 * 60 * 1000L) }
        SetupBus.update { ImportProgress(running = true) }
        job = scope.launch {
            try {
                importAll(uris, deleteOriginals)
            } finally {
                finish()
            }
        }
    }

    private class Picked(val uri: Uri, val name: String, val size: Long, val file: SetupFile)

    private fun importAll(uris: List<Uri>, deleteOriginals: Boolean) {
        val errors = mutableListOf<String>()
        val done = mutableListOf<String>()
        val picked = uris.mapNotNull { uri ->
            val (name, size) = runCatching { SafImport.queryNameAndSize(this, uri) }.getOrElse {
                errors += "Could not read a picked file: ${it.message}"
                return@mapNotNull null
            }
            val f = SetupFiles.identify(name, size)
            if (f == null) errors += "$name is not one of AndroidLM's files"
            f?.let { Picked(uri, name, size, it) }
        }.distinctBy { it.file.name }.sortedBy { SetupFiles.ALL.indexOf(it.file) }
        val status = SetupLocator.status(this).associateBy { it.file.name }
        val todo = picked.filter { p ->
            val there = status[p.file.name]?.found != null
            if (there) done += "${p.file.label}: already on this phone"
            !there
        }
        SetupBus.update { it.copy(done = done.toList(), errors = errors.toList(), count = todo.size) }

        for ((i, p) in todo.withIndex()) {
            if (cancelled) break
            val f = p.file
            if (p.size >= 0 && p.size != f.bytes) {
                errors += "${p.name} is ${ModelManager.gbLabel(p.size)}, not ${ModelManager.gbLabel(f.bytes)}: an unfinished download or another version"
                SetupBus.update { it.copy(errors = errors.toList()) }
                continue
            }
            val dest = SetupLocator.destination(this, f)
            val free = dest.parentFile!!.usableSpace
            if (f.bytes + SPARE > free) {
                errors += "Not enough space for ${f.name}: it needs ${ModelManager.gbLabel(f.bytes + SPARE)}, the phone has ${ModelManager.gbLabel(free)} free"
                SetupBus.update { it.copy(errors = errors.toList()) }
                continue
            }
            SetupBus.update { it.copy(file = f.label, index = i, copied = 0, total = f.bytes) }
            var last = 0L
            val t0 = System.nanoTime()
            try {
                val input = contentResolver.openInputStream(p.uri) ?: throw java.io.IOException("cannot open ${p.name}")
                input.use {
                    SetupFiles.copyVerified(it, f, dest, onProgress = { copied ->
                        val now = System.nanoTime()
                        if (now - last > 500_000_000L || copied == f.bytes) {
                            last = now
                            SetupBus.update { s -> s.copy(copied = copied) }
                            notify("${f.label} (${i + 1} of ${todo.size})", (copied * 100 / f.bytes).toInt())
                        }
                    }, cancelled = { cancelled })
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                Log.i(RunService.LOG_TAG, "import ${f.name}: ${f.bytes} bytes in $ms ms (${f.bytes / 1000 / maxOf(ms, 1)} MB/s) -> ${dest.path}")
                var line = "${f.label}: copied and checked"
                if (deleteOriginals && onPhoneStorage(p.uri)) {
                    val gone = runCatching { DocumentsContract.deleteDocument(contentResolver, p.uri) }.getOrDefault(false)
                    line += if (gone) "; the download was deleted" else "; delete ${p.name} in Files to free its space"
                }
                done += line
            } catch (_: CancellationException) {
                break
            } catch (t: Throwable) {
                Log.w(RunService.LOG_TAG, "import ${f.name} failed", t)
                errors += t.message ?: "${f.name}: the copy failed"
            }
            SetupBus.update { it.copy(done = done.toList(), errors = errors.toList()) }
        }
        if (cancelled) errors += "Import cancelled"
        SetupBus.update { it.copy(running = false, file = null, done = done.toList(), errors = errors.toList()) }
    }

    /**
     * Is [uri] on the phone's own storage (Downloads, or a folder of internal storage) rather than
     * a USB drive? Only such an original is deleted after its copy.
     */
    private fun onPhoneStorage(uri: Uri): Boolean = when (uri.authority) {
        "com.android.providers.downloads.documents" -> true
        "com.android.externalstorage.documents" ->
            runCatching { DocumentsContract.getDocumentId(uri).startsWith("primary:") }.getOrDefault(false)
        else -> false
    }

    private fun finish() {
        SetupBus.update { it.copy(running = false, file = null) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val s = SetupBus.state.value
        if (s.done.isNotEmpty() || s.errors.isNotEmpty()) {
            val text = if (s.errors.isEmpty()) "Import finished: the files are ready" else s.errors.last()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(DONE_ID, notification(text, -1, ongoing = false))
        }
        stopSelf()
    }

    override fun onDestroy() {
        cancelled = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(text: String, percent: Int, ongoing: Boolean = true): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (ongoing) "Importing AndroidLM's files" else "AndroidLM")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
        if (ongoing) {
            b.setProgress(100, percent.coerceAtLeast(0), percent < 0)
            val cancel = PendingIntent.getService(
                this, 0, Intent(this, ImportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        }
        return b.build()
    }

    private fun notify(text: String, percent: Int) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text, percent))
    }

    companion object {
        private const val ACTION_CANCEL = "io.bigmoeonedge.example.IMPORT_CANCEL"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_DELETE = "delete_originals"
        private const val CHANNEL = "setup"
        private const val NOTIF_ID = 2
        private const val DONE_ID = 3
        /** Room left on the phone after a copy, so the import never fills it. */
        private const val SPARE = 500_000_000L

        fun start(ctx: Context, uris: List<Uri>, deleteOriginals: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, ImportService::class.java)
                    .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                    .putExtra(EXTRA_DELETE, deleteOriginals),
            )
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, ImportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
