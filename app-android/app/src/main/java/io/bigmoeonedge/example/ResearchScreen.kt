package io.bigmoeonedge.example

import androidx.compose.foundation.clickable
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
                corpus != null -> "Plan, look up the offline corpus (${corpus.label()}), answer with sources"
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

/** One research run, streaming or finished. */
@Composable
fun ResearchView(r: ResearchUi, loading: Boolean, prefilling: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("You", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            SelectionContainer { Text(r.question, fontSize = 15.sp) }
        }

        PhaseLine(r, loading, prefilling)

        if (r.titles != null) {
            Labeled("Planned articles") {
                if (r.titles.isEmpty()) Hint("none (the plan named no article)")
                else Text(r.titles.joinToString("  ·  "), fontSize = 13.sp)
            }
        }
        if (r.route != null) {
            Labeled("Route") { Text(routeText(r), fontSize = 13.sp) }
        }

        if (r.answer.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (r.route?.route == Route.ANSWER_FIRST) "Assistant (from its own knowledge)" else "Assistant",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary,
                )
                SelectionContainer { MarkdownText(r.answer) }
            }
        }
        if (r.check != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Source check", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary)
                if (r.check.isNotEmpty()) SelectionContainer { MarkdownText(r.check) }
            }
        }

        if (r.sources != null) {
            Labeled(if (r.sources.isEmpty()) "Sources" else "Sources (${r.sources.size})") {
                if (r.sources.isEmpty()) {
                    Hint(
                        if (r.route?.route == Route.ANSWER_FIRST) "Nothing relevant in the corpus, so the answer was not checked."
                        else "Nothing relevant in the corpus: answered from the model's own knowledge."
                    )
                }
                // keyed by run and number, so an expanded passage never carries over to another run
                r.sources.forEach { s -> key(r.runId, s.number) { SourceRow(s) } }
                if (r.sourcesDropped > 0) Hint("${r.sourcesDropped} more passages were found but did not fit the context.")
            }
        }

        if (r.error != null) {
            Text(r.error, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
        }
        if (r.timings.isNotEmpty()) {
            Text(r.timings.joinToString("\n") { timingText(it) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhaseLine(r: ResearchUi, loading: Boolean, prefilling: Boolean) {
    val text = when (r.phase) {
        ResearchPhase.PLANNING -> if (loading) "Waiting for the model to load…" else "Planning which articles to look up…"
        ResearchPhase.SEARCHING -> "Searching the offline corpus…"
        ResearchPhase.DRAFTING -> "Drafting an answer from the model's own knowledge…"
        ResearchPhase.ANSWERING ->
            if (r.sources.isNullOrEmpty()) "Answering from the model's own knowledge…" else "Answering from the sources…"
        ResearchPhase.CHECKING -> "Checking the draft against the sources…"
        ResearchPhase.DONE -> "Done"
        ResearchPhase.CANCELLED -> "Stopped"
        ResearchPhase.FAILED -> "Failed"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            // every generation starts with a prefill, and with sources in the prompt it is a long one
            if (r.running && prefilling && !loading) "$text (reading the prompt)" else text,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = if (r.phase == ResearchPhase.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Labeled(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun timingText(t: PhaseTiming): String {
    val name = t.phase.name.lowercase(Locale.US).padEnd(9)
    val g = t.generation ?: return String.format(Locale.US, "%s %d ms", name, t.wallMs)
    return buildString {
        append(String.format(Locale.US, "%s %.1fs · %d tok", name, t.wallMs / 1000.0, g.tokens))
        if (g.tokensPerSecond > 0) append(String.format(Locale.US, " @ %.1f tok/s", g.tokensPerSecond))
        if (g.promptTokens >= 0) append(String.format(Locale.US, " · prompt %d tok", g.promptTokens))
    }
}
