package io.bigmoeonedge.example

// AndroidLM: the "Set up" card. It lists the files the app reads (SetupFiles), says which are on
// the phone, opens each missing one's download in the browser (the app itself has no network
// access), and imports the files the user picks (ImportService).

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
    val missing = st.filter { it.found == null }
    val extraMissing = extras.filter { it.found == null }
    val report = progress.running || progress.done.isNotEmpty() || progress.errors.isNotEmpty()
    if (missing.isEmpty() && extraMissing.isEmpty() && !report) return

    var open by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val isOpen = open ?: (missing.any { it.file.required } || report)
    var deleteOriginals by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
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
                    if (missing.isNotEmpty()) "Not on this phone yet: " + missing.joinToString(", ") { it.file.label }
                    else "Optional: a larger model, slower and more thorough (${ModelManager.gbLabel(extras.sumOf { it.file.bytes })})",
                )
                return@Column
            }
            Hint(
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
                                s.outdated -> "an older version is on this phone"
                                s.file.required -> "needed"
                                else -> "optional"
                            },
                        )
                    }
                    if (s.found == null) {
                        TextButton(onClick = {
                            error = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.file.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.exceptionOrNull()?.let { "No browser to download with: ${s.file.url}" }
                        }) { Text("Download") }
                    }
                }
            }
            st.forEach { FileRow(it) }
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
            if (missing.isNotEmpty()) {
                // downloaded files already take their space, so the import itself needs room for
                // one copy at a time when each download is deleted after it
                val need = missing.sumOf { it.file.bytes }
                val largest = missing.maxOf { it.file.bytes }
                Hint(
                    "The missing files are ${sizeLabel(need)}; the phone has ${ModelManager.gbLabel(free)} free." + when {
                        need <= free -> ""
                        free >= largest + 500_000_000L ->
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
                if (name == null) {
                    Text("Preparing…", fontSize = 12.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Text(
                        "Copying $name (${progress.index + 1} of ${progress.count}): " +
                            "${ModelManager.gbLabel(progress.copied)} of ${ModelManager.gbLabel(progress.total)}",
                        fontSize = 12.sp,
                    )
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) (progress.copied.toFloat() / progress.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { ImportService.cancel(context) }) { Text("Cancel") }
            }
            progress.done.forEach { Text("✓ $it", fontSize = 12.sp) }
            (progress.errors + listOfNotNull(error)).forEach {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

/** A size as the card writes it: in MB below 0.1 GB, where GB would round to "0.0 GB". */
private fun sizeLabel(bytes: Long): String =
    if (bytes < 100_000_000L) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else ModelManager.gbLabel(bytes)
