package io.bigmoeonedge.example

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.androidlm.research.PhaseTiming
import org.androidlm.research.ResearchPhase
import org.androidlm.research.ResearchSource
import org.androidlm.research.Route
import org.androidlm.research.android.CorpusFiles
import java.util.Locale

/*
 * AndroidLM research mode, main-screen pieces: the toggle by the prompt box and the view of one
 * run (phase, planned articles, route, answer, source check, sources). State comes from
 * UiState.research, which RunService fills from the pipeline's events.
 */

/**
 * The "Research" switch. Without a corpus on the device the mode is unavailable: the switch is
 * off and disabled, and [hint] says where the files go.
 */
@Composable
fun ResearchToggle(
    corpus: CorpusFiles?,
    scanning: Boolean,
    checked: Boolean,
    enabled: Boolean,
    sessionCtx: Int,
    hint: String,
    onChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            label = "Research",
            description = when {
                corpus != null -> "Answer with sources from the offline Wikipedia"
                scanning -> "Looking for a corpus…"
                else -> "Unavailable: no corpus on this device"
            },
            checked = checked && corpus != null,
            enabled = enabled && corpus != null,
            onChange = onChange,
        )
        if (corpus == null && !scanning) {
            Text(hint, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (corpus != null && checked && sessionCtx < RESEARCH_MIN_CTX) {
            // question + about 1000 tokens of sources + a 600-token draft + the check itself
            Hint("Context is $sessionCtx tokens; research prompts need about $RESEARCH_MIN_CTX (Settings).")
        }
    }
}

private const val RESEARCH_MIN_CTX = 4096

/**
 * One research run, streaming or finished: the question as a title, how it was answered (planned
 * articles and route), the sources, the answer and its source check, and while it runs a status
 * line at the bottom, where the screen follows the newest text. Citations like [1] open the passage.
 */
@Composable
fun ResearchView(r: ResearchUi, loading: Boolean, prefill: Prefill?, telemetry: Telemetry, generating: Boolean) {
    var openSource by remember(r.runId) { mutableStateOf<ResearchSource?>(null) }
    val cite: (Int) -> Unit = { n -> r.sources?.firstOrNull { it.number == n }?.let { openSource = it } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        SelectionContainer { Text(r.question, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }

        if (r.titles != null || r.route != null) {
            Labeled("How it was answered") {
                if (r.route != null) Text(routeText(r), fontSize = 13.sp)
                if (r.titles != null) {
                    Text(
                        if (r.titles.isEmpty()) "No Wikipedia article was planned."
                        else "Articles looked up: " + r.titles.joinToString(" · "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (r.sources != null) {
            Labeled(if (r.sources.isEmpty()) "Sources" else "Sources (${r.sources.size}) · tap one to read it") {
                if (r.sources.isEmpty()) {
                    Hint(
                        if (r.route?.route == Route.ANSWER_FIRST) "Nothing relevant in the offline Wikipedia, so the answer was not checked."
                        else "Nothing relevant in the offline Wikipedia: answered from the model's own knowledge."
                    )
                }
                // keyed by run and number, so an expanded passage never carries over to another run
                r.sources.forEach { s -> key(r.runId, s.number) { SourceRow(s) } }
                if (r.sourcesDropped > 0) Hint("${r.sourcesDropped} more passages were found but did not fit.")
            }
        }

        if (r.answer.isNotEmpty()) {
            Labeled(
                if (r.route?.route == Route.ANSWER_FIRST) "Answer, from the model's own knowledge"
                else "Answer, from the sources"
            ) {
                SelectionContainer { MarkdownText(r.answer, onCitation = cite) }
            }
        }
        if (r.check != null && (r.check.isNotEmpty() || r.running)) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Source check", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("The answer above, checked against the sources", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (r.check.isNotEmpty()) {
                        SelectionContainer { MarkdownText(r.check, onCitation = cite, fontSize = 14.sp) }
                    }
                }
            }
        }

        if (r.running) StatusLine(r, loading, prefill, telemetry, generating)
        if (r.phase == ResearchPhase.CANCELLED) Hint("Stopped.")
        if (r.error != null) {
            Text(r.error, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
        }
        if (!r.running && r.timings.isNotEmpty()) {
            Text(timingSummary(r), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    openSource?.let { s ->
        AlertDialog(
            onDismissRequest = { openSource = null },
            confirmButton = { TextButton(onClick = { openSource = null }) { Text("Close") } },
            title = { Text("[${s.number}] ${s.title}", fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.section.isNotEmpty()) Hint(s.section)
                    SelectionContainer { Text(s.text, fontSize = 14.sp) }
                    Hint(if (s.via == "title") "From a planned article, offline Wikipedia" else "From the full-text search, offline Wikipedia")
                }
            },
        )
    }
}

/**
 * What the run is doing now, at the bottom of the view. While the engine reads a prompt it shows
 * a progress bar with the token count and time left (reported per chunk, interpolated between);
 * while it writes, the speed.
 */
@Composable
private fun StatusLine(r: ResearchUi, loading: Boolean, prefill: Prefill?, telemetry: Telemetry, generating: Boolean) {
    val nSources = r.sources?.size ?: 0
    val text = when (r.phase) {
        ResearchPhase.PLANNING -> if (loading) "Loading the model (once per app start)…" else "Choosing Wikipedia articles to look up…"
        ResearchPhase.SEARCHING -> "Searching the offline Wikipedia…"
        ResearchPhase.DRAFTING -> if (prefill != null) "Reading the question…" else "Writing an answer from the model's own knowledge…"
        ResearchPhase.ANSWERING -> when {
            prefill != null && nSources > 0 -> "Reading $nSources sources…"
            prefill != null -> "Reading the question…"
            nSources > 0 -> "Writing the answer from the sources…"
            else -> "Writing the answer from the model's own knowledge…"
        }
        ResearchPhase.CHECKING -> if (prefill != null) "Reading $nSources sources to check the answer…" else "Writing the source check…"
        else -> ""
    }
    // Ticks twice a second while a prompt is read, so the bar moves between the engine's reports.
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    if (prefill != null) {
        LaunchedEffect(prefill) {
            while (true) {
                now = android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(500)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        if (prefill != null && prefill.total >= 200) {
            val read = prefill.estimate(now)
            LinearProgressIndicator(
                progress = { (read / prefill.total).toFloat().coerceIn(0f, 0.99f) },
                modifier = Modifier.fillMaxWidth(),
            )
            val left = prefill.secondsLeft(now)
            Hint(String.format(Locale.US, "%,d of %,d tokens · %s", read.toInt(), prefill.total,
                if (left >= 2) "about ${left.toInt()} s left" else "almost done"))
        } else if (generating && prefill == null && telemetry.step > 0 && telemetry.wallMs > 0) {
            Hint(String.format(Locale.US, "%.1f tokens/s", 1000.0 / telemetry.wallMs))
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

private fun routeText(r: ResearchUi): String {
    val d = r.route ?: return ""
    val subject = r.titles?.firstOrNull()?.let { "\"$it\"" } ?: "the subject"
    val threshold = String.format(Locale.US, "%,d", r.routeThreshold)
    val views = d.views?.let { String.format(Locale.US, "%,d", it) }
    return when {
        d.travel ->
            "Sources first: a travel question, and $subject has a travel guide."
        d.route == Route.RETRIEVAL_FIRST ->
            "Sources first: $subject is little read ($views monthly views, under $threshold), where the model's memory is unreliable."
        views != null ->
            "Answer first, then a source check: $subject is widely read ($views monthly views)."
        r.titles.isNullOrEmpty() -> "Answer first, then a source check: no article was planned."
        else -> "Answer first, then a source check: $subject is not in the corpus."
    }
}

/** One line for a finished run: time per phase and the writing speed, e.g. "Answered in 2 min 40 s · …". */
private fun timingSummary(r: ResearchUi): String {
    val total = r.timings.sumOf { it.wallMs } / 1000.0
    val parts = r.timings.mapNotNull { t ->
        val secs = t.wallMs / 1000.0
        val g = t.generation
        when (t.phase) {
            ResearchPhase.PLANNING -> String.format(Locale.US, "planned %.0f s", secs)
            ResearchPhase.SEARCHING ->
                if (t.workMs != null && t.wallMs < 500) "searched during the draft"
                else String.format(Locale.US, "searched %.0f s", secs)
            ResearchPhase.DRAFTING, ResearchPhase.ANSWERING, ResearchPhase.CHECKING -> {
                val what = when (t.phase) {
                    ResearchPhase.DRAFTING -> "answer"
                    ResearchPhase.ANSWERING -> "answer"
                    else -> "check"
                }
                if (g == null) String.format(Locale.US, "%s %.0f s", what, secs)
                else String.format(Locale.US, "%s %.0f s (%d tokens at %.1f/s)", what, secs, g.tokens, g.tokensPerSecond)
            }
            else -> null
        }
    }
    val mins = (total / 60).toInt()
    val head = if (mins > 0) String.format(Locale.US, "Done in %d min %02d s", mins, (total % 60).toInt())
    else String.format(Locale.US, "Done in %.0f s", total)
    return head + " · " + parts.joinToString(" · ")
}

/** A numbered source; tapping it shows the passage the model was given. */
@Composable
private fun SourceRow(s: ResearchSource) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("[${s.number}]", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    s.title + if (s.section.isNotEmpty()) " — ${s.section}" else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                SelectionContainer {
                    Text(s.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Hint(if (s.via == "title") "from a planned article" else "from the full-text search")
            }
        }
    }
}
