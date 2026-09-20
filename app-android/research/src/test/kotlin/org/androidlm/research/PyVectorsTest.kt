package org.androidlm.research

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Small database-free checks against values computed by the Python reference (py_vectors.json
 * was produced by running scripts/rag.py's own functions on synthetic inputs that include
 * supplementary-plane characters, NBSP, U+0085 and combining marks).
 */
class PyVectorsTest {
    private val v: JsonObject = JsonParser.parseString(
        javaClass.getResource("/py_vectors.json")!!.readText(Charsets.UTF_8),
    ).asJsonObject

    private val stems = listOf(Stem("energi", 2.0), Stem("kyoto", 3.0), Stem("end", 1.5), Stem("citi", 1.0), Stem("treat", 0.5))

    @Test fun planOutputParsing() {
        for (p in v["plans"].asJsonArray) {
            val (text, expected) = p.asJsonArray.let { it[0].asString to it[1].asJsonArray.map { t -> t.asString } }
            assertEquals(text, expected, Planner.parsePlanOutput(text))
        }
    }

    @Test fun planOutputParsingExamples() {
        assertEquals(
            listOf("Kyoto", "Etiquette in Japan", "Shinto shrine", "Fushimi Inari-taisha"),
            Planner.parsePlanOutput("1. Kyoto\n2) \"Etiquette in Japan\"\n- Shinto shrine\n* Fushimi Inari-taisha\n5. Extra"),
        )
        // one-character and 80-character lines are dropped, 79 is kept
        assertEquals(listOf("y".repeat(79)), Planner.parsePlanOutput("A\n" + "x".repeat(80) + "\n" + "y".repeat(79)))
        // a title's own leading number is not a list marker
        assertEquals(listOf("1983 Harrods bombing"), Planner.parsePlanOutput("1983 Harrods bombing"))
        assertEquals(listOf("1983 Harrods bombing"), Planner.parsePlanOutput("2. 1983 Harrods bombing"))
        assertEquals(emptyList<String>(), Planner.parsePlanOutput(""))
    }

    @Test fun routerRule() {
        assertEquals(Route.RETRIEVAL_FIRST, Planner.routeFor(4999))
        assertEquals(Route.RETRIEVAL_FIRST, Planner.routeFor(0))
        assertEquals(Route.ANSWER_FIRST, Planner.routeFor(5000))
        assertEquals(Route.ANSWER_FIRST, Planner.routeFor(43328))
        assertEquals(Route.ANSWER_FIRST, Planner.routeFor(null)) // first title unresolved, or no titles
        assertEquals(Route.RETRIEVAL_FIRST, Planner.routeFor(5000, routeViews = 5001))
    }

    @Test fun routerWithoutTitlesNeverTouchesTheCorpus() {
        val db = object : SqlDatabase {
            override fun query(sql: String, vararg args: Any?): List<Array<Any?>> = when {
                sql.startsWith("select max(id)") -> listOf(arrayOf(10L))
                sql.contains("sqlite_master") -> emptyList()
                else -> throw AssertionError("unexpected query: $sql")
            }
            override fun exec(sql: String, vararg args: Any?) {}
        }
        val corpus = Corpus(db, object : ZstdDecompressor { override fun decompress(data: ByteArray) = data })
        val d = Planner.route(corpus, emptyList())
        assertNull(d.views)
        assertEquals(Route.ANSWER_FIRST, d.route)
    }

    @Test fun sectionOfUsesCodePointOffsets() {
        val doc = PyText(v["doc"].asString)
        for (s in v["sections"].asJsonArray) {
            val start = s.asJsonArray[0].asInt
            assertEquals("section_of at $start", s.asJsonArray[1].asString, Corpus.sectionOf(doc, start))
        }
    }

    @Test fun coverage() {
        for (c in v["coverage"].asJsonArray) {
            val text = c.asJsonArray[0].asString
            assertEquals(text, c.asJsonArray[1].asDouble, Corpus.coverage(text, stems), 0.0)
        }
    }

    @Test fun passageScore() {
        val db = object : SqlDatabase {
            override fun query(sql: String, vararg args: Any?): List<Array<Any?>> =
                if (sql.startsWith("select max(id)")) listOf(arrayOf(10L)) else emptyList()
            override fun exec(sql: String, vararg args: Any?) {}
        }
        val corpus = Corpus(db, object : ZstdDecompressor { override fun decompress(data: ByteArray) = data })
        val doc = PyText(v["doc"].asString)
        for (p in v["pscore"].asJsonArray.map { it.asJsonArray }) {
            assertEquals("passage_score ${p[0]}..${p[1]}", p[2].asDouble, corpus.passageScore(doc, p[0].asInt, p[1].asInt, stems), 1e-15)
        }
    }

    @Test fun buildContext() {
        val hits = v["hits"].asJsonArray.map { it.asJsonObject }.mapIndexed { i, h ->
            Hit(ArticleRef(i.toLong()), 0, h["title"].asString, h["section"].asString, h["text"].asString, 1.0, "title",
                lead = h.has("lead") && h["lead"].asBoolean)
        }
        for (c in v["contexts"].asJsonArray.map { it.asJsonArray }) {
            val built = buildContext(hits, c[0].asInt, c[1].asInt)
            assertEquals("context ${c[0]}/${c[1]}", c[2].asString, built.context)
            assertEquals("used ${c[0]}/${c[1]}", c[3].asInt, built.usedHits.size)
        }
    }

    @Test fun roundAndSum() {
        for (r in v["rounds"].asJsonArray.map { it.asJsonArray }) {
            assertEquals("round(${r[0]}, 2)", r[1].asDouble, Py.round(r[0].asDouble, 2), 0.0)
        }
        for (s in v["sum"].asJsonArray.map { it.asJsonArray }) {
            assertEquals("sum(${s[0]})", s[1].asDouble, Py.sum(s[0].asJsonArray.map { it.asDouble }), 0.0)
        }
    }

    @Test fun promptsAreVerbatim() {
        // SHA-256 of each constant in scripts/rag.py (UTF-8)
        assertEquals("c3a97bcdb85d9ea307d2561846ce49f4c496ff5d222cede41248e90397635b14", sha256(Prompts.PLAN_SYSTEM))
        assertEquals("f559cfb82c78c77c65448041e7290216b88cd1fe48bf73fa76d20ee65819b749", sha256(Prompts.ANSWER_SYSTEM))
        assertEquals("03546ba08f0631423a6757422b8941bc171ccefd2db1d8620bbdbc0284780afd", sha256(Prompts.CLOSED_SYSTEM))
        assertEquals("35dfab9b25aabb3f7a830602e6bd1f8b2bf0692b3b0f8fc86cfd870d56972937", sha256(Prompts.VERIFY_SYSTEM))
        assertEquals("0a65d1938ad93fc3a363504cbdaf5542a186bbef3785f64cbec136968af60590", sha256(Lexicon.STOP.sorted().joinToString(" ")))
        assertEquals(
            "44de40e36f178d0bbedb607facc7c61244edf149d398e2f9aa2a935d6bc6db92",
            sha256(Lexicon.ASPECT_HEADINGS.toSortedMap().entries.joinToString(";") { it.key + "=" + it.value.joinToString(" ") }),
        )
        assertEquals("Sources:\n\nCTX\n\nQuestion: Q?", Prompts.answerUser("CTX", "Q?"))
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
