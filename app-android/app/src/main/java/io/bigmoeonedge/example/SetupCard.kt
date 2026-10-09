package io.bigmoeonedge.example

// AndroidLM: the "Set up" card. It lists the files the app reads (SetupFiles), says which are on
// the phone, downloads the missing ones into the app (DownloadService, "online" build) or opens
// each one's download in the browser ("offline" build, which has no network access), and imports
// the files the user picks (ImportService).

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidlm.research.SetupFile
import org.androidlm.research.SetupFiles

/**
 * Shown while a file is missing (open when a required one is), and while an import runs or has
 * something to report. [onImported] re-scans the models and the corpus when an import ends.
 */
@Composable
fun SetupCard(scanning: Boolean, onImported: () -> Unit) {
    val context = LocalContext.current
    val progress by SetupBus.state.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<List<SetupStatus>?>(null) }
    var free by remember { mutableStateOf(0L) }
    LaunchedEffect(scanning, progress.running) {
        if (scanning || progress.running) return@LaunchedEffect
        status = withContext(Dispatchers.IO) { SetupLocator.status(context) }
        free = withContext(Dispatchers.IO) { ModelManager.internalModelsDir(context).usableSpace }
    }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(progress.running) {
        if (wasRunning && !progress.running) onImported()
        wasRunning = progress.running
    }
    val all = status ?: return
    // the optional larger model is offered in a section of its own: missing it never opens the card
    val st = all.filter { !it.file.extra }
    val extras = all.filter { it.file.extra }
    // an older version of a file still works until the new one is in: an update, not a gap
    val missing = st.filter { it.found == null && !it.outdated }
    val updates = st.filter { it.found == null && it.outdated }
    val todo = missing + updates
    val extraMissing = extras.filter { it.found == null }
    val report = progress.running || progress.done.isNotEmpty() || progress.errors.isNotEmpty()
    if (todo.isEmpty() && extraMissing.isEmpty() && !report) return

    var open by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val isOpen = open ?: (missing.any { it.file.required } || report)
    var deleteOriginals by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // a download asked for on mobile data, or with no connection, waits for a second tap
    var asked by remember { mutableStateOf<NetAsk?>(null) }
    // and one that only fits once older versions are deleted asks before they are
    var replaceAsked by remember { mutableStateOf<List<SetupFile>?>(null) }
    fun download(files: List<SetupFile>, replace: Boolean = false, confirmed: Boolean = false) {
        error = null
        if (!replace) {
            val of = all.associateBy { it.file.name }
            val need = files.sumOf { it.bytes - (of[it.name]?.partBytes ?: 0L) } + 500_000_000L
            val old = files.sumOf { of[it.name]?.oldBytes ?: 0L }
            if (need > free && old > 0 && need <= free + old) { replaceAsked = files; return }
        }
        val net = SetupServer.network(context)
        if (!confirmed && net != SetupServer.Network.UNMETERED) { asked = NetAsk(files, net, replace); return }
        asked = null
        DownloadService.start(context, files, replace)
    }
    replaceAsked?.let { files ->
        val olds = all.filter { s -> s.oldBytes > 0 && files.any { it.name == s.file.name } }
        AlertDialog(
            onDismissRequest = { replaceAsked = null },
            title = { Text("Delete the older version first?") },
            text = {
                Text(
                    "The phone has ${ModelManager.gbLabel(free)} free: not enough for the new " +
                        olds.joinToString(", ") { it.file.label } + " next to the older version " +
                        "(${sizeLabel(olds.sumOf { it.oldBytes })}). AndroidLM can delete the older version " +
                        "first. Until the new one is downloaded and checked, " +
                        (if (olds.any { it.file.name == SetupFiles.WIKI }) "research mode has no Wikipedia" else "it is missing") +
                        ", and the model is unloaded.",
                )
            },
            confirmButton = { TextButton(onClick = { replaceAsked = null; download(files, replace = true) }) { Text("Delete and download") } },
            dismissButton = { TextButton(onClick = { replaceAsked = null }) { Text("Not now") } },
        )
    }
    asked?.let { (files, net, replace) ->
        val size = sizeLabel(files.sumOf { it.bytes })
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(if (net == SetupServer.Network.NONE) "No connection" else "Download over mobile data?") },
            text = {
                Text(
                    if (net == SetupServer.Network.NONE) "The phone is not connected to a network right now. Connect to Wi-Fi, then download the $size."
                    else "The phone is on mobile data, and the download is $size.",
                )
            },
            confirmButton = { TextButton(onClick = { download(files, replace, confirmed = true) }) { Text(if (net == SetupServer.Network.NONE) "Try anyway" else "Download") } },
            dismissButton = { TextButton(onClick = { asked = null }) { Text("Not now") } },
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ImportService.start(context, uris, deleteOriginals)
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set up", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !isOpen }) { Text(if (isOpen) "Close" else "Open") }
            }
            if (!isOpen) {
                Hint(
                    when {
                        missing.isNotEmpty() -> "Not on this phone yet: " + missing.joinToString(", ") { it.file.label }
                        updates.isNotEmpty() -> "Update available: " + updates.joinToString(", ") { it.file.label } +
                            " (${sizeLabel(updates.sumOf { it.file.bytes - it.partBytes })})"
                        else -> "Optional: a larger model, slower and more thorough (${ModelManager.gbLabel(extras.sumOf { it.file.bytes })})"
                    },
                )
                return@Column
            }
            Hint(
                if (BuildConfig.SETUP_DOWNLOADS)
                    "AndroidLM answers without the network. Its model and libraries are ${st.size} files of " +
                        "${ModelManager.gbLabel(st.sumOf { it.file.bytes })}: download them here (the only thing " +
                        "the app uses the internet for), or import files copied from a computer or a USB drive. " +
                        "Each file is checked against its SHA-256 before the app uses it."
                else
                    "AndroidLM never uses the network. Its model and libraries are ${st.size} files of " +
                        "${ModelManager.gbLabel(st.sumOf { it.file.bytes })}. Download them with the " +
                        "phone's browser, or copy them from a computer or a USB drive, then import them " +
                        "here: each file is checked and copied into the app.",
            )
            @Composable
            fun FileRow(s: SetupStatus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.file.label, fontSize = 14.sp)
                        Hint(
                            "${s.file.name} · ${sizeLabel(s.file.bytes)} · " + when {
                                s.found != null -> "on this phone"
                                s.partBytes > 0 -> "${sizeLabel(s.partBytes)} downloaded so far"
                                s.outdated -> "newer than the copy on this phone, which works until this one is in"
                                s.file.required -> "needed"
                                else -> "optional"
                            },
                        )
                    }
                    if (s.found == null) {
                        TextButton(onClick = {
                            if (BuildConfig.SETUP_DOWNLOADS) download(listOf(s.file))
                            else error = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.file.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.exceptionOrNull()?.let { "No browser to download with: ${s.file.url}" }
                        }, enabled = !progress.running) {
                            Text(when { s.partBytes > 0 -> "Resume"; s.outdated -> "Update"; else -> "Download" })
                        }
                    }
                }
            }
            st.forEach { FileRow(it) }
            if (updates.any { it.file.name == SetupFiles.WIKI }) {
                Hint(
                    "The new Wikipedia has every article in full; the older one has only the opening " +
                        "section of the 4 million least-read.",
                )
            }
            if (extras.isNotEmpty()) {
                Text("Optional: a larger model", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Hint(
                    "Qwen3.8-Flash-Next, in two parts of ${ModelManager.gbLabel(extras.sumOf { it.file.bytes })} " +
                        "together. On 61 research questions (restaurants, crypto, travel, emergencies) its " +
                        "answers scored 67% of a web search + frontier AI answer, against 63% for the main " +
                        "model, but each takes about 3 to 4 times as long (first words after about 2 minutes, " +
                        "done after about 8). Both models and the libraries together take about 113GB, more " +
                        "than a 128GB phone holds. Once both parts are imported, choose it in the Model list.",
                )
                extras.forEach { FileRow(it) }
                if (extraMissing.isNotEmpty()) {
                    val need = extraMissing.sumOf { it.file.bytes }
                    Hint(
                        "Its missing parts are ${sizeLabel(need)}" +
                            if (need + 2_000_000_000L <= free) "." else ": free up some space first, or import them with the box below ticked.",
                    )
                }
            }
            if (BuildConfig.SETUP_DOWNLOADS && todo.isNotEmpty()) {
                val need = todo.sumOf { it.file.bytes - it.partBytes }
                Button(
                    onClick = { download(todo.map { it.file }) },
                    enabled = !progress.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            missing.isEmpty() -> "Download the update"
                            updates.isEmpty() -> "Download all missing"
                            else -> "Download all"
                        } + " (${sizeLabel(need)})",
                    )
                }
                Hint(
                    "The phone has ${ModelManager.gbLabel(free)} free" + when {
                        need + 500_000_000L <= free -> "."
                        need + 500_000_000L <= free + updates.sumOf { it.oldBytes } ->
                            ": enough once the older version is deleted, which the download offers to do."
                        else -> ": free up some space first."
                    } + " A download continues with the screen off, and one that stops resumes where it left off.",
                )
                ServerRow(enabled = !progress.running)
                Text("Or import files copied to the phone", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            if (todo.isNotEmpty() && !BuildConfig.SETUP_DOWNLOADS) {
                // downloaded files already take their space, so the import itself needs room for
                // one copy at a time when each download is deleted after it; and an import deletes
                // the older version of a file when the new one needs its room
                val need = todo.sumOf { it.file.bytes }
                val largest = todo.maxOf { it.file.bytes }
                val room = free + updates.sumOf { it.oldBytes }
                Hint(
                    "The files to get are ${sizeLabel(need)}; the phone has ${ModelManager.gbLabel(free)} free." + when {
                        need <= free -> ""
                        need <= room -> " That is enough: an older version is deleted when the new one needs its room."
                        room >= largest + 500_000_000L ->
                            " That is enough when they are already in this phone's Downloads and the box below is " +
                                "ticked: each download is deleted once it is copied, so only one file at a time needs " +
                                "room (the largest is ${sizeLabel(largest)})."
                        else -> " The largest needs ${sizeLabel(largest + 500_000_000L)}: free up some space first."
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = deleteOriginals, onCheckedChange = { deleteOriginals = it }, enabled = !progress.running)
                Text(
                    "Delete each download once it is copied, so it does not take the space twice " +
                        "(files on a USB drive are kept)",
                    fontSize = 12.sp,
                )
            }
            Button(
                onClick = { error = null; picker.launch(arrayOf("*/*")) },
                enabled = !progress.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import files…") }
            Hint("In the file picker, select all the files at once (press and hold the first).")

            if (progress.running) {
                val name = progress.file
                val downloading = progress.verb == "Downloading"
                if (name == null || progress.checking) {
                    Text(if (name == null) "Preparing…" else "Checking $name…", fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(
                        "${progress.verb} $name (${progress.index + 1} of ${progress.count}): " +
                            "${ModelManager.gbLabel(progress.copied)} of ${ModelManager.gbLabel(progress.total)}",
                        fontSize = 12.sp,
                    )
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) (progress.copied.toFloat() / progress.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { if (downloading) DownloadService.cancel(context) else ImportService.cancel(context) }) {
                    Text(if (downloading) "Pause" else "Cancel")
                }
            }
            progress.done.forEach { Text("✓ $it", fontSize = 12.sp) }
            (progress.errors + listOfNotNull(error)).forEach {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Where the downloads come from: the manifest's servers (Hugging Face) unless the user names
 * another, which must serve each file under its own name.
 */
@Composable
private fun ServerRow(enabled: Boolean) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(SetupServer.get(context)) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(server) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "From: " + if (server.isEmpty()) "Hugging Face (the default)" else server,
            fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { draft = server; editing = !editing }, enabled = enabled) { Text(if (editing) "Close" else "Change") }
    }
    if (editing) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text("Another server (empty for the default)") },
            placeholder = { Text("https://example.org/androidlm") },
            isError = !SetupServer.valid(draft),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint("Each file is fetched as <server>/<file name> and checked against the same SHA-256, so a mirror cannot change what the app reads.")
        Row {
            TextButton(onClick = { SetupServer.set(context, draft); server = draft.trim(); editing = false }, enabled = SetupServer.valid(draft)) { Text("Save") }
            TextButton(onClick = { SetupServer.set(context, ""); server = ""; draft = ""; editing = false }) { Text("Use the default") }
        }
    }
}

/** A download that waits for a second tap: on mobile data or with no connection. */
private data class NetAsk(val files: List<SetupFile>, val net: SetupServer.Network, val replace: Boolean)

/** A size as the card writes it: in MB below 0.1 GB, where GB would round to "0.0 GB". */
private fun sizeLabel(bytes: Long): String =
    if (bytes < 100_000_000L) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else ModelManager.gbLabel(bytes)
