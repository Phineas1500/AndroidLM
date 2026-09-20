package org.androidlm.research

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager

/**
 * Small checks that need no fixture files, against values computed by the Python reference
 * (py_vectors.json was produced by running scripts/rag.py's own functions on synthetic inputs
 * that include supplementary-plane characters, NBSP, U+0085 and combining marks; rag.py was
 * loaded with a stub in place of its zstandard import, which none of these functions use).
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
        val corpus = dbFreeCorpus()
        val doc = PyText(v["doc"].asString)
        for (p in v["pscore"].asJsonArray.map { it.asJsonArray }) {
            assertEquals("passage_score ${p[0]}..${p[1]}", p[2].asDouble, corpus.passageScore(doc, p[0].asInt, p[1].asInt, stems), 1e-15)
        }
    }

    private fun dbFreeCorpus(): Corpus {
        val db = object : SqlDatabase {
            override fun query(sql: String, vararg args: Any?): List<Array<Any?>> =
                if (sql.startsWith("select max(id)")) listOf(arrayOf(10L)) else emptyList()
            override fun exec(sql: String, vararg args: Any?) {}
        }
        return Corpus(db, object : ZstdDecompressor { override fun decompress(data: ByteArray) = data })
    }

    /** rows of `aspect`: [start, end, stems, section, score, score at bonus 0.5, score at bonus 0] */
    @Test fun aspectHeadingsMatchAsPhrases() {
        val corpus = dbFreeCorpus()
        val doc = PyText(v["aspect_doc"].asString)
        var bonuses = 0
        for (a in v["aspect"].asJsonArray.map { it.asJsonArray }) {
            val (start, end) = a[0].asInt to a[1].asInt
            val st = a[2].asJsonArray.map { Stem(it.asJsonArray[0].asString, it.asJsonArray[1].asDouble) }
            val what = "passage_score ${st.map { it.stem }} in \"${a[3].asString}\""
            assertEquals(what, a[3].asString, Corpus.sectionOf(doc, start))
            assertEquals(what, a[4].asDouble, corpus.passageScore(doc, start, end, st), 1e-15)
            assertEquals("$what, bonus 0.5", a[5].asDouble, corpus.passageScore(doc, start, end, st, 0.5), 1e-15)
            assertEquals("$what, bonus 0", a[6].asDouble, corpus.passageScore(doc, start, end, st, aspectBonus = 0.0), 1e-15)
            if (a[5].asDouble != a[6].asDouble) bonuses++
        }
        assertTrue("the vectors exercise the bonus", bonuses > 5)
    }

    @Test fun getAroundDoesNotMatchGetIn() {
        val corpus = dbFreeCorpus()
        val doc = PyText("# Nepal\n## Get in\n### Visas\nOn arrival.\n## Get around\nBy bus.\n## Getting around town\nOn foot.\n")
        val around = listOf(Stem("around", 1.0)) // ASPECT_HEADINGS: "get around"
        fun bonus(at: String, stems: List<Stem>): Double {
            val start = doc.value.indexOf(at)
            val end = start + at.length
            assertTrue(start >= 0)
            return corpus.passageScore(doc, start, end, stems, 0.5) - corpus.passageScore(doc, start, end, stems, 0.0)
        }
        assertEquals("Get in > Visas", Corpus.sectionOf(doc, doc.value.indexOf("On arrival.")))
        assertEquals(0.0, bonus("On arrival.", around), 0.0)
        assertEquals(0.5, bonus("By bus.", around), 1e-15)
        assertEquals(0.0, bonus("On foot.", around), 0.0) // "getting around": "get around" is not in it
        // "visa" asks for "get in", which is in the path "get in > visas" but not in "get around"
        assertEquals(0.5, bonus("On arrival.", listOf(Stem("visa", 1.0))), 1e-15)
        assertEquals(0.0, bonus("By bus.", listOf(Stem("visa", 1.0))), 0.0)
        // the default bonus
        val s = doc.value.indexOf("By bus.")
        assertEquals(0.25, corpus.passageScore(doc, s, s + 7, listOf(Stem("car", 1.0))), 1e-15)
    }

    @Test fun boundedPhraseSearch() {
        assertTrue(Py.containsAtBoundaries("get in", "get in > visas"))
        assertTrue(Py.containsAtBoundaries("get in", "how to get in"))
        assertTrue(!Py.containsAtBoundaries("get in", "get into trouble"))
        assertTrue(!Py.containsAtBoundaries("get in", "forget in time"))
        assertTrue(!Py.containsAtBoundaries("get in", "get_in"))
        assertTrue(Py.containsAtBoundaries("see", "sightseeing > see"))
        assertTrue(!Py.containsAtBoundaries("see", "sightseeing"))
        assertTrue(!Py.containsAtBoundaries("see", ""))
    }

    /** Titles resolved by Python on the same small database (rows of `resolve`). */
    @Test fun oneWordTitlesNeverMatchFuzzily() {
        val r = v["resolve"].asJsonObject
        fun ids(cases: String, column: Int) =
            r[cases].asJsonArray.map { it.asJsonArray }.map { c -> c[0].asString to c[column].let { if (it.isJsonNull) null else it.asLong } }

        fun build(withRedirects: Boolean): File {
            val f = File.createTempFile("resolve", ".db")
            DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath).use { c ->
                c.createStatement().use { st ->
                    st.execute("CREATE TABLE articles(id INTEGER PRIMARY KEY, title TEXT NOT NULL, views INTEGER NOT NULL, block_id INTEGER NOT NULL, off INTEGER NOT NULL, len INTEGER NOT NULL)")
                    st.execute("CREATE TABLE chunks(id INTEGER PRIMARY KEY, article_id INTEGER NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL)")
                    st.execute("CREATE VIRTUAL TABLE fts USING fts5(title, section, body, content='', detail=full, tokenize='porter unicode61 remove_diacritics 2')")
                    if (withRedirects) st.execute("CREATE TABLE redirects(title TEXT PRIMARY KEY, article_id INTEGER NOT NULL)")
                }
                for (a in r["articles"].asJsonArray.map { it.asJsonArray }) {
                    c.prepareStatement("INSERT INTO articles VALUES (?,?,?,1,0,0)").use { st ->
                        st.setLong(1, a[0].asLong); st.setString(2, a[1].asString); st.setLong(3, a[2].asLong); st.execute()
                    }
                    c.prepareStatement("INSERT INTO chunks VALUES (?,?,0,0)").use { st ->
                        st.setLong(1, a[0].asLong * 10); st.setLong(2, a[0].asLong); st.execute()
                    }
                    c.prepareStatement("INSERT INTO fts(rowid, title, section, body) VALUES (?,?,'','body text')").use { st ->
                        st.setLong(1, a[0].asLong * 10); st.setString(2, a[1].asString); st.execute()
                    }
                }
                if (withRedirects) for (d in r["redirects"].asJsonArray.map { it.asJsonArray }) {
                    c.prepareStatement("INSERT INTO redirects VALUES (?,?)").use { st ->
                        st.setString(1, d[0].asString); st.setLong(2, d[1].asLong); st.execute()
                    }
                }
            }
            return f
        }

        val zstd = object : ZstdDecompressor { override fun decompress(data: ByteArray) = data }
        for (withRedirects in listOf(true, false)) {
            val f = build(withRedirects)
            try {
                JdbcSqlDatabase(f).use { db ->
                    val corpus = Corpus(db, zstd)
                    assertEquals(withRedirects, corpus.hasRedirects)
                    if (withRedirects) {
                        for ((title, id) in ids("cases", 1)) assertEquals("resolve_title(\"$title\")", id, corpus.resolveTitle(title))
                        for ((title, id) in ids("cases", 2)) assertEquals("resolve_title(\"$title\", fuzzy=False)", id, corpus.resolveTitle(title, fuzzy = false))
                    } else {
                        for ((title, id) in ids("cases_no_redirects", 1)) assertEquals("resolve_title(\"$title\"), no redirects", id, corpus.resolveTitle(title))
                    }
                }
            } finally {
                f.delete()
            }
        }
        // what the vectors must show: one word resolves exactly or by redirect only, two words fuzzily
        val byTitle = ids("cases", 1).toMap()
        assertNull(byTitle["Ella"])       // not "Ella Mai" / "Ella Fitzgerald"
        assertNull(byTitle["Station"])    // not "Kyoto Station"
        assertEquals(4L, byTitle["Ger"])  // a redirect still applies
        assertEquals(7L, byTitle["KYOTO"]) // and so does the exact (case-insensitive) title
        assertEquals(2L, byTitle["Mai Ella"])
        assertEquals(12L, byTitle["Rent control"])
    }

    @Test fun oneWordTitleSkipsTheTitleSearch() {
        val db = object : SqlDatabase {
            override fun query(sql: String, vararg args: Any?): List<Array<Any?>> = when {
                sql.startsWith("select max(id)") -> listOf(arrayOf(10L))
                sql.contains("fts match") -> throw AssertionError("title search for ${args.toList()}")
                else -> emptyList()
            }
            override fun exec(sql: String, vararg args: Any?) {}
        }
        val corpus = Corpus(db, object : ZstdDecompressor { override fun decompress(data: ByteArray) = data })
        assertNull(corpus.resolveTitle("Ger"))
        assertNull(corpus.resolveTitle(" Ger! "))
        assertNull(corpus.resolveTitle(""))
        try {
            corpus.resolveTitle("Ger Canning")
            throw IllegalStateException("two words must reach the title search")
        } catch (expected: AssertionError) {
        }
    }

    @Test fun cleanCheck() {
        for (c in v["clean_check"].asJsonArray.map { it.asJsonArray }) {
            assertEquals("clean_check(${c[0]})", c[1].asString, cleanCheck(c[0].asString))
        }
    }

    @Test fun cleanCheckExamples() {
        assertEquals(
            "- Correction: x [1]\n- Addition: y [2]",
            cleanCheck("- Correction: x [1]\n- Addition: y [2]\nBut wait, let me re-read\n- Correction: z"),
        )
        // a deliberation line behind a bullet
        assertEquals("- Correction: x [1]", cleanCheck("- Correction: x [1]\n- Wait, that is not what [1] says\n- Addition: y [2]"))
        assertEquals("No corrections [1].", cleanCheck("No corrections [1].\n  * Let me double-check."))
        // nothing to cut: only stripped
        assertEquals("No corrections. Supported by [1] and [3].", cleanCheck("\n No corrections. Supported by [1] and [3]. \n"))
        assertEquals("- Waiting rooms are heated [2]\n- Actually it is closed [3]", cleanCheck("- Waiting rooms are heated [2]\n- Actually it is closed [3]"))
        assertEquals("", cleanCheck("Hmm, let me look at the sources."))
    }

    @Test fun leadPassagesAreTrimmedToo() {
        val hits = v["lead_hits"].asJsonArray.map { it.asJsonObject }.mapIndexed { i, h ->
            Hit(ArticleRef(i.toLong()), 0, h["title"].asString, h["section"].asString, h["text"].asString, 1.0, "title",
                lead = h.has("lead") && h["lead"].asBoolean)
        }
        // rows: [budget, passage_chars or null (default), lead_chars or null (default), context, used]
        for (c in v["lead_contexts"].asJsonArray.map { it.asJsonArray }) {
            val budget = c[0].asInt
            val built = when {
                c[1].isJsonNull && c[2].isJsonNull -> buildContext(hits, budget)
                c[1].isJsonNull -> buildContext(hits, budget, leadChars = c[2].asInt)
                else -> buildContext(hits, budget, c[1].asInt, c[2].asInt)
            }
            assertEquals("context ${c[0]}/${c[1]}/${c[2]}", c[3].asString, built.context)
            assertEquals("used ${c[0]}/${c[1]}/${c[2]}", c[4].asInt, built.usedHits.size)
        }
        // the defaults: a lead is cut at the last sentence end before 1000, any other passage before 650
        val sentence = "Lead sentence number one is here. "
        val text = sentence.repeat(50).trim()
        val built = buildContext(listOf(hits[0].copy(text = text), hits[1].copy(text = text)), 100000)
        val (lead, other) = built.context.split("\n\n").map { it.substringAfter("\n") }
        assertEquals(sentence.repeat(1000 / sentence.length).trim(), lead)
        assertEquals(sentence.repeat(650 / sentence.length).trim(), other)
        assertTrue(lead.length in 651..1000)
        // at most 1000: untouched
        val exact = "x".repeat(1000)
        assertEquals("[1] T\n$exact", buildContext(listOf(hits[0].copy(title = "T", text = exact)), 100000).context)
        assertEquals("[1] T\n$exact", buildContext(listOf(hits[0].copy(title = "T", text = exact + "y")), 100000).context)
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
            // ";".join(k + "=" + v for k, v in sorted(ASPECT_HEADINGS.items())): values are ", "-separated phrases
            "547c76fe466407ca34503600915e8cd9f698adcbca3ebfebd5ccedb84fa11f41",
            sha256(Lexicon.ASPECT_HEADINGS.toSortedMap().entries.joinToString(";") { it.key + "=" + it.value.joinToString(", ") }),
        )
        assertEquals("6f74956360774a93232b48142c5138cfd7fb80c4db3cadbc0023eb96227f5be2", sha256(Lexicon.TRAVEL_STEMS.sorted().joinToString(" ")))
        assertEquals("Sources:\n\nCTX\n\nQuestion: Q?", Prompts.answerUser("CTX", "Q?"))
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
