package io.bigmoeonedge.example

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
 * AndroidLM research mode, chat pieces: the Research switch, and one turn of a chat (the question
 * as a bubble; the answer with its places, source check, sources and how it was answered). State comes from
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
 * The question of one turn, as the user's chat bubble on the right.
 */
@Composable
fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/**
 * The answer of one turn, streaming or finished, as a chat message: how the question was read (a
 * follow-up or a translation), the places found, the answer, its source check, and while it runs a
 * status line at the bottom, where the screen follows the newest text. Under a finished answer,
 * Copy and the details: how it was answered, the sources and the timings. Citations like [1] open
 * the passage. [key] identifies the turn in its chat, for what stays open across scrolling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantTurn(r: ResearchUi, key: String, loading: Boolean, prefill: Prefill?, telemetry: Telemetry, generating: Boolean) {
    var openSource by remember(key) { mutableStateOf<ResearchSource?>(null) }
    var details by rememberSaveable(key) { mutableStateOf(false) }
    val cite: (Int) -> Unit = { n -> r.sources?.firstOrNull { it.number == n }?.let { openSource = it } }
    val places = r.route?.route == Route.PLACES

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r.askedAs != null) Hint("Understood as: " + r.askedAs)
        if (r.translatedAs != null) Hint("Searched for in English as: " + r.translatedAs)

        if (places && !r.sources.isNullOrEmpty()) PlacesList(r, key)

        if (r.answer.isNotEmpty()) {
            SelectionContainer { MarkdownText(r.answer, fontSize = 16.sp, onCitation = cite) }
            // the answer is complete while its source check still reads: say so, and that a new
            // question may be asked now
            if (r.checking) answeredMs(r)?.let { ms ->
                Hint("Answered in " + duration(ms) + ". Checking it against the sources; a new question stops this.")
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
                    Text(
                        if (r.phase == ResearchPhase.CANCELLED) "Stopped before the end, for the next question"
                        else "Facts from the sources that the answer leaves out or states differently",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (r.check.isNotEmpty()) {
                        SelectionContainer { MarkdownText(r.check, onCitation = cite, fontSize = 14.sp) }
                    }
                }
            }
        }

        if (r.running) StatusLine(r, loading, prefill, telemetry, generating)
        if (r.phase == ResearchPhase.CANCELLED) Hint(if (r.answer.isEmpty()) "Stopped before an answer." else "Stopped.")
        if (r.error != null) {
            Text(r.error, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
        }

        // the button and what it opens, brought into view together (revealOnOpen)
        if (!r.running) {
            var opens by remember(key) { mutableIntStateOf(0) }
            Column(Modifier.bringIntoViewRequester(revealOnOpen(opens)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (r.answer.isNotEmpty()) CopyButton(r)
                    val n = r.sources?.size ?: 0
                    TextButton(onClick = { details = !details; if (details) opens++ },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(
                            (if (!places && n > 0) "Sources ($n) · how it was answered" else "How it was answered") +
                                if (details) " ▾" else " ▸",
                            fontSize = 13.sp,
                        )
                    }
                }
                if (details) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.route != null) Text(routeText(r), fontSize = 13.sp)
                        if (r.titles != null && !places) {
                            Text(
                                if (r.titles.isEmpty()) "No Wikipedia article was planned."
                                else "Articles looked up: " + r.titles.joinToString(" · "),
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.sources != null && !places) {
                            if (r.sources.isEmpty()) {
                                Hint(
                                    if (r.route?.route == Route.ANSWER_FIRST) "Nothing relevant in the offline Wikipedia, so the answer was not checked."
                                    else "Nothing relevant in the offline Wikipedia: answered from the model's own knowledge."
                                )
                            }
                            // keyed by turn and number, so an expanded passage never carries over to another turn
                            r.sources.forEach { s -> key(key, s.number) { SourceRow(s) } }
                            if (r.sourcesDropped > 0) Hint("${r.sourcesDropped} more passages were found but did not fit.")
                        }
                        if (r.timings.isNotEmpty()) {
                            Text(timingSummary(r), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
                    if (s.via == "places") {
                        SelectionContainer { Text(s.text, fontSize = 14.sp) }
                        MapButton(s)
                    } else {
                        if (s.section.isNotEmpty()) Hint(s.section)
                        SelectionContainer { Text(s.text, fontSize = 14.sp) }
                        Hint(if (s.via == "title") "From a planned article, offline Wikipedia" else "From the full-text search, offline Wikipedia")
                    }
                }
            },
        )
    }
}

/** Copies the answer (and its source check) as plain text. */
@Composable
private fun CopyButton(r: ResearchUi) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(r.answer) { mutableStateOf(false) }
    TextButton(
        onClick = {
            val text = if (r.check.isNullOrBlank()) r.answer else r.answer + "\n\nSource check:\n" + r.check
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) { Text(if (copied) "Copied" else "Copy", fontSize = 13.sp) }
}

/**
 * The places the offline map data has for a places question, numbered as the answer cites them.
 * Open while the recommendations are still to come (the list is on screen in a fraction of a
 * second), folded once they are there; a tap on the header changes that.
 */
@Composable
private fun PlacesList(r: ResearchUi, key: String) {
    val sources = r.sources ?: return
    var userOpen by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    val open = userOpen ?: r.answer.isEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { userOpen = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Places from the offline map (${sources.size}" +
                        (if (r.sourcesDropped > 0) " of ${sources.size + r.sourcesDropped}" else "") + ")",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open) "▾" else "▸", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (r.placesWhere != null) Hint(r.placesWhere.replaceFirstChar { it.uppercase() })
            if (open) {
                sources.forEach { s -> key(key, s.number) { PlaceRow(s) } }
                Hint(PLACES_CREDIT)
            }
        }
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
        ResearchPhase.TRANSLATING -> if (loading) "Loading the model (once per app start)…" else "Translating the question into English for the search…"
        ResearchPhase.REWRITING -> "Reading the follow-up with the previous answer…"
        ResearchPhase.PLANNING -> when {
            loading && r.route?.route == Route.PLACES -> "Loading the model to write recommendations (once per app start)…"
            loading -> "Loading the model (once per app start)…"
            else -> "Choosing Wikipedia articles to look up…"
        }
        ResearchPhase.SEARCHING -> when {
            r.route?.pack == true -> "Searching the Ethereum and cryptography library and Wikipedia…"
            r.route?.route != Route.PLACES -> "Searching the offline Wikipedia…"
            r.placesWhere == null -> "Finding your position (GPS)…"
            else -> "Looking up places…"
        }
        ResearchPhase.DRAFTING -> if (prefill != null) "Reading the question…" else "Writing an answer from the model's own knowledge…"
        ResearchPhase.ANSWERING -> when {
            r.route?.route == Route.PLACES && prefill != null -> "Reading the list of places…"
            r.route?.route == Route.PLACES -> "Writing recommendations from the list…"
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
            if (DevEnv.flag("ANDROIDLM_SPINNER")) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else TickingRing()
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

/**
 * The status line's "working" mark: a ring that fills by an eighth every half second. A spinning
 * (indeterminate) indicator is an endless animation, and it kept the whole screen redrawing at the
 * display's full rate (up to 120 frames a second on a Pixel 8 Pro) for all of an answer, whose text
 * changes four times a second (RunService.UI_FRAME_MS): about a quarter of a core and the GPU, next
 * to the engine, in a phone whose speed is limited by its heat. This draws two frames a second.
 */
@Composable
private fun TickingRing() {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            step = (step + 1) % 8
        }
    }
    CircularProgressIndicator(progress = { (step + 1) / 8f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
}

private fun routeText(r: ResearchUi): String {
    val d = r.route ?: return ""
    if (d.route == Route.PLACES) {
        return if (r.placesWhere != null) "From the offline map data and travel guide: ${r.placesWhere}."
        else "A question about places near you: looking them up in the offline map data."
    }
    val subject = r.titles?.firstOrNull()?.let { "\"$it\"" } ?: "the subject"
    val threshold = String.format(Locale.US, "%,d", r.routeThreshold)
    val views = d.views?.let { String.format(Locale.US, "%,d", it) }
    return when {
        d.pack && d.route == Route.RETRIEVAL_FIRST ->
            "Sources first: an Ethereum or cryptography question, answered from the offline library of Ethereum's " +
                "specifications and documentation and NIST's standards, with Wikipedia."
        d.pack ->
            "Answer first, then a source check against the offline library of Ethereum's specifications and " +
                "documentation and NIST's standards, with Wikipedia."
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
            ResearchPhase.TRANSLATING -> String.format(Locale.US, "translated %.0f s", secs)
            ResearchPhase.REWRITING -> String.format(Locale.US, "follow-up read %.0f s", secs)
            ResearchPhase.PLANNING -> String.format(Locale.US, "planned %.0f s", secs)
            ResearchPhase.SEARCHING -> when {
                r.route?.route == Route.PLACES -> String.format(Locale.US, "places found in %.1f s", secs)
                t.workMs != null && t.wallMs < 500 -> "searched during the draft"
                else -> String.format(Locale.US, "searched %.0f s", secs)
            }
            ResearchPhase.DRAFTING, ResearchPhase.ANSWERING, ResearchPhase.CHECKING -> {
                val what = when (t.phase) {
                    ResearchPhase.DRAFTING -> "answer"
                    ResearchPhase.ANSWERING -> if (r.route?.route == Route.PLACES) "recommendations" else "answer"
                    else -> "check"
                }
                if (g == null) String.format(Locale.US, "%s %.0f s", what, secs)
                else String.format(Locale.US, "%s %.0f s (%d tokens at %.1f/s)", what, secs, g.tokens, g.tokensPerSecond)
            }
            else -> null
        }
    }
    val answered = answeredMs(r)
    val head = if (answered != null && r.timings.any { it.phase == ResearchPhase.CHECKING }) {
        "Answered in " + duration(answered) + ", checked by " + duration((total * 1000).toLong())
    } else {
        "Done in " + duration((total * 1000).toLong())
    }
    return head + " · " + parts.joinToString(" · ")
}

/** How long an answer-first run took to its answer, before the source check; null on other routes. */
private fun answeredMs(r: ResearchUi): Long? {
    if (r.route?.route != Route.ANSWER_FIRST || r.timings.none { it.phase == ResearchPhase.DRAFTING }) return null
    return r.timings.takeWhile { it.phase != ResearchPhase.CHECKING }.sumOf { it.wallMs }
}

private fun duration(ms: Long): String {
    val s = ms / 1000.0
    val mins = (s / 60).toInt()
    return if (mins > 0) String.format(Locale.US, "%d min %02d s", mins, (s % 60).toInt()) else String.format(Locale.US, "%.0f s", s)
}

/**
 * What a tap opened under the last answer must come into view: the list does not move when an item
 * grows, so the new text stayed below the screen, with nothing to say it was there. Each time
 * [opens] goes up (a tap that opened the section), once the new content is laid out the list
 * scrolls just enough to show the element the returned requester is attached to: its bottom, or
 * its top when it is taller than the screen. A section that is already open when its item scrolls
 * back into view does not move the list ([opens] starts at 0 again).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun revealOnOpen(opens: Int): BringIntoViewRequester {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(opens) {
        if (opens > 0) {
            withFrameNanos { } // the frame that lays out the opened content
            requester.bringIntoView()
        }
    }
    return requester
}

/** A numbered source; tapping it shows the passage the model was given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceRow(s: ResearchSource) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var opens by remember { mutableIntStateOf(0) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(revealOnOpen(opens)),
    ) {
        Column(Modifier.clickable { expanded = !expanded; if (expanded) opens++ }.padding(horizontal = 10.dp, vertical = 6.dp)) {
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

/** Credit for the places list (ODbL and the Overture and Wikivoyage licences ask for it). */
private const val PLACES_CREDIT =
    "Map data © OpenStreetMap contributors (ODbL) and the Overture Maps Foundation; travel guide: Wikivoyage (CC BY-SA). " +
        "No ratings: check a place is open before going."

/** A place of the list: its name and what it is; tapping it shows the details and a map link. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceRow(s: ResearchSource) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var opens by remember { mutableIntStateOf(0) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(revealOnOpen(opens)),
    ) {
        Column(Modifier.clickable { expanded = !expanded; if (expanded) opens++ }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("[${s.number}]", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s.section, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                SelectionContainer {
                    Text(s.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                MapButton(s)
            }
        }
    }
}

/**
 * Opens the place in whatever map app is installed (a geo: link; an offline map app such as
 * Organic Maps or OsmAnd shows it without a network). The app itself never goes online.
 */
@Composable
private fun MapButton(s: ResearchSource) {
    val lat = s.lat ?: return
    val lon = s.lon ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    var missing by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            val label = android.net.Uri.encode(s.title)
            val uri = android.net.Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", lat, lon, lat, lon, label))
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
            } catch (e: android.content.ActivityNotFoundException) {
                missing = true
            }
        },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) { Text("Open in a map app", fontSize = 13.sp) }
    if (missing) Hint(String.format(Locale.US, "No map app is installed. The place is at %.5f, %.5f.", lat, lon))
}
