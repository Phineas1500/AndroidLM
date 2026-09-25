package org.androidlm.research

import com.github.luben.zstd.Zstd
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ResearchPipeline against a scripted engine. The sample databases and golden.json come from the
 * fixture directory ([GoldenTest] explains where it is looked up); tests that need them are
 * skipped when it is absent. The expected contexts are the Python implementation's
 * (golden.json), so the prompts asserted here are the ones rag.py would send.
 */
class ResearchPipelineTest {

    // ── scripted engine ──

    private class Call(val prompt: String, val nPredict: Int, val continueChat: Boolean = false)

    /**
     * Returns [script]'s texts in order, streaming each in small pieces first. With [hangAt],
     * that call (0-based) streams its text, signals [hanging] and then waits to be cancelled.
     */
    private class FakeEngine(private val script: List<String>, private val hangAt: Int = -1) : Engine {
        val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())
        val hanging = CompletableDeferred<Unit>()
        @Volatile var cancelledCalls = 0

        override suspend fun generate(prompt: String, nPredict: Int, onToken: (String) -> Unit, continueChat: Boolean): Generation {
            val i = calls.size
            calls.add(Call(prompt, nPredict, continueChat))
            assertTrue("unexpected engine call #$i:\n$prompt", i < script.size)
            val text = script[i]
            text.chunked(7).forEach(onToken)
            if (i == hangAt) {
                hanging.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    cancelledCalls++
                    throw e
                }
            }
            return Generation(text, tokens = text.length / 4, tokensPerSecond = 5.0, promptTokens = prompt.length / 4, wallSeconds = 0.5)
        }
    }

    // ── corpus thread and databases ──

    /** Records the thread of every statement, to prove the Corpus never leaves its thread. */
    private class ThreadRecordingDb(private val inner: SqlDatabase, private val threads: MutableSet<Thread>) : SqlDatabase {
        override fun query(sql: String, vararg args: Any?): List<Array<Any?>> {
            threads.add(Thread.currentThread())
            return inner.query(sql, *args)
        }

        override fun exec(sql: String, vararg args: Any?) {
            threads.add(Thread.currentThread())
            inner.exec(sql, *args)
        }

        override fun interrupt() = inner.interrupt()
    }

    private lateinit var corpusThread: ExecutorCoroutineDispatcher
    private val dbThreads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())
    @Volatile private var corpusJavaThread: Thread? = null
    private val opened = ArrayList<JdbcSqlDatabase>()
    private val tempFiles = ArrayList<File>()

    @Before
    fun setUp() {
        corpusThread = Executors.newSingleThreadExecutor { r -> Thread(r, CORPUS_THREAD).apply { isDaemon = true }.also { corpusJavaThread = it } }
            .asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        runBlocking { withContext(corpusThread) { opened.forEach { it.close() } } }
        corpusThread.close()
        tempFiles.forEach { it.delete() }
    }

    private val fixtureDir = File(
        System.getProperty("ANDROIDLM_FIXTURES") ?: System.getenv("ANDROIDLM_FIXTURES")
            ?: (System.getProperty("user.home") + "/androidlm-tools/fixtures"),
    )

    private fun fixture(name: String): File {
        val f = File(fixtureDir, name)
        assumeTrue("fixture $f not found", f.isFile)
        return f
    }

    /** Opens the databases lazily, on whichever thread first asks (which must be the corpus thread). */
    private fun provider(wiki: File, voyage: File?): CorpusProvider = object : CorpusProvider {
        private val zstd = JniZstdDecompressor()
        private val wikiCorpus by lazy { open(wiki) }
        private val voyageCorpus by lazy { voyage?.let { open(it) } }

        private fun open(f: File): Corpus {
            val db = JdbcSqlDatabase(f).also { opened.add(it) }
            return Corpus(ThreadRecordingDb(db, dbThreads), zstd)
        }

        override fun wiki() = wikiCorpus
        override fun voyage() = voyageCorpus
    }

    private fun sampleProvider(withVoyage: Boolean = true) =
        provider(fixture("sample_wiki.db"), if (withVoyage) fixture("sample_voyage.db") else null)

    private val golden: JsonArray by lazy {
        JsonParser.parseString(fixture("golden.json").readText(Charsets.UTF_8)).asJsonArray
    }

    private fun goldenCase(questionPrefix: String) =
        golden.map { it.asJsonObject }.single { it["question"].asString.startsWith(questionPrefix) }

    // ── event helpers ──

    private class Recorder : ResearchListener {
        val events: MutableList<ResearchEvent> = Collections.synchronizedList(ArrayList())
        override fun onEvent(event: ResearchEvent) {
            events.add(event)
        }

        inline fun <reified T : ResearchEvent> all(): List<T> = events.toList().filterIsInstance<T>()

        /** The sequence with each run of token events collapsed into one entry. */
        fun shape(): List<String> {
            val out = ArrayList<String>()
            for (e in events.toList()) {
                val s = when (e) {
                    is ResearchEvent.PhaseChanged -> "phase:" + e.phase
                    is ResearchEvent.PhaseCompleted -> "completed:" + e.timing.phase
                    is ResearchEvent.Planned -> "planned"
                    is ResearchEvent.Routed -> "routed:" + e.decision.route
                    is ResearchEvent.SourcesFound -> "sources"
                    is ResearchEvent.AnswerToken -> "answer-tokens"
                    is ResearchEvent.AnswerCompleted -> "answer"
                    is ResearchEvent.CheckToken -> "check-tokens"
                    is ResearchEvent.CheckCompleted -> "check"
                    is ResearchEvent.Completed -> "result"
                    is ResearchEvent.Failed -> "failed:" + e.phase
                }
                if (out.lastOrNull() != s || !s.endsWith("-tokens")) out.add(s)
            }
            return out
        }
    }

    private fun run(
        engine: Engine, corpora: CorpusProvider, question: String, rec: Recorder, config: ResearchConfig = ResearchConfig(),
    ): ResearchResult =
        runBlocking { withTimeout(60_000) { ResearchPipeline(engine, corpora, corpusThread, config).run(question, rec) } }

    private fun assertCorpusThreadOnly() {
        // (compared by identity: in debug mode coroutines rename the thread they run on)
        assertEquals("threads that touched a corpus database", setOf(corpusJavaThread), dbThreads.toSet())
    }

    // ── retrieval first ──

    @Test
    fun littleReadSubjectGoesRetrievalFirst() {
        val case = goldenCase("What happened in the 1983 Harrods bombing")
        val question = case["question"].asString
        val context = case["hits_voyage_context"].asString
        val answer = "An IRA car bomb exploded outside Harrods on 17 December 1983 [1]."
        // list markers and quotes are the planner's to remove; "Harrods bombing" resolves fuzzily
        val engine = FakeEngine(listOf("1. Harrods bombing\n2. \"Provisional Irish Republican Army\"\n", answer))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), question, rec)

        assertEquals(2, engine.calls.size)
        assertEquals(Prompts.PLAN_SYSTEM + "\n\n" + question, engine.calls[0].prompt)
        assertEquals(60, engine.calls[0].nPredict)
        assertEquals(
            Prompts.ANSWER_SYSTEM + "\n\nSources:\n\n" + context + "\n\nQuestion: " + question,
            engine.calls[1].prompt,
        )
        assertEquals(600, engine.calls[1].nPredict)

        assertEquals(listOf("Harrods bombing", "Provisional Irish Republican Army"), result.titles)
        assertEquals(RouteDecision(Route.RETRIEVAL_FIRST, 2127L), result.route)
        assertEquals(answer, result.answer)
        assertNull(result.check)
        assertEquals(answer, result.text)

        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:RETRIEVAL_FIRST",
                "phase:SEARCHING", "completed:SEARCHING", "sources",
                "phase:ANSWERING", "answer-tokens", "completed:ANSWERING", "answer",
                "result", "phase:DONE",
            ),
            rec.shape(),
        )
        assertEquals(listOf("Harrods bombing", "Provisional Irish Republican Army"), rec.all<ResearchEvent.Planned>().single().titles)
        val routed = rec.all<ResearchEvent.Routed>().single()
        assertEquals(2127L, routed.decision.views)
        assertEquals(5000L, routed.threshold)
        assertEquals(answer, rec.all<ResearchEvent.AnswerToken>().joinToString("") { it.text })
        assertEquals(answer, rec.all<ResearchEvent.AnswerCompleted>().single().text)
        assertTrue(rec.all<ResearchEvent.CheckToken>().isEmpty())

        // the sources are exactly the passages of the context, numbered as the model sees them
        val sources = rec.all<ResearchEvent.SourcesFound>().single()
        assertEquals(result.sources, sources.sources)
        assertEquals(case["hits_voyage_used"].asInt, sources.sources.size)
        assertEquals(case["hits_voyage"].asJsonArray.size() - sources.sources.size, sources.dropped)
        assertSourcesMatchContext(sources.sources, context)
        assertEquals("1983 Harrods bombing", sources.sources[0].title)
        assertEquals("title", sources.sources[0].via)

        // timings: one per phase that ran, generations carry the engine's figures
        assertEquals(
            listOf(ResearchPhase.PLANNING, ResearchPhase.SEARCHING, ResearchPhase.ANSWERING),
            result.timings.map { it.phase },
        )
        assertEquals(rec.all<ResearchEvent.PhaseCompleted>().map { it.timing }, result.timings)
        assertNull(result.timings[1].generation)
        assertEquals(5.0, result.timings[2].generation!!.tokensPerSecond, 0.0)
        assertEquals(answer, result.timings[2].generation!!.text)
        assertTrue(result.timings.all { it.wallMs >= 0 })
        assertCorpusThreadOnly()
    }

    private fun assertSourcesMatchContext(sources: List<ResearchSource>, context: String) {
        assertTrue(sources.isNotEmpty())
        var from = 0
        sources.forEachIndexed { i, s ->
            assertEquals(i + 1, s.number)
            val head = "[${s.number}] ${s.title}" + if (s.section.isNotEmpty()) " — ${s.section}" else ""
            val at = context.indexOf(head + "\n", from)
            assertTrue("source heading \"$head\" not in the context after offset $from", at >= 0)
            from = at + head.length
        }
        assertFalse("a source beyond the reported ones", context.contains("\n\n[${sources.size + 1}] "))
    }

    // ── answer first ──

    @Test
    fun continuedCheckIsAFollowUpTurnOfTheDraft() {
        val case = goldenCase("Roughly how many times larger is the population of India")
        val question = case["question"].asString
        val context = case["hits_voyage_context"].asString
        val draft = "India has about 1.4 billion people, roughly 35 times Canada's 40 million."
        val rawCheck = "No corrections. Supported by [1].\nBut wait, let me re-read [2]."
        val engine = FakeEngine(listOf("India\nCanada", draft, rawCheck))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), question, rec, ResearchConfig(checkContinue = true))

        assertEquals(3, engine.calls.size)
        // the plan and the draft each start a conversation; the check continues the draft's
        assertEquals(listOf(false, false, true), engine.calls.map { it.continueChat })
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + question, engine.calls[1].prompt)
        assertEquals(Prompts.CHECK_FOLLOWUP + "\n\nSources:\n\n" + context, engine.calls[2].prompt)
        assertEquals(Prompts.checkFollowupUser(context), engine.calls[2].prompt)
        assertEquals(260, engine.calls[2].nPredict)
        assertEquals("No corrections. Supported by [1].", result.check)
        assertEquals(draft + "\n\n**Source check**\nNo corrections. Supported by [1].", result.text)
        assertEquals(
            listOf(ResearchPhase.PLANNING, ResearchPhase.DRAFTING, ResearchPhase.SEARCHING, ResearchPhase.CHECKING),
            result.timings.map { it.phase },
        )
    }

    @Test
    fun widelyReadSubjectGoesAnswerFirst() {
        val case = goldenCase("Roughly how many times larger is the population of India")
        val question = case["question"].asString
        val context = case["hits_voyage_context"].asString
        val draft = "India has about 1.4 billion people, roughly 35 times Canada's 40 million.\nCanada is about three times larger by area."
        val rawCheck = "  \nNo corrections. India's population and area are supported by [1].\n\n"
        val check = "No corrections. India's population and area are supported by [1]."
        val engine = FakeEngine(listOf("India\nCanada", draft, rawCheck))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), question, rec)

        assertEquals(3, engine.calls.size)
        assertEquals(Prompts.PLAN_SYSTEM + "\n\n" + question, engine.calls[0].prompt)
        assertEquals(60, engine.calls[0].nPredict)
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + question, engine.calls[1].prompt)
        assertEquals(600, engine.calls[1].nPredict)
        assertEquals(
            Prompts.VERIFY_SYSTEM + "\n\nQuestion: " + question + "\n\nDraft answer:\n" + draft +
                "\n\nSources:\n\n" + context,
            engine.calls[2].prompt,
        )
        assertEquals(260, engine.calls[2].nPredict)
        assertEquals(listOf(false, false, false), engine.calls.map { it.continueChat })

        assertEquals(RouteDecision(Route.ANSWER_FIRST, 540755L), result.route)
        assertEquals(draft, result.answer)
        assertEquals(check, result.check)
        assertEquals(draft + "\n\n**Source check**\n" + check, result.text)

        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:ANSWER_FIRST",
                "phase:DRAFTING", "answer-tokens", "completed:DRAFTING", "answer",
                "phase:SEARCHING", "completed:SEARCHING", "sources",
                "phase:CHECKING", "check-tokens", "completed:CHECKING", "check",
                "result", "phase:DONE",
            ),
            rec.shape(),
        )
        assertEquals(draft, rec.all<ResearchEvent.AnswerToken>().joinToString("") { it.text })
        assertEquals(rawCheck, rec.all<ResearchEvent.CheckToken>().joinToString("") { it.text })
        assertEquals(check, rec.all<ResearchEvent.CheckCompleted>().single().text)
        assertEquals(result, rec.all<ResearchEvent.Completed>().single().result)
        assertSourcesMatchContext(result.sources, context)
        assertEquals(
            listOf(ResearchPhase.PLANNING, ResearchPhase.DRAFTING, ResearchPhase.SEARCHING, ResearchPhase.CHECKING),
            result.timings.map { it.phase },
        )
        assertCorpusThreadOnly()
    }

    @Test
    fun sourceCheckIsCleanedOfDeliberation() {
        val case = goldenCase("Roughly how many times larger is the population of India")
        val question = case["question"].asString
        val draft = "India has about 1.4 billion people."
        val rawCheck = "\n- Correction: x [1]\n- Addition: y [2]\nBut wait, let me re-read\n- Correction: z\n"
        val check = "- Correction: x [1]\n- Addition: y [2]"
        val engine = FakeEngine(listOf("India\nCanada", draft, rawCheck))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), question, rec)

        // the stream is the engine's, raw; what is reported as the check is cleaned
        assertEquals(rawCheck, rec.all<ResearchEvent.CheckToken>().joinToString("") { it.text })
        assertEquals(check, rec.all<ResearchEvent.CheckCompleted>().single().text)
        assertEquals(check, result.check)
        assertEquals(draft + "\n\n**Source check**\n" + check, result.text)
        assertEquals(result, rec.all<ResearchEvent.Completed>().single().result)
        // the phase's generation keeps the engine's own text
        assertEquals(rawCheck, result.timings.last().generation!!.text)
    }

    // ── the optional travel route ──

    private val kyotoQuestion = "I am visiting Kyoto for three days. Which districts and sites should I prioritize, and what " +
        "etiquette should I know at temples and shrines?"

    @Test
    fun travelRouteIsOffByDefault() {
        assertFalse(ResearchConfig().travelRoute)
        // a travel question, a guide that resolves, a widely read subject: answer first, as before
        val engine = FakeEngine(listOf("Kyoto", "draft", "check"))
        val rec = Recorder()
        val result = run(engine, sampleProvider(), kyotoQuestion, rec)
        assertEquals(RouteDecision(Route.ANSWER_FIRST, 43328L), result.route)
        assertFalse(result.route.travel)
        assertEquals(3, engine.calls.size)
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + kyotoQuestion, engine.calls[1].prompt)
    }

    @Test
    fun travelRouteSendsATravelQuestionWithAGuideRetrievalFirst() {
        val case = goldenCase("I am visiting Kyoto")
        assertEquals(kyotoQuestion, case["question"].asString)
        val plan = case["titles"].asJsonArray.joinToString("\n") { it.asString }
        val engine = FakeEngine(listOf(plan, "See Fushimi Inari [2]."))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), kyotoQuestion, rec, ResearchConfig(travelRoute = true))

        // widely read (43,328 monthly views, threshold 5,000), and retrieval first all the same
        assertEquals(RouteDecision(Route.RETRIEVAL_FIRST, 43328L, travel = true), result.route)
        assertEquals(result.route, rec.all<ResearchEvent.Routed>().single().decision)
        assertEquals(2, engine.calls.size)
        assertEquals(
            Prompts.ANSWER_SYSTEM + "\n\nSources:\n\n" + case["hits_voyage_context"].asString + "\n\nQuestion: " + kyotoQuestion,
            engine.calls[1].prompt,
        )
        assertNull(result.check)
        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:RETRIEVAL_FIRST",
                "phase:SEARCHING", "completed:SEARCHING", "sources",
                "phase:ANSWERING", "answer-tokens", "completed:ANSWERING", "answer",
                "result", "phase:DONE",
            ),
            rec.shape(),
        )
        assertCorpusThreadOnly()
    }

    @Test
    fun travelRouteNeedsAGuideCorpus() {
        val engine = FakeEngine(listOf("Kyoto", "draft", "check"))
        val result = run(engine, sampleProvider(withVoyage = false), kyotoQuestion, Recorder(), ResearchConfig(travelRoute = true))
        assertEquals(RouteDecision(Route.ANSWER_FIRST, 43328L), result.route)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun travelRouteNeedsATravelStem() {
        // Kyoto has a guide, but nothing in the question is in TRAVEL_STEMS
        val question = "When did Kyoto stop being the capital of Japan?"
        val engine = FakeEngine(listOf("Kyoto", "draft", "check"))
        val result = run(engine, sampleProvider(), question, Recorder(), ResearchConfig(travelRoute = true))
        assertEquals(RouteDecision(Route.ANSWER_FIRST, 43328L), result.route)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun travelRouteNeedsTheFirstTitleToHaveAGuide() {
        // travel stems ("food", "try"), but the sample guide corpus has no "India"; Kyoto comes second
        val question = "What food should I try in India?"
        val engine = FakeEngine(listOf("India\nKyoto", "draft", "check"))
        val result = run(engine, sampleProvider(), question, Recorder(), ResearchConfig(travelRoute = true))
        assertEquals(RouteDecision(Route.ANSWER_FIRST, 540755L), result.route)
        assertEquals(3, engine.calls.size)
    }

    @Test
    fun travelRouteLeavesTheViewsRuleAlone() {
        val question = "What happened in the 1983 Harrods bombing, who carried it out, and what warning was given?"
        val engine = FakeEngine(listOf("1983 Harrods bombing", "answer"))
        val result = run(engine, sampleProvider(), question, Recorder(), ResearchConfig(travelRoute = true))
        assertEquals(RouteDecision(Route.RETRIEVAL_FIRST, 2127L), result.route)
        assertFalse(result.route.travel)
    }

    @Test
    fun voyageCorpusIsOptionalAndChangesTheSources() {
        val case = goldenCase("I am visiting Kyoto")
        val question = case["question"].asString
        val plan = case["titles"].asJsonArray.joinToString("\n") { it.asString }

        val with = FakeEngine(listOf(plan, "draft", "check"))
        val withResult = run(with, sampleProvider(withVoyage = true), question, Recorder())
        val without = FakeEngine(listOf(plan, "draft", "check"))
        val withoutResult = run(without, sampleProvider(withVoyage = false), question, Recorder())

        assertEquals(Route.ANSWER_FIRST, withResult.route.route)
        assertEquals(43328L, withResult.route.views)
        assertTrue(with.calls[2].prompt.endsWith("\n\nSources:\n\n" + case["hits_voyage_context"].asString))
        assertTrue(without.calls[2].prompt.endsWith("\n\nSources:\n\n" + case["hits_context"].asString))
        assertTrue(withResult.sources.any { it.title.startsWith("Wikivoyage: ") })
        assertTrue(withoutResult.sources.none { it.title.startsWith("Wikivoyage: ") })
        assertCorpusThreadOnly()
    }

    @Test
    fun unresolvedFirstTitleGoesAnswerFirst() {
        val question = "What happened in the 1983 Harrods bombing, who carried it out, and what warning was given?"
        // the second title is a little-read article, but only the FIRST one routes
        val engine = FakeEngine(listOf("A Title That Does Not Exist\n1983 Harrods bombing", "Draft.", "Check [1]."))
        val rec = Recorder()

        val result = run(engine, sampleProvider(), question, rec)

        assertEquals(RouteDecision(Route.ANSWER_FIRST, null), result.route)
        assertEquals(3, engine.calls.size)
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + question, engine.calls[1].prompt)
        assertTrue(engine.calls[2].prompt.startsWith(Prompts.VERIFY_SYSTEM + "\n\nQuestion: " + question + "\n\nDraft answer:\nDraft.\n\nSources:\n\n[1] 1983 Harrods bombing"))
        assertEquals("Draft.\n\n**Source check**\nCheck [1].", result.text)
    }

    @Test
    fun emptyPlanGoesAnswerFirst() {
        val question = "What happened in the 1983 Harrods bombing?"
        val engine = FakeEngine(listOf("\n", "Draft.", "Check."))
        val result = run(engine, sampleProvider(), question, Recorder())
        assertEquals(emptyList<String>(), result.titles)
        assertEquals(RouteDecision(Route.ANSWER_FIRST, null), result.route)
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + question, engine.calls[1].prompt)
    }

    // ── empty context ──

    /**
     * A corpus where "Obscure Thing" (10 views) resolves but has no indexed passages, and whose
     * index knows none of the question's words: every retrieval comes back empty.
     */
    private fun barrenCorpus(): CorpusProvider {
        val f = File.createTempFile("barren", ".db").also { tempFiles.add(it) }
        val filler = "zyzzyva"
        DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE blocks(id INTEGER PRIMARY KEY, zdata BLOB NOT NULL)")
                st.execute("CREATE TABLE articles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, views INTEGER NOT NULL, block_id INTEGER NOT NULL, off INTEGER NOT NULL, len INTEGER NOT NULL)")
                st.execute("CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL)")
                st.execute("CREATE VIRTUAL TABLE fts USING fts5(title, section, body, content='', detail=full, tokenize='porter unicode61 remove_diacritics 2')")
                st.execute("INSERT INTO articles VALUES (1, 'Obscure Thing', 10, 1, 0, 0), (2, 'Filler', 10, 1, 0, ${filler.length})")
                st.execute("INSERT INTO chunks VALUES (1, 2, 0, ${filler.length})")
                st.execute("INSERT INTO fts(rowid, title, section, body) VALUES (1, 'Filler', '', '$filler')")
            }
            c.prepareStatement("INSERT INTO blocks VALUES (1, ?)").use { st ->
                st.setBytes(1, Zstd.compress(filler.toByteArray()))
                st.execute()
            }
        }
        return provider(f, null)
    }

    @Test
    fun retrievalFirstWithoutSourcesAnswersClosedBook() {
        val question = "Who invented the obscure thing?"
        val engine = FakeEngine(listOf("Obscure Thing", "Nobody knows."))
        val rec = Recorder()

        val result = run(engine, barrenCorpus(), question, rec)

        assertEquals(RouteDecision(Route.RETRIEVAL_FIRST, 10L), result.route)
        assertEquals(2, engine.calls.size)
        assertEquals(Prompts.CLOSED_SYSTEM + "\n\n" + question, engine.calls[1].prompt)
        assertEquals(600, engine.calls[1].nPredict)
        assertEquals("Nobody knows.", result.text)
        assertTrue(result.sources.isEmpty())
        assertEquals(0, result.sourcesDropped)
        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:RETRIEVAL_FIRST",
                "phase:SEARCHING", "completed:SEARCHING", "sources",
                "phase:ANSWERING", "answer-tokens", "completed:ANSWERING", "answer",
                "result", "phase:DONE",
            ),
            rec.shape(),
        )
        assertCorpusThreadOnly()
    }

    @Test
    fun answerFirstWithoutSourcesSkipsTheCheck() {
        val question = "Who invented the obscure thing?"
        val engine = FakeEngine(listOf("Something Unknown", "Nobody knows."))
        val rec = Recorder()

        val result = run(engine, barrenCorpus(), question, rec)

        assertEquals(RouteDecision(Route.ANSWER_FIRST, null), result.route)
        assertEquals(2, engine.calls.size)
        assertNull(result.check)
        assertEquals("Nobody knows.", result.text)
        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:ANSWER_FIRST",
                "phase:DRAFTING", "answer-tokens", "completed:DRAFTING", "answer",
                "phase:SEARCHING", "completed:SEARCHING", "sources",
                "result", "phase:DONE",
            ),
            rec.shape(),
        )
    }

    // ── cancellation and failure ──

    private fun cancelWhileHanging(script: List<String>, hangAt: Int, corpora: CorpusProvider, question: String): Pair<FakeEngine, Recorder> {
        val engine = FakeEngine(script, hangAt)
        val rec = Recorder()
        runBlocking {
            withTimeout(60_000) {
                val job = async(start = CoroutineStart.DEFAULT) {
                    ResearchPipeline(engine, corpora, corpusThread).run(question, rec)
                }
                engine.hanging.await()
                job.cancel()
                try {
                    job.await()
                    fail("a cancelled run must not return a result")
                } catch (expected: CancellationException) {
                }
            }
        }
        return engine to rec
    }

    @Test
    fun cancellationMidDraftStopsTheRun() {
        val question = "Roughly how many times larger is the population of India than that of Canada?"
        val (engine, rec) = cancelWhileHanging(listOf("India\nCanada", "India has about"), 1, sampleProvider(), question)

        assertEquals(2, engine.calls.size) // no search, no check after the cancel
        assertEquals(1, engine.cancelledCalls)
        assertEquals(
            listOf(
                "phase:PLANNING", "completed:PLANNING", "planned", "routed:ANSWER_FIRST",
                "phase:DRAFTING", "answer-tokens", "phase:CANCELLED",
            ),
            rec.shape(),
        )
        assertEquals("India has about", rec.all<ResearchEvent.AnswerToken>().joinToString("") { it.text })
        assertTrue(rec.all<ResearchEvent.Completed>().isEmpty())
    }

    @Test
    fun cancellationWhilePlanningAndWhileChecking() {
        val question = "Roughly how many times larger is the population of India than that of Canada?"
        val (planning, planRec) = cancelWhileHanging(listOf("India"), 0, sampleProvider(), question)
        assertEquals(1, planning.cancelledCalls)
        assertEquals(listOf("phase:PLANNING", "phase:CANCELLED"), planRec.shape())

        val (checking, checkRec) = cancelWhileHanging(listOf("India", "Draft.", "No corr"), 2, sampleProvider(), question)
        assertEquals(1, checking.cancelledCalls)
        assertEquals(listOf("phase:CHECKING", "check-tokens", "phase:CANCELLED"), checkRec.shape().takeLast(3))
    }

    @Test
    fun cancellationWhileSearchingTakesEffectWhenTheCorpusCallReturns() {
        val question = "What happened in the 1983 Harrods bombing, who carried it out, and what warning was given?"
        val inner = sampleProvider()
        val searching = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // the voyage lookup is the first corpus call of the search; hold the corpus thread there
        val corpora = object : CorpusProvider {
            override fun wiki() = inner.wiki()
            override fun voyage(): Corpus? {
                searching.complete(Unit)
                runBlocking { release.await() }
                return inner.voyage()
            }
        }
        val engine = FakeEngine(listOf("1983 Harrods bombing", "never asked"))
        val rec = Recorder()
        runBlocking {
            withTimeout(60_000) {
                val job = async { ResearchPipeline(engine, corpora, corpusThread).run(question, rec) }
                searching.await()
                job.cancel()
                release.complete(Unit)
                try {
                    job.await()
                    fail("a cancelled run must not return a result")
                } catch (expected: CancellationException) {
                }
            }
        }
        assertEquals(1, engine.calls.size) // the answer was never requested
        assertEquals(listOf("phase:SEARCHING", "phase:CANCELLED"), rec.shape().takeLast(2))
    }

    @Test
    fun engineFailureIsReportedAndRethrown() {
        val question = "Roughly how many times larger is the population of India than that of Canada?"
        val engine = object : Engine {
            var n = 0
            override suspend fun generate(prompt: String, nPredict: Int, onToken: (String) -> Unit, continueChat: Boolean): Generation {
                if (n++ == 0) return Generation("India")
                throw IllegalStateException("prompt exceeds n_ctx")
            }
        }
        val rec = Recorder()
        try {
            run(engine, sampleProvider(), question, rec)
            fail("the engine's failure must surface")
        } catch (e: IllegalStateException) {
            assertEquals("prompt exceeds n_ctx", e.message)
        }
        assertEquals(listOf("phase:DRAFTING", "failed:DRAFTING", "phase:FAILED"), rec.shape().takeLast(3))
        assertNotNull(rec.all<ResearchEvent.Failed>().single().message)
    }


    // ── background search ──

    /** A second provider over the same files, on its own thread, with its own thread record. */
    /**
     * The whole-index BM25 query held back for [holdMs] (on a phone it takes about 20 s) unless
     * [interrupt] comes first, after which it throws, as an interrupted query does.
     */
    private class SlowBm25Db(private val inner: SqlDatabase, private val holdMs: Long) : SqlDatabase {
        val interrupts = AtomicInteger()
        private val released = CountDownLatch(1)

        override fun query(sql: String, vararg args: Any?): List<Array<Any?>> {
            if ("bm25(fts, 8.0, 3.0, 1.0)" in sql && released.await(holdMs, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("interrupted")
            }
            return inner.query(sql, *args)
        }

        override fun exec(sql: String, vararg args: Any?) = inner.exec(sql, *args)

        override fun interrupt() {
            interrupts.incrementAndGet()
            released.countDown()
        }
    }

    private class Background(wiki: File, voyage: File?, holdBm25Ms: Long = 0) : AutoCloseable {
        val threads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())
        @Volatile var javaThread: Thread? = null
        val dispatcher: ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "test-search").apply { isDaemon = true }.also { javaThread = it } }
                .asCoroutineDispatcher()
        val priorities: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        private val dbs = ArrayList<JdbcSqlDatabase>()
        @Volatile var slow: SlowBm25Db? = null
        private val provider = object : CorpusProvider {
            private val zstd = JniZstdDecompressor()
            private val w by lazy { open(wiki, holdBm25Ms) }
            private val v by lazy { voyage?.let { open(it, 0) } }
            private fun open(f: File, holdMs: Long): Corpus {
                val db = JdbcSqlDatabase(f).also { dbs.add(it) }
                val inner: SqlDatabase = if (holdMs > 0) SlowBm25Db(db, holdMs).also { slow = it } else db
                return Corpus(ThreadRecordingDb(inner, threads), zstd)
            }
            override fun wiki() = w
            override fun voyage() = v
            override fun interrupt() {
                slow?.interrupt()
            }
        }
        val search = BackgroundSearch(provider, dispatcher) { priorities.add(it) }

        override fun close() {
            runBlocking { withContext(dispatcher) { dbs.forEach { it.close() } } }
            dispatcher.close()
        }
    }

    private fun backgroundRun(
        engine: Engine, bg: Background, question: String, rec: Recorder, config: ResearchConfig = ResearchConfig(),
    ): ResearchResult = runBlocking {
        withTimeout(60_000) {
            ResearchPipeline(engine, sampleProvider(), corpusThread, config, bg.search).run(question, rec)
        }
    }

    /** Everything but wall times: what a background search must leave unchanged. */
    private fun ResearchResult.withoutTimes() = copy(timings = timings.map { it.copy(wallMs = 0, workMs = null, parts = emptyList()) })

    @Test
    fun backgroundSearchChangesNothingButTheTimingAnswerFirst() {
        val case = goldenCase("Roughly how many times larger is the population of India")
        val question = case["question"].asString
        val script = listOf("India\nCanada", "India has about 1.4 billion people.", "No corrections. See [1].")
        val plainRec = Recorder()
        val plainEngine = FakeEngine(script)
        val plain = run(plainEngine, sampleProvider(), question, plainRec, ResearchConfig(checkContinue = true))

        Background(fixture("sample_wiki.db"), fixture("sample_voyage.db")).use { bg ->
            val rec = Recorder()
            val engine = FakeEngine(script)
            val result = backgroundRun(engine, bg, question, rec, ResearchConfig(checkContinue = true))

            assertEquals(plainEngine.calls.map { it.prompt }, engine.calls.map { it.prompt })
            assertEquals(plain.withoutTimes(), result.withoutTimes())
            assertEquals(plainRec.shape(), rec.shape())
            // the search ran in the background: its own time is reported beside the wait
            val searching = result.timings.single { it.phase == ResearchPhase.SEARCHING }
            assertNotNull(searching.workMs)
            // background while the engine works, raised when the run waits for it
            assertEquals(listOf(true, false), bg.priorities.toList())
            // each connection stayed on its own thread
            assertEquals(setOf(bg.javaThread), bg.threads.toSet())
            assertCorpusThreadOnly()
        }
    }

    @Test
    fun backgroundSearchChangesNothingButTheTimingRetrievalFirst() {
        val case = goldenCase("What happened in the 1983 Harrods bombing")
        val question = case["question"].asString
        val script = listOf("1. Harrods bombing\n2. \"Provisional Irish Republican Army\"\n", "An IRA car bomb [1].")
        val plainRec = Recorder()
        val plainEngine = FakeEngine(script)
        val plain = run(plainEngine, sampleProvider(), question, plainRec)

        Background(fixture("sample_wiki.db"), fixture("sample_voyage.db")).use { bg ->
            val rec = Recorder()
            val engine = FakeEngine(script)
            val result = backgroundRun(engine, bg, question, rec)

            assertEquals(plainEngine.calls.map { it.prompt }, engine.calls.map { it.prompt })
            assertEquals(plain.withoutTimes(), result.withoutTimes())
            assertEquals(plainRec.shape(), rec.shape())
            assertEquals(listOf(true, false), bg.priorities.toList())
            assertEquals(setOf(bg.javaThread), bg.threads.toSet())
            assertCorpusThreadOnly()
        }
    }

    @Test
    fun questionSearchGivesRetrieveTheSameHits() {
        val zstd = JniZstdDecompressor()
        val wikiDb = JdbcSqlDatabase(fixture("sample_wiki.db"))
        val voyageDb = JdbcSqlDatabase(fixture("sample_voyage.db"))
        try {
            val wiki = Corpus(wikiDb, zstd)
            val voyage = Corpus(voyageDb, zstd)
            var n = 0
            for (c in golden.map { it.asJsonObject }) {
                val question = c["question"].asString
                val titles = c["titles"].asJsonArray.map { it.asString }
                val pre = wiki.questionSearch(question)
                assertEquals(question, wiki.retrieve(question, titles, 6, voyage), wiki.retrieve(question, titles, 6, voyage, pre))
                assertEquals(question, wiki.retrieve(question, titles, 6), wiki.retrieve(question, titles, 6, null, pre))
                n++
            }
            assertTrue(n > 0)
        } finally {
            wikiDb.close()
            voyageDb.close()
        }
    }

    /** retrieve() skips BM25 only when its hits could not reach the result: the same hits either way. */
    @Test
    fun skippingTheBm25ChangesNoRetrieval() {
        val zstd = JniZstdDecompressor()
        val wikiDb = JdbcSqlDatabase(fixture("sample_wiki.db"))
        val voyageDb = JdbcSqlDatabase(fixture("sample_voyage.db"))
        try {
            val wiki = Corpus(wikiDb, zstd)
            val voyage = Corpus(voyageDb, zstd)
            var full = 0
            var room = 0
            for (c in golden.map { it.asJsonObject }) {
                val question = c["question"].asString
                val titles = c["titles"].asJsonArray.map { it.asString }
                for (v in listOf(voyage, null)) {
                    val t = wiki.titleHits(question, titles, 6, v)
                    if (t.full) full++ else room++
                    val always = wiki.withBm25(t, wiki.bm25(t.stems)) // the search before the skip
                    assertEquals(question, always, wiki.retrieve(question, titles, 6, v))
                }
            }
            assertTrue("both kinds of case covered", full > 0 && room > 0)
        } finally {
            wikiDb.close()
            voyageDb.close()
        }
    }

    @Test
    fun retrievalFirstStopsTheBm25WhenThePlannedArticlesFillTheSources() {
        val case = goldenCase("What happened in the 1983 Harrods bombing")
        val question = case["question"].asString
        val script = listOf("1. Harrods bombing\n2. \"Provisional Irish Republican Army\"\n", "An IRA car bomb [1].")
        val plain = run(FakeEngine(script), sampleProvider(), question, Recorder())

        Background(fixture("sample_wiki.db"), fixture("sample_voyage.db"), holdBm25Ms = 60_000).use { bg ->
            val t0 = System.nanoTime()
            val result = backgroundRun(FakeEngine(script), bg, question, Recorder())
            val seconds = (System.nanoTime() - t0) / 1e9

            assertEquals(plain.withoutTimes(), result.withoutTimes())
            assertTrue("did not wait for the held BM25 ($seconds s)", seconds < 30)
            assertTrue("the BM25 query was interrupted", bg.slow!!.interrupts.get() >= 1)
            val parts = result.timings.single { it.phase == ResearchPhase.SEARCHING }.parts.toMap()
            assertEquals(0L, parts["bm25_needed"])
        }
    }

    @Test
    fun retrievalFirstWaitsForTheBm25WhenTheSourcesHaveRoom() {
        val case = goldenCase("What are the main arguments for and against rent control")
        val question = case["question"].asString
        val script = listOf("Rent control\nHousing economics", "Economists mostly oppose it [1].")
        val config = ResearchConfig(routeViews = Long.MAX_VALUE) // retrieval first, whatever the views
        val plain = run(FakeEngine(script), sampleProvider(), question, Recorder(), config)

        Background(fixture("sample_wiki.db"), fixture("sample_voyage.db"), holdBm25Ms = 1_000).use { bg ->
            val result = backgroundRun(FakeEngine(script), bg, question, Recorder(), config)

            assertEquals(plain.withoutTimes(), result.withoutTimes())
            assertEquals(0, bg.slow!!.interrupts.get())
            val parts = result.timings.single { it.phase == ResearchPhase.SEARCHING }.parts.toMap()
            assertEquals(1L, parts["bm25_needed"])
        }
    }

    @Test
    fun cancellationMidDraftDoesNotWaitForTheBackgroundSearch() {
        val question = "Roughly how many times larger is the population of India than that of Canada?"
        Background(fixture("sample_wiki.db"), fixture("sample_voyage.db")).use { bg ->
            val engine = FakeEngine(listOf("India\nCanada", "India has about"), hangAt = 1)
            val rec = Recorder()
            runBlocking {
                withTimeout(60_000) {
                    val job = async { ResearchPipeline(engine, sampleProvider(), corpusThread, ResearchConfig(), bg.search).run(question, rec) }
                    engine.hanging.await()
                    job.cancel()
                    try {
                        job.await()
                        fail("a cancelled run must not return a result")
                    } catch (expected: CancellationException) {
                    }
                }
            }
            assertEquals(1, engine.cancelledCalls)
            assertEquals(listOf("phase:DRAFTING", "answer-tokens", "phase:CANCELLED"), rec.shape().takeLast(3))
        }
    }

    @Test
    fun planDropsAnEchoOfItsOwnInstructions() {
        val out = "Harrods bombing\nIrish Republican Army\nOutput only the titles, nothing else."
        assertEquals(listOf("Harrods bombing", "Irish Republican Army"), Planner.parsePlanOutput(out))
        // a real title that shares words with the prompt is kept
        assertEquals(listOf("Titles of nobility"), Planner.parsePlanOutput("Titles of nobility"))
    }

    companion object {
        private const val CORPUS_THREAD = "test-corpus"
    }
}
