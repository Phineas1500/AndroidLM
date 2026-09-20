package io.bigmoeonedge.example

// Model acquisition UI: the "Add a model" card and everything it opens. Split out of
// MainActivity, which owned the chat screen and this at the same time and read as one file with
// two unrelated jobs. Nothing here touches the chat or the engine: it imports and deletes model
// files, then tells the caller to re-scan.
//
// AndroidLM: this build has no network access. Upstream's model catalog and URL downloader were
// removed; a model reaches the device by adb push (dev flavor) or through the system file picker.

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Add a model" card: import a gguf the user already has on the device (SAF picker), and delete
 * the ones the app owns. Imports land in the app models dir with no permission; [onModelReady]
 * triggers a re-scan so the new model shows up in the picker above.
 *
 * [models] is the current scan result (the selectable list: first shards only).
 */
@Composable
fun AddModelSection(
    models: List<File>,
    scanning: Boolean,
    loadedSig: String?,
    onModelReady: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A model whose delete is being confirmed: its filename, or null when no dialog is up.
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // Null until the first scan finishes: on a first run the card opens itself, on a device that
    // already has a model it stays out of the way.
    var open by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(scanning) { if (!scanning && open == null) open = models.isEmpty() }
    val isOpen = open == true
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importFrac by remember { mutableStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        importStatus = "Importing…"
        importFrac = -1f
        scope.launch {
            SafImport.importGguf(context, uri) { copied, total ->
                importFrac = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f
            }.onSuccess {
                importStatus = null; importFrac = -1f; onModelReady()
            }.onFailure {
                importStatus = null; importFrac = -1f; error = it.message ?: "import failed"
            }
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Collapsible: once a model is on the device this card is just in the way, but it has
            // to stay reachable to add a second one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add a model", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !isOpen }) { Text(if (isOpen) "Close" else "Open") }
            }

            if (isOpen) {
                Text(
                    "This app never uses the network. Any MoE gguf works — pick a file already on " +
                        "the device; it is copied into app storage.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = importStatus == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pick file") }

                if (models.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Models on device", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    models.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    ModelManager.gbLabel(f.length()),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { deleteTarget = f.name },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) { Text("Delete", maxLines = 1, softWrap = false) }
                        }
                    }
                }
            }

            importStatus?.let { st ->
                Text(st, fontSize = 12.sp)
                if (importFrac >= 0f) {
                    LinearProgressIndicator(progress = { importFrac }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }

    deleteTarget?.let { fname ->
        // A split gguf deletes as a unit: every shard of the set, not just the first one the list
        // shows. Orphaned multi-GB tails are the failure mode this forbids.
        val targetNames = remember(fname) { ModelManager.shardSetOf(context, fname) }
        val copies = remember(fname, models) { targetNames.flatMap { ModelManager.copiesOf(context, it) } }
        // The loaded session pins its gguf via mmap; deleting it out from under a live engine is the
        // failure mode to forbid. sessionSignature starts with the model's path, so match on that.
        val isLoaded = copies.any { loadedSig != null && loadedSig.startsWith(it.absolutePath + "|") }
        DeleteModelDialog(
            fileName = fname,
            copies = copies,
            isLoaded = isLoaded,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                val toDelete = copies.filter { ModelManager.isAppDeletable(it) }
                scope.launch {
                    withContext(Dispatchers.IO) { toDelete.forEach { runCatching { it.delete() } } }
                    deleteTarget = null
                    onModelReady() // rescan: the picker refreshes
                }
            },
        )
    }
}

/**
 * Confirm deleting every app-deletable copy of a model. Lists each copy with its size, flags copies
 * the app cannot remove (adb-pushed, shell-owned) and the loaded-model guard, and only enables the
 * delete when there is something to delete and the model is not in use.
 */
@Composable
private fun DeleteModelDialog(
    fileName: String,
    copies: List<File>,
    isLoaded: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deletable = copies.filter { ModelManager.isAppDeletable(it) }
    val blocked = copies.filterNot { ModelManager.isAppDeletable(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $fileName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isLoaded) {
                    Text(
                        "This model is loaded. Start a new chat (or switch models) before deleting it.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }
                deletable.forEach { f ->
                    Text("${f.absolutePath}  ·  ${ModelManager.gbLabel(f.length())}", fontSize = 12.sp)
                }
                blocked.forEach { f ->
                    Text(
                        "${f.absolutePath} — adb-pushed; remove with: adb shell rm ${f.absolutePath}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deletable.isEmpty() && !isLoaded) {
                    Text(
                        "Nothing here the app can delete.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isLoaded && deletable.isNotEmpty()) {
                Text("Delete")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
