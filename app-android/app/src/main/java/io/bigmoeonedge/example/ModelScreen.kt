package io.bigmoeonedge.example

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.androidlm.research.android.CorpusFiles
import org.androidlm.research.android.CorpusLocator
import java.io.File

/**
 * AndroidLM: "Model and files", off the chat screen: the Set up card (the files the app reads,
 * their download and import), which model answers, the Research switch, and everything about the
 * engine for the curious and for reproducing a run (the model file, the flags the session runs
 * with, live speed and memory, the per-run metrics, unloading the model).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    corpus: CorpusFiles?,
    models: List<File>,
    scanning: Boolean,
    modelIdx: Int,
    onSelectModel: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenMetrics: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ui by RunBus.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model and files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // the files the app reads, which are missing, and their download or import
            SetupCard(scanning = scanning, onImported = onRefresh)

            val modelNames = remember(models) { friendlyModelNames(models.map { it.name }) }
            when {
                scanning -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Looking for the model…", fontSize = 14.sp)
                }
                models.isEmpty() -> {
                    ElevatedCard {
                        Text(ModelManager.pushHint(), Modifier.padding(12.dp), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { requestSharedStorageAccess(context); onRefresh() }) { Text("Refresh") }
                }
                models.size == 1 -> Text(
                    "Model: " + modelNames[0] + when {
                        ui.loading -> " · loading…"
                        ui.ready || ui.generating -> " · loaded"
                        else -> ""
                    },
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LabeledDropdown(label = "Model", options = modelNames, selected = modelIdx, onSelect = onSelectModel)
            }
            // the optional larger model says what choosing it costs
            val chosen = models.getOrNull(modelIdx)
            var chosenArch by remember { mutableStateOf("") }
            LaunchedEffect(chosen) {
                chosenArch = chosen?.let { withContext(Dispatchers.IO) { ModelProfile.architecture(it.absolutePath) } } ?: ""
            }
            if (chosenArch == ModelProfile.FLASH_NEXT_ARCH) {
                Hint("The larger model: more thorough, but each answer takes about 3 to 4 times as long (first words after about 2 minutes).")
            }

            // With Research on, a message runs the research pipeline; off, it is a plain chat turn.
            val corpusHint = remember { CorpusLocator.hint(context) }
            ResearchToggle(
                corpus = corpus,
                scanning = scanning,
                checked = settings.researchMode,
                enabled = !ui.busy,
                sessionCtx = settings.sessionCtx,
                hint = corpusHint,
                onChange = { onSettingsChange(settings.copy(researchMode = it)) },
            )
            if (!settings.researchMode) {
                Hint("Without Research the model answers from memory alone, with no sources, and the chat is not saved.")
            }
            if (corpus == null && !scanning && models.isNotEmpty()) {
                TextButton(onClick = onRefresh, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text("Look for the corpus again", fontSize = 12.sp)
                }
            }

            HorizontalDivider()
            Text("Details", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            models.getOrNull(modelIdx.coerceIn(0, (models.size - 1).coerceAtLeast(0)))?.let {
                Text(it.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (corpus != null) Hint("Corpus: " + corpus.label())
            if (models.isNotEmpty()) {
                // bring a model into app storage without adb: a local file through the system picker
                AddModelSection(models = models, scanning = scanning, loadedSig = ui.sessionSig, onModelReady = onRefresh)
            }

            // A reminder of the active config (full controls in Settings), with the exact engine
            // flags one tap away, so a screenshot of a run is reproducible off-device.
            val summary = remember(settings) { configSummary(settings) }
            Text(summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var showConfig by rememberSaveable { mutableStateOf(false) }
            val flags = remember(settings, models, modelIdx) {
                models.getOrNull(modelIdx.coerceIn(0, (models.size - 1).coerceAtLeast(0)))
                    ?.let { configFlags(settings, it.absolutePath, settings.metricsCsv) }
                    .orEmpty()
            }
            if (flags.isNotEmpty()) {
                TextButton(onClick = { showConfig = !showConfig }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Text(if (showConfig) "Hide engine flags" else "Engine flags (${flags.size})", fontSize = 12.sp)
                }
                if (showConfig) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        flags.forEach { (flag, value) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(flag, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            TelemetryCard(ui, settings.threads, settings.overlap, settings.ioThreads)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenMetrics) { Text("Metrics") }
                // The model stays loaded between questions and unloads itself when idle; unloading
                // now is optional and only frees the memory sooner.
                if (ui.ready) {
                    TextButton(onClick = {
                        context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_SHUTDOWN))
                    }) { Text("Unload model now (optional)") }
                }
            }
            if (ui.ready) {
                Hint("The model stays loaded between questions and unloads by itself after 30 idle minutes. Unloading now frees about 8 GB; the next question reloads it in about 30 s.")
            }
        }
    }
}
