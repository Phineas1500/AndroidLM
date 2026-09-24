package org.androidlm.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

    companion object {
        fun of(wiki: Corpus, voyage: Corpus? = null): CorpusProvider = object : CorpusProvider {
            override fun wiki() = wiki
            override fun voyage() = voyage
        }
    }
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
)

enum class ResearchPhase { PLANNING, SEARCHING, DRAFTING, ANSWERING, CHECKING, DONE, CANCELLED, FAILED }

/** One passage that reached the model; [number] is its citation number in the context. */
data class ResearchSource(
    val number: Int,
    val title: String,
    val section: String,
    val text: String,
    val via: String,
)

/** Wall time of one phase; [generation] is set for the phases that ran the model. */
data class PhaseTiming(val phase: ResearchPhase, val wallMs: Long, val generation: Generation? = null)

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

    /** The router's decision; `decision.views` is null when the first planned title did not resolve. */
    data class Routed(val decision: RouteDecision, val threshold: Long) : ResearchEvent()

    data class SourcesFound(val sources: List<ResearchSource>, val dropped: Int) : ResearchEvent()

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
) {
    /**
     * Answers [question], reporting progress to [listener]. Throws CancellationException when
     * cancelled (after `PhaseChanged(CANCELLED)`), and rethrows any failure (after `Failed` and
     * `PhaseChanged(FAILED)`).
     */
    suspend fun run(question: String, listener: ResearchListener): ResearchResult {
        val run = Run(question, listener)
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

    private inner class Run(val question: String, val listener: ResearchListener) {
        var phase = ResearchPhase.PLANNING
        val timings = ArrayList<PhaseTiming>()

        suspend fun execute(): ResearchResult {
            // 1. plan
            val plan = generating(ResearchPhase.PLANNING, Prompts.PLAN_SYSTEM, question, config.planTokens) {}
            val titles = Planner.parsePlanOutput(plan.text)
            listener.onEvent(ResearchEvent.Planned(titles))

            // 2. route (a lookup of a few milliseconds; it has no phase of its own)
            val decision = withContext(corpusDispatcher) {
                // the guide is only opened for routing when the travel route is on
                val voyage = if (config.travelRoute) corpora.voyage() else null
                Planner.route(corpora.wiki(), titles, config.routeViews, question, voyage, config.travelRoute)
            }
            listener.onEvent(ResearchEvent.Routed(decision, config.routeViews))

            // 3. answer first: the draft comes before the search
            var draft: String? = null
            if (decision.route == Route.ANSWER_FIRST) {
                draft = answering(ResearchPhase.DRAFTING, Prompts.CLOSED_SYSTEM, question)
            }

            val built = searching(titles)
            val sources = built.usedHits.mapIndexed { i, h -> ResearchSource(i + 1, h.title, h.section, h.text, h.via) }
            val dropped = built.dropped
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
                        continuing(ResearchPhase.CHECKING, Prompts.checkFollowupUser(context), config.checkTokens, onCheckToken)
                    } else {
                        generating(
                            ResearchPhase.CHECKING, Prompts.VERIFY_SYSTEM,
                            Prompts.verifyUser(question, draft, context), config.checkTokens, onCheckToken,
                        )
                    }
                    // a check that was nothing but deliberation cleans to "": show the draft alone
                    check = cleanCheck(res.text).ifEmpty { null }
                    listener.onEvent(ResearchEvent.CheckCompleted(check ?: ""))
                }
            } else if (context.isNotEmpty()) {
                answer = answering(ResearchPhase.ANSWERING, Prompts.ANSWER_SYSTEM, Prompts.answerUser(context, question))
            } else {
                answer = answering(ResearchPhase.ANSWERING, Prompts.CLOSED_SYSTEM, question)
            }

            val text = if (check != null) answer + SOURCE_CHECK_HEADING + check else answer
            val result = ResearchResult(question, titles, decision, sources, dropped, answer, check, text, timings.toList())
            listener.onEvent(ResearchEvent.Completed(result))
            enter(ResearchPhase.DONE)
            return result
        }

        private fun enter(next: ResearchPhase) {
            phase = next
            listener.onEvent(ResearchEvent.PhaseChanged(next))
        }

        private fun completed(start: Long, generation: Generation? = null) {
            val timing = PhaseTiming(phase, (System.nanoTime() - start) / 1_000_000, generation)
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

        private suspend fun searching(titles: List<String>): Searched {
            enter(ResearchPhase.SEARCHING)
            val start = System.nanoTime()
            val searched = withContext(corpusDispatcher) {
                val hits = corpora.wiki().retrieve(question, titles, config.k, corpora.voyage())
                val built = buildContext(hits, config.contextChars)
                Searched(built.context, built.usedHits, hits.size - built.usedHits.size)
            }
            completed(start)
            return searched
        }
    }

    private class Searched(val context: String, val usedHits: List<Hit>, val dropped: Int)

    companion object {
        /** Between the draft and its source check in the final text (rag.py, verbatim). */
        const val SOURCE_CHECK_HEADING = "\n\n**Source check**\n"
    }
}
