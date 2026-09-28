package org.androidlm.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one generation produced, with the basic figures the engine reports at the end of it. */
data class Generation(
    val text: String,
    /** Tokens generated. */
    val tokens: Int = 0,
    /** Decode rate, tokens per second. */
    val tokensPerSecond: Double = 0.0,
    /** Tokens in the prompt, or -1 when the engine did not say. */
    val promptTokens: Int = -1,
    /** Wall time of the whole call in seconds (prefill included). */
    val wallSeconds: Double = 0.0,
)

/**
 * The model, as the pipeline sees it: one independent prompt in, text out. Implementations run
 * every request on a fresh KV with reasoning off (rag.py's BmoeSession: `clear_kv` true, `think`
 * false) and sample as the engine does.
 *
 * [onToken] receives the text as it streams, in order, never concurrently, and never after
 * `generate` has returned; the returned [Generation.text] is authoritative (the engine may
 * revise what it streamed). Cancelling the calling coroutine must stop the generation, and
 * `generate` then throws CancellationException.
 */
interface Engine {
    /**
     * One generation. [continueChat] = false starts a new conversation (the engine drops its KV);
     * true sends [prompt] as the next user turn of the current conversation, so the engine keeps
     * the earlier turns in its KV cache and reads only the new text.
     */
    suspend fun generate(prompt: String, nPredict: Int, onToken: (String) -> Unit, continueChat: Boolean = false): Generation
}

/**
 * The corpora of a research session. Both functions are called on the pipeline's corpus thread
 * only, so an implementation can open the databases on first use and keep them (a Corpus is
 * bound to one connection and one thread).
 */
interface CorpusProvider {
    fun wiki(): Corpus

    /** The optional Wikivoyage corpus. */
    fun voyage(): Corpus?

    /** The optional places database (places.db): where to eat, drink and stay. */
    fun places(): Places? = null

    /** The optional Ethereum and cryptography pack ([Pack], ethereum.db). */
    fun pack(): Corpus? = null

    /** [SqlDatabase.interrupt] on every open database; callable from any thread. */
    fun interrupt() {}

    companion object {
        fun of(wiki: Corpus, voyage: Corpus? = null, places: Places? = null, pack: Corpus? = null): CorpusProvider =
            object : CorpusProvider {
                override fun wiki() = wiki
                override fun voyage() = voyage
                override fun places() = places
                override fun pack() = pack
            }
    }
}

/**
 * The phone's position, for "near me" questions: (latitude, longitude), or null when it is not
 * available (no permission, location off, no fix in time). May take seconds; must be cancellable.
 */
fun interface Locator {
    suspend fun here(): Pair<Double, Double>?
}

/** The knobs of rag.py `answer()`; the defaults are its command-line defaults. */
data class ResearchConfig(
    val k: Int = 6,
    val contextChars: Int = 4000,
    val routeViews: Long = Planner.DEFAULT_ROUTE_VIEWS,
    val planTokens: Int = Planner.PLAN_MAX_TOKENS,
    val answerTokens: Int = 600,
    val checkTokens: Int = 260,
    /**
     * rag.py `--travel-route` (off by default, as there): a travel question about a place that has
     * a Wikivoyage guide goes retrieval-first however widely read the subject is.
     */
    val travelRoute: Boolean = false,
    /**
     * rag.py `--check-continue`: ask for the source check as a follow-up turn of the draft's own
     * conversation (Prompts.CHECK_FOLLOWUP) instead of a fresh prompt that repeats the draft, so the
     * engine reads only the sources. Off by default, as in rag.py; the app turns it on.
     */
    val checkContinue: Boolean = false,
    /**
     * rag.py `check_context`: the source check of a draft reads at most this many characters of
     * the passages that share most with the draft and the question (0: the answer's own context,
     * [contextChars]); with [checkExcerpts], each passage cut to its sentences that do.
     */
    val checkChars: Int = 0,
    val checkExcerpts: Boolean = false,
    /** The places answer: a handful of one-line recommendations from the list. */
    val placesTokens: Int = 360,
    /**
     * rag.py `--worked`: a question that needs a calculation (Worked.needs) gets a few lines of
     * working before its answer. Off by default, as in rag.py; the app turns it on.
     */
    val worked: Boolean = false,
    /**
     * rag.py `--pack-route auto`: a question [Pack.routes] picks, when there is a pack
     * ([CorpusProvider.pack]), has the pack's passages ahead of Wikipedia's. With [packSources]
     * (`--pack-mode sources`) it is answered with them in context; otherwise (`check`) it is
     * answered first and the source check reads them. Off by default, as in rag.py.
     */
    val pack: Boolean = false,
    val packSources: Boolean = true,
    val packPassages: Int = Pack.PASSAGES,
)

enum class ResearchPhase { TRANSLATING, REWRITING, PLANNING, SEARCHING, DRAFTING, ANSWERING, CHECKING, DONE, CANCELLED, FAILED }

/** A finished question and its answer, for the follow-up question that comes after it. */
data class Exchange(val question: String, val answer: String)

/**
 * Follow-up questions ("what about Porto?", "how old was he?", "which of those is open late?"):
 * whether a question needs the previous exchange to be understood, and the rewrite's result.
 */
object FollowUp {
    private val OPENERS = Regex("^(and|also|what about|how about|and what|what else|tell me more|more about|" +
        "which (one|ones|of)|how so|same for|compared (to|with))\\b")
    private val SHORT = Regex("^(in|near|for|at|why|how|really)\\b")
    private val PRONOUNS = Regex("\\b(it|its|they|them|their|those|these|that|this|there|he|him|his|she|her|hers|one|ones)\\b")
    private val PERSONAL = Regex("^(he|him|his|she|her|hers)$")
    private val SPACE = Regex("\\s+")
    const val PREVIOUS_ANSWER_CHARS = 700
    const val MAX_TOKENS = 80

    /**
     * Opens like a follow-up ("what about...", "which of..."), or is a few words ("in Porto?",
     * "why?"), or is short, names nothing (no capitalised word after the first) and points back
     * with a pronoun ("how old was he?"), or has "he", "she"... before any name ("when did he win
     * the Nobel prize?"). "Is it safe to drink the water in Mexico City?" and "How old was Obama
     * when he became president?" name what they are about, so they stand alone.
     */
    fun looksLike(question: String): Boolean {
        val t = question.trim()
        val q = t.lowercase(java.util.Locale.ROOT).trimEnd('?', '.', '!')
        val words = q.split(SPACE).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 16) return false
        if (OPENERS.containsMatchIn(q)) return true
        if (words.size <= 4 && SHORT.containsMatchIn(q)) return true
        val raw = t.split(SPACE).filter { it.isNotEmpty() }
        val names = raw.drop(1).any { w -> w.firstOrNull()?.isUpperCase() == true }
        if (words.size <= 12 && !names && PRONOUNS.containsMatchIn(q)) return true
        // a person pointed back to before anyone is named
        for ((i, w) in raw.withIndex()) {
            if (i > 0 && w.firstOrNull()?.isUpperCase() == true) return false
            if (PERSONAL.matches(w.lowercase(java.util.Locale.ROOT).trim('?', '.', '!', ',', '\'', '"'))) return true
        }
        return false
    }

    /** The rewrite's question: its first non-empty line without quotes; null when it is not usable. */
    fun parse(text: String): String? {
        val line = text.lines().map { it.trim().trim('"', '\u201c', '\u201d').trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        return line.takeIf { it.length in 3..300 }
    }
}

/**
 * A question asked in another language: the places database, the plan and the search all work in
 * English, so it is first translated (a short generation), and the answer is asked for in the
 * language of the question.
 */
object Translation {
    const val MAX_TOKENS = 80
    private val U = if (System.getProperty("java.vm.name") == "Dalvik") "" else "(?U)"
    private val ENGLISH = Regex(U + "\\b(the|in|of|for|to|and|is|are|what|where|which|who|how|why|when|best|near|me|my|" +
        "can|should|tell|about|with|restaurants?)\\b")
    // frequent words of the languages of Western Europe that are rare in English questions
    private val OTHER = Regex(U + "\\b(el|los|las|en|del|y|que|qué|donde|dónde|cuál|cuáles|mejor|mejores|son|una|" +
        "les|des|du|et|est|où|quel|quels|quelle|quelles|meilleur|meilleurs|sont|der|die|das|und|ist|sind|wo|welche|" +
        "beste|besten|il|di|che|dove|migliori|sono|em|os|não|onde|melhor|melhores|são|quais)\\b")
    // "near me" in the scripts the translation is for (the translated question then says so)
    private val HERE = Regex("附近|周边|周围|身边|近く|近所|근처|주변|рядом|поблизости|cerca de mí|cerca de aquí|près de moi|" +
        "in der nähe|in meiner nähe|vicino a me|perto de mim")

    /**
     * Most of the question's letters are in another script than the Latin one (Chinese, Japanese,
     * Korean, Russian, Arabic...), or it is in Latin letters with more of the frequent words of
     * Spanish, French, German, Italian or Portuguese than of English (at least two).
     */
    fun needed(question: String): Boolean {
        var latin = 0
        var other = 0
        question.codePoints().forEach { c ->
            if (Character.isLetter(c)) {
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN) latin++ else other++
            }
        }
        if (other > latin) return true
        val low = question.lowercase(java.util.Locale.ROOT)
        val foreign = OTHER.findAll(low).count()
        return foreign >= 2 && foreign > ENGLISH.findAll(low).count()
    }

    /** The question (in any language) asks about the phone's surroundings. */
    fun mentionsHere(question: String): Boolean = HERE.containsMatchIn(question.lowercase(java.util.Locale.ROOT))

    /** The translation: its first non-empty line without quotes; null when it is not usable. */
    fun parse(text: String): String? = FollowUp.parse(text)
}

/**
 * One passage that reached the model; [number] is its citation number in the context. On the
 * places route a source is a place: [title] its name, [section] what it is, [text] its details,
 * [via] "places", and [lat]/[lon] where it is.
 */
data class ResearchSource(
    val number: Int,
    val title: String,
    val section: String,
    val text: String,
    val via: String,
    val lat: Double? = null,
    val lon: Double? = null,
)

/**
 * Wall time of one phase; [generation] is set for the phases that ran the model. For SEARCHING with
 * a [BackgroundSearch], [wallMs] is how long the run waited for the search and [workMs] how long
 * the search itself took (most of it overlapped with planning and drafting). [parts] breaks a
 * search with a background half down, in milliseconds: `stems` and `bm25` (how long the question's
 * stems and whole-index BM25 took; -1 when the BM25 did not finish), `bm25_at` (when it finished,
 * from the start of the run), and on the retrieval-first route `bm25_needed` (0 when the planned
 * articles' passages filled the sources, so the BM25 was stopped instead of awaited), `wait` (for
 * the background half, once the run needed it) and `titles` (the planned articles' passages and
 * the packing); on the answer-first route `titles` is the rest of the search.
 */
data class PhaseTiming(
    val phase: ResearchPhase,
    val wallMs: Long,
    val generation: Generation? = null,
    val workMs: Long? = null,
    val parts: List<Pair<String, Long>> = emptyList(),
)

data class ResearchResult(
    val question: String,
    val titles: List<String>,
    val route: RouteDecision,
    val sources: List<ResearchSource>,
    /** Retrieved passages that did not fit the context budget (rag.py `sources_dropped`). */
    val sourcesDropped: Int,
    /** The answer proper: the draft on the answer-first route. */
    val answer: String,
    /** The source check, when one ran, cleaned of visible deliberation ([cleanCheck]). */
    val check: String?,
    /** rag.py `rec["answer"]`: the answer, followed by the source-check section when there is one. */
    val text: String,
    val timings: List<PhaseTiming>,
)

sealed class ResearchEvent {
    /** A phase begins (DONE, CANCELLED and FAILED are terminal: nothing follows them). */
    data class PhaseChanged(val phase: ResearchPhase) : ResearchEvent()

    data class Planned(val titles: List<String>) : ResearchEvent()

    /** A follow-up question was rewritten to stand on its own; the run answers [question]. */
    data class Rewritten(val question: String) : ResearchEvent()

    /** A question in another language, translated into English for the search. */
    data class Translated(val question: String) : ResearchEvent()

    /** The router's decision; `decision.views` is null when the first planned title did not resolve. */
    data class Routed(val decision: RouteDecision, val threshold: Long) : ResearchEvent()

    data class SourcesFound(val sources: List<ResearchSource>, val dropped: Int) : ResearchEvent()

    /**
     * The places route found where to look: [where] says what was searched ("186 vegan places to
     * eat within 16 km of Buenos Aires, Argentina"); the places follow as [SourcesFound].
     */
    data class PlacesFound(val where: String, val total: Int, val here: Boolean) : ResearchEvent()

    /** Streamed text of the answer (the draft, on the answer-first route). */
    data class AnswerToken(val text: String) : ResearchEvent()

    /** The answer is complete; [text] replaces whatever was streamed. */
    data class AnswerCompleted(val text: String) : ResearchEvent()

    data class CheckToken(val text: String) : ResearchEvent()

    /**
     * The source check is complete; [text] (rag.py `clean_check`: stripped, and cut at the first
     * line that starts deliberating) replaces what was streamed. The [CheckToken]s are the raw
     * stream, so they may carry text that is not in [text].
     */
    data class CheckCompleted(val text: String) : ResearchEvent()

    /** A phase ended normally. */
    data class PhaseCompleted(val timing: PhaseTiming) : ResearchEvent()

    /** Precedes `PhaseChanged(DONE)`. */
    data class Completed(val result: ResearchResult) : ResearchEvent()

    /** Precedes `PhaseChanged(FAILED)`. */
    data class Failed(val phase: ResearchPhase, val message: String) : ResearchEvent()
}

/**
 * Receives the events of one run, in order and never concurrently, but on whichever thread
 * produced them (the engine's for tokens, the pipeline's otherwise). Must not block.
 */
fun interface ResearchListener {
    fun onEvent(event: ResearchEvent)
}

/**
 * A second corpus connection on its own thread, for searching while the engine is busy: the
 * question's stems and whole-index BM25 run while the plan is written, and on the answer-first
 * route the rest of the search runs while the draft is written. [corpora] must be a separate
 * [CorpusProvider] over the same files (a Corpus belongs to one connection and one thread), and
 * [dispatcher] must be backed by ONE thread, not the main corpus thread. [priority] is told
 * `true` while the search only overlaps the engine (so it can yield the fast cores to it) and
 * `false` when the run is waiting for it; it may be called from any thread.
 */
class BackgroundSearch(
    val corpora: CorpusProvider,
    val dispatcher: CoroutineDispatcher,
    val priority: (background: Boolean) -> Unit = {},
)

/**
 * rag.py `answer()` in `auto` mode over an [Engine] (its ENGINE branch of `chat()`: the engine
 * takes one user message, so every prompt is `system + "\n\n" + user`):
 *
 *  1. plan: PLAN_SYSTEM over the question, parsed into article titles;
 *  2. route: the first planned title's monthly views decide (below `routeViews`: retrieval first);
 *     with `travelRoute`, a travel question about a place with a travel guide is retrieval first;
 *  3. retrieval first: retrieve, pack the context, answer with ANSWER_SYSTEM over the sources
 *     (closed-book with CLOSED_SYSTEM when nothing was retrieved);
 *     answer first: draft with CLOSED_SYSTEM, then retrieve, pack, and check the draft against
 *     the sources with VERIFY_SYSTEM (no check when nothing was retrieved); the check is cleaned
 *     of visible deliberation before it is reported.
 *
 * Every Corpus call runs on [corpusDispatcher], which must be backed by ONE thread. Cancelling
 * the coroutine that called [run] stops the run in any phase: a generation through the engine's
 * own cancellation, a corpus call as soon as it returns. One run at a time per instance.
 */
class ResearchPipeline(
    private val engine: Engine,
    private val corpora: CorpusProvider,
    private val corpusDispatcher: CoroutineDispatcher,
    private val config: ResearchConfig = ResearchConfig(),
    private val background: BackgroundSearch? = null,
    private val locator: Locator? = null,
) {
    // Background searches are not children of a run: cancelling a run must not wait for a query
    // that is still reading the index. Their results are dropped; the thread moves on to the next.
    private val backgroundScope = background?.let { CoroutineScope(SupervisorJob() + it.dispatcher) }

    /**
     * Answers [question], reporting progress to [listener]. Throws CancellationException when
     * cancelled (after `PhaseChanged(CANCELLED)`), and rethrows any failure (after `Failed` and
     * `PhaseChanged(FAILED)`).
     */
    suspend fun run(question: String, listener: ResearchListener, previous: Exchange? = null): ResearchResult {
        val run = Run(question, listener, previous)
        try {
            return run.execute()
        } catch (e: CancellationException) {
            listener.onEvent(ResearchEvent.PhaseChanged(ResearchPhase.CANCELLED))
            throw e
        } catch (e: Throwable) {
            listener.onEvent(ResearchEvent.Failed(run.phase, e.message ?: e.toString()))
            listener.onEvent(ResearchEvent.PhaseChanged(ResearchPhase.FAILED))
            throw e
        }
    }

    private inner class Run(var question: String, val listener: ResearchListener, val previous: Exchange?) {
        var phase = ResearchPhase.PLANNING
        val timings = ArrayList<PhaseTiming>()
        private val runStart = System.nanoTime()

        private val jobs = ArrayList<Job>()

        suspend fun execute(): ResearchResult = try {
            executeInner()
        } finally {
            // a query still reading the index is stopped, not just abandoned
            if (jobs.any { it.isActive }) background?.corpora?.interrupt()
            jobs.forEach { it.cancel() }
        }

        /**
         * The half of a search that depends only on the question, on the background connection:
         * its stems, then its whole-index BM25. Two jobs, so that a search whose planned articles
         * already fill the sources can go on with the stems alone.
         */
        private inner class QuestionHalf(bg: BackgroundSearch) {
            @Volatile private var stemsMs = -1L
            @Volatile private var bmMs = -1L
            @Volatile private var bmAtMs = -1L
            val stems: Deferred<List<Stem>> = backgroundScope!!.async {
                val t = System.nanoTime()
                bg.corpora.wiki().stems(question).also { stemsMs = msSince(t) }
            }.also { jobs.add(it) }
            val bm25: Deferred<List<Hit>> = backgroundScope!!.async {
                val s = stems.await()
                val t = System.nanoTime()
                bg.corpora.wiki().bm25(s).also { bmMs = msSince(t); bmAtMs = msSince(runStart) }
            }.also { jobs.add(it) }

            fun parts() = listOf("stems" to stemsMs, "bm25" to bmMs, "bm25_at" to bmAtMs)
        }

        /**
         * Resolves each planned title on the corpus thread as soon as its line of the plan is
         * complete, while the model writes the rest; Corpus keeps the answer, so the route and the
         * search after the plan find it ready. A title that is not an article needs a full-text
         * title search, seconds on a phone. Only a head start: the finished plan is parsed again,
         * and a line that turns out not to be a title only costs a lookup. [onToken] is the plan's
         * token callback (one thread, in order).
         */
        private inner class TitlePrefetch(private val scope: CoroutineScope) {
            private val text = StringBuilder()
            private val requested = HashSet<String>()

            fun onToken(piece: String) {
                text.append(piece)
                if ('\n' !in piece) return
                for (title in Planner.parsePlanOutput(text.substring(0, text.lastIndexOf("\n")))) {
                    if (!requested.add(title)) continue
                    scope.launch(corpusDispatcher) { runCatching { corpora.wiki().resolveTitle(title) } }
                }
            }
        }

        private suspend fun executeInner(): ResearchResult {
            // a question in another language is searched for in English, and answered in its own
            if (Translation.needed(question)) translate()
            // a follow-up is first rewritten to stand on its own, from the previous exchange
            if (previous != null && FollowUp.looksLike(question)) rewrite(previous)

            // 0. a question about where to eat, drink or stay goes to the places database, when it
            //    names a place the database knows (or asks "near me"); everything else goes on below
            placesRun()?.let { return it }

            // with a background search: the question-only half of the search runs while the plan
            //    is being written, on its own connection and thread
            val half: QuestionHalf? = background?.let { bg ->
                bg.priority(true)
                QuestionHalf(bg)
            }

            // 1. plan; each title is resolved as soon as its line is written (TitlePrefetch)
            val prefetch = TitlePrefetch(CoroutineScope(currentCoroutineContext()))
            val plan = generating(ResearchPhase.PLANNING, Prompts.PLAN_SYSTEM, question, config.planTokens, prefetch::onToken)
            val titles = Planner.parsePlanOutput(plan.text)
            listener.onEvent(ResearchEvent.Planned(titles))

            // 2. route (a lookup of a few milliseconds; it has no phase of its own)
            var decision = withContext(corpusDispatcher) {
                // the guide is only opened for routing when the travel route is on
                val voyage = if (config.travelRoute) corpora.voyage() else null
                Planner.route(corpora.wiki(), titles, config.routeViews, question, voyage, config.travelRoute)
            }
            // a question the Ethereum and cryptography pack answers (rag.py --pack-route auto)
            val pack = if (config.pack) withContext(corpusDispatcher) { corpora.pack() } else null
            if (pack != null) {
                val wikiStems = half?.stems?.await() ?: withContext(corpusDispatcher) { corpora.wiki().stems(question) }
                val affinity = withContext(corpusDispatcher) {
                    Pack.affinity(pack.stems(question), wikiStems, corpora.wiki().nIndexed)
                }
                if (Pack.routes(affinity)) {
                    decision = RouteDecision(
                        if (config.packSources) Route.RETRIEVAL_FIRST else Route.ANSWER_FIRST, decision.views, pack = true,
                    )
                }
            }
            listener.onEvent(ResearchEvent.Routed(decision, config.routeViews))

            // a question that needs a calculation gets a few lines of working first (rag.py --worked)
            val worked = config.worked && Worked.needs(question)
            val closedSystem = if (worked) Prompts.WORKED_SYSTEM else Prompts.CLOSED_SYSTEM

            // 3. answer first: the draft comes before the search (with a background search, the search
            //    runs while the draft is written; its result is only reported once the draft is done)
            var draft: String? = null
            var early: Deferred<Pair<Searched, Long>>? = null
            if (decision.route == Route.ANSWER_FIRST) {
                if (background != null && half != null) {
                    early = backgroundScope!!.async {
                        val p = QuestionSearch(question, half.stems.await(), half.bm25.await())
                        val t0 = System.nanoTime()
                        val s = search(background.corpora, p, titles)
                        s to msSince(t0)
                    }.also { jobs.add(it) }
                }
                draft = answering(ResearchPhase.DRAFTING, closedSystem, answerQuestion)
            }

            val searched = searching(titles, half, early)
            // the pack's passages lead the sources of a question it answers
            val packHits = if (decision.pack && pack != null) {
                withContext(corpusDispatcher) { Pack.hits(pack, question, config.packPassages) }
            } else {
                null
            }
            val hits = if (packHits != null) packHits + searched.hits else searched.hits
            // the source check of a draft reads the passages that share most with it (rag.py
            // check_context), numbered as the sources are shown
            val built = if (draft != null && config.checkChars > 0) {
                CheckContext.build(hits, draft, question, config.checkChars, config.checkExcerpts)
            } else if (packHits != null) {
                buildContext(hits, config.contextChars)
            } else {
                BuiltContext(searched.context, searched.usedHits)
            }
            // (an excerpt is what the check read; the whole passage is what the source shows)
            val full = hits.associateBy { it.aid to it.start }
            val sources = built.usedHits.mapIndexed { i, h ->
                ResearchSource(i + 1, h.title, h.section, (full[h.aid to h.start] ?: h).text, h.via)
            }
            val dropped = hits.size - built.usedHits.size
            val asOf = if (packHits != null) withContext(corpusDispatcher) { Pack.asOf(pack!!) } else null
            listener.onEvent(ResearchEvent.SourcesFound(sources, dropped))
            val context = built.context

            val answer: String
            var check: String? = null
            if (draft != null) {
                answer = draft
                if (context.isNotEmpty()) {
                    val onCheckToken: (String) -> Unit = { listener.onEvent(ResearchEvent.CheckToken(it)) }
                    val res = if (config.checkContinue) {
                        // the draft was the engine's last generation, so its conversation is still loaded
                        val followup = asOf?.let { Pack.checkFollowup(it) } ?: Prompts.CHECK_FOLLOWUP
                        continuing(ResearchPhase.CHECKING, Prompts.checkFollowupUser(context, followup), config.checkTokens, onCheckToken)
                    } else {
                        generating(
                            ResearchPhase.CHECKING, Prompts.VERIFY_SYSTEM,
                            Prompts.verifyUser(answerQuestion, draft, context), config.checkTokens, onCheckToken,
                        )
                    }
                    // a check that was nothing but deliberation cleans to "": show the draft alone
                    check = cleanCheck(res.text).ifEmpty { null }
                    listener.onEvent(ResearchEvent.CheckCompleted(check ?: ""))
                }
            } else if (context.isNotEmpty()) {
                val system = when {
                    worked -> Prompts.WORKED_SOURCES_SYSTEM
                    asOf != null -> Pack.answerSystem(asOf)
                    else -> Prompts.ANSWER_SYSTEM
                }
                answer = answering(ResearchPhase.ANSWERING, system, Prompts.answerUser(context, answerQuestion))
            } else {
                answer = answering(ResearchPhase.ANSWERING, closedSystem, answerQuestion)
            }

            val text = if (check != null) answer + SOURCE_CHECK_HEADING + check else answer
            val result = ResearchResult(question, titles, decision, sources, dropped, answer, check, text, timings.toList())
            listener.onEvent(ResearchEvent.Completed(result))
            enter(ResearchPhase.DONE)
            return result
        }

        /**
         * The places route (places.py `lookup` + PLACES_SYSTEM), or null when the question is not
         * about places or names no place the database knows. A "near me" question stays here even
         * without a position or with nothing found: the Wikipedia pipeline could not place it.
         */
        private suspend fun placesRun(): ResearchResult? {
            val t0 = System.nanoTime()
            val (db, ask) = withContext(corpusDispatcher) {
                val db = corpora.places() ?: return@withContext null
                db.parse(question)?.let { db to it }
            } ?: return null
            val decision = RouteDecision(Route.PLACES, null)
            val lookup: PlacesLookup?
            if (ask.here) {
                listener.onEvent(ResearchEvent.Routed(decision, config.routeViews))
                enter(ResearchPhase.SEARCHING)
                val here = locator?.here()
                lookup = if (here == null) null else withContext(corpusDispatcher) { db.lookup(ask, here) }
                if (lookup == null) return placesNotice(t0, decision, NO_POSITION)
            } else {
                lookup = withContext(corpusDispatcher) { db.lookup(ask) } ?: return null
                listener.onEvent(ResearchEvent.Routed(decision, config.routeViews))
                enter(ResearchPhase.SEARCHING)
            }
            val where = PlacesText.whereText(lookup.total, lookup.radiusKm, lookup.label, ask, lookup.capped)
            listener.onEvent(ResearchEvent.PlacesFound(where, lookup.total, ask.here))
            if (lookup.places.isEmpty()) {
                // said plainly rather than left to the Wikipedia pipeline, which can only guess at
                // places (the old route invented restaurants)
                return placesNotice(t0, decision, String.format(java.util.Locale.US, NOTHING_FOUND,
                    PlacesText.whatText(ask), lookup.radiusKm, lookup.label))
            }
            val answer = withContext(corpusDispatcher) { PlacesAnswer.of(ask, lookup, corpora.voyage(), corpora.wiki()) }
            val sources = answer.sources
            listener.onEvent(ResearchEvent.SourcesFound(sources, lookup.total - sources.size))
            completed(t0)
            val lines = answer.modelLines
            val res = generating(
                ResearchPhase.ANSWERING, PlacesText.PLACES_SYSTEM, PlacesText.placesUser(answerQuestion, where, lines),
                config.placesTokens,
            ) { listener.onEvent(ResearchEvent.AnswerToken(it)) }
            listener.onEvent(ResearchEvent.AnswerCompleted(res.text))
            val result = ResearchResult(question, emptyList(), decision, sources, lookup.total - sources.size,
                res.text, null, res.text, timings.toList())
            listener.onEvent(ResearchEvent.Completed(result))
            enter(ResearchPhase.DONE)
            return result
        }

        /** The question as asked, when it was translated: the answer is written in its language. */
        private var asked: String? = null

        /** The question the answering prompts get: in English, with the question as asked after it when translated. */
        private val answerQuestion: String get() = asked?.let { Prompts.replyIn(question, it) } ?: question

        /** A question in another language translated into English; the run then searches for that. */
        private suspend fun translate() {
            val res = generating(ResearchPhase.TRANSLATING, Prompts.TRANSLATE_SYSTEM, question, Translation.MAX_TOKENS) {}
            val translated = Translation.parse(res.text) ?: return
            if (translated != question) {
                asked = question
                question = translated
                listener.onEvent(ResearchEvent.Translated(translated))
            }
        }

        /** The follow-up rewritten to stand on its own; the run then answers that question. */
        private suspend fun rewrite(previous: Exchange) {
            val answer = previous.answer.let {
                if (it.length <= FollowUp.PREVIOUS_ANSWER_CHARS) it else it.substring(0, FollowUp.PREVIOUS_ANSWER_CHARS) + "\u2026"
            }
            val res = generating(
                ResearchPhase.REWRITING, Prompts.FOLLOWUP_SYSTEM, Prompts.followupUser(previous.question, answer, question),
                FollowUp.MAX_TOKENS,
            ) {}
            val rewritten = FollowUp.parse(res.text) ?: return
            if (rewritten != question) {
                question = rewritten
                listener.onEvent(ResearchEvent.Rewritten(rewritten))
            }
        }

        /** A places answer without the model: why there is no list. */
        private fun placesNotice(t0: Long, decision: RouteDecision, text: String): ResearchResult {
            listener.onEvent(ResearchEvent.SourcesFound(emptyList(), 0))
            completed(t0)
            listener.onEvent(ResearchEvent.AnswerCompleted(text))
            val result = ResearchResult(question, emptyList(), decision, emptyList(), 0, text, null, text, timings.toList())
            listener.onEvent(ResearchEvent.Completed(result))
            enter(ResearchPhase.DONE)
            return result
        }

        private fun enter(next: ResearchPhase) {
            phase = next
            listener.onEvent(ResearchEvent.PhaseChanged(next))
        }

        private fun completed(
            start: Long, generation: Generation? = null, workMs: Long? = null, parts: List<Pair<String, Long>> = emptyList(),
        ) {
            val timing = PhaseTiming(phase, msSince(start), generation, workMs, parts)
            timings.add(timing)
            listener.onEvent(ResearchEvent.PhaseCompleted(timing))
        }

        /** rag.py `chat()` with ENGINE set: one prompt, the system text leading it. */
        private suspend fun generating(
            phase: ResearchPhase, system: String, user: String, nPredict: Int, onToken: (String) -> Unit,
        ): Generation {
            enter(phase)
            val start = System.nanoTime()
            val res = engine.generate(system + "\n\n" + user, nPredict, onToken)
            completed(start, res)
            return res
        }

        /** rag.py `chat_messages()` follow-up: the next user turn of the engine's current conversation. */
        private suspend fun continuing(
            phase: ResearchPhase, user: String, nPredict: Int, onToken: (String) -> Unit,
        ): Generation {
            enter(phase)
            val start = System.nanoTime()
            val res = engine.generate(user, nPredict, onToken, continueChat = true)
            completed(start, res)
            return res
        }

        /** A generation whose text is the user-visible answer. */
        private suspend fun answering(phase: ResearchPhase, system: String, user: String): String {
            val res = generating(phase, system, user, config.answerTokens) {
                listener.onEvent(ResearchEvent.AnswerToken(it))
            }
            listener.onEvent(ResearchEvent.AnswerCompleted(res.text))
            return res.text
        }

        private suspend fun searching(
            titles: List<String>, half: QuestionHalf?, early: Deferred<Pair<Searched, Long>>?,
        ): Searched {
            enter(ResearchPhase.SEARCHING)
            val start = System.nanoTime()
            background?.priority?.invoke(false) // the run is waiting for the search now
            if (early != null) {
                val (searched, workMs) = early.await()
                completed(start, workMs = workMs, parts = half!!.parts() + ("titles" to workMs))
                return searched
            }
            if (half == null) {
                val searched = withContext(corpusDispatcher) { search(corpora, null, titles) }
                completed(start)
                return searched
            }
            // Corpus.retrieve in two halves: the planned articles' passages on this connection, then,
            // only if they leave room in the sources, the BM25 hits from the background connection
            val w0 = System.nanoTime()
            val stems = half.stems.await()
            val stemsWaitMs = msSince(w0)
            val t0 = System.nanoTime()
            val t = withContext(corpusDispatcher) {
                corpora.wiki().titleHits(question, titles, config.k, corpora.voyage(), stems)
            }
            var bmWaitMs = 0L
            val hits = if (t.full) {
                // no BM25 hit could reach the sources: stop the query instead of waiting for it
                if (!half.bm25.isCompleted) background!!.corpora.interrupt()
                t.hits.take(t.limit)
            } else {
                val w1 = System.nanoTime()
                val bm = half.bm25.await()
                bmWaitMs = msSince(w1)
                withContext(corpusDispatcher) { corpora.wiki().withBm25(t, bm) }
            }
            val built = buildContext(hits, config.contextChars)
            completed(start, parts = half.parts() + listOf(
                "bm25_needed" to (if (t.full) 0L else 1L),
                "wait" to stemsWaitMs + bmWaitMs,
                "titles" to msSince(t0) - bmWaitMs,
            ))
            return Searched(built.context, built.usedHits, hits.size - built.usedHits.size, hits)
        }

        /** retrieve + pack, on [provider]'s thread (the caller's dispatcher decides which). */
        private suspend fun search(provider: CorpusProvider, p: QuestionSearch?, titles: List<String>): Searched {
            val hits = provider.wiki().retrieve(question, titles, config.k, provider.voyage(), p)
            val built = buildContext(hits, config.contextChars)
            return Searched(built.context, built.usedHits, hits.size - built.usedHits.size, hits)
        }
    }

    private class Searched(val context: String, val usedHits: List<Hit>, val dropped: Int, val hits: List<Hit>)

    companion object {
        /** Between the draft and its source check in the final text (rag.py, verbatim). */
        const val SOURCE_CHECK_HEADING = "\n\n**Source check**\n"

        /** The answer to a "near me" question when the phone's position is not available. */
        const val NO_POSITION =
            "Your position is not available: location access is off for AndroidLM, or the phone has no GPS " +
                "fix yet (indoors it can take a while). Ask again with the name of the city, for example " +
                "\"vegan restaurants in Lisbon\"."

        /** The answer when the map data has nothing of the kind; what, the radius and where are filled in. */
        const val NOTHING_FOUND =
            "The offline map data (OpenStreetMap and Overture Maps) has no %s within %.0f km of %s. " +
                "Try a wider question, such as places to eat rather than a diet or a cuisine."

        private fun msSince(t: Long) = (System.nanoTime() - t) / 1_000_000
    }
}
