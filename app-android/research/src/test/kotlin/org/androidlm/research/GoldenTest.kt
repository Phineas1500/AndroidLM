package org.androidlm.research

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Reproduces fixtures/golden.json (written by scripts/make_golden.py from the Python
 * implementation) on the sample databases. Fixture directory: system property or environment
 * variable ANDROIDLM_FIXTURES, default ~/androidlm-tools/fixtures; skipped when absent.
 */
@RunWith(Parameterized::class)
class GoldenTest(private val caseIndex: Int) {

    private val case: JsonObject get() = golden[caseIndex].asJsonObject
    private val question: String get() = case["question"].asString
    private val titles: List<String> get() = case["titles"].asJsonArray.map { it.asString }

    private fun check(what: String, expected: Any?, actual: Any?) {
        assertEquals("case $caseIndex $what", expected, actual)
        checks++
    }

    private fun checkNear(what: String, expected: Double, actual: Double, tolerance: Double) {
        assertEquals("case $caseIndex $what", expected, actual, tolerance)
        checks++
    }

    @Test fun stems() {
        val expected = case["stems"].asJsonArray
        val actual = wiki.stems(question)
        check("stems (names, in order)", expected.map { it.asJsonArray[0].asString }, actual.map { it.stem })
        expected.forEachIndexed { i, e ->
            checkNear("idf of ${actual[i].stem}", e.asJsonArray[1].asDouble, actual[i].idf, 1e-3)
        }
    }

    @Test fun queryTerms() {
        check("query_terms", case["query_terms"].asJsonArray.map { it.asString }, wiki.queryTerms(wiki.stems(question)))
    }

    @Test fun resolved() {
        check("resolved", ids(case["resolved"].asJsonArray), titles.map { wiki.resolveTitle(it) })
    }

    @Test fun resolvedVoyage() {
        check("resolved_voyage", ids(case["resolved_voyage"].asJsonArray), titles.map { voyage.resolveTitle(it, fuzzy = false) })
    }

    @Test fun hits() = checkRetrieval("hits", null)

    @Test fun hitsVoyage() = checkRetrieval("hits_voyage", voyage)

    private fun checkRetrieval(name: String, v: Corpus?) {
        val expected = case[name].asJsonArray.map { it.asJsonObject }
        val hits = wiki.retrieve(question, titles, voyage = v)
        check("$name count", expected.size, hits.size)
        expected.forEachIndexed { i, e ->
            val h = hits[i]
            check("$name[$i].title", e["title"].asString, h.title)
            check("$name[$i].section", e["section"].asString, h.section)
            check("$name[$i].start", e["start"].asInt, h.start)
            check("$name[$i].via", e["via"].asString, h.via)
            checkNear("$name[$i].score", e["score"].asDouble, h.score, 0.011)
        }
        val built = buildContext(hits, 4000)
        check("${name}_used", case[name + "_used"].asInt, built.usedHits.size)
        check("${name}_context", case[name + "_context"].asString, built.context)
    }

    @Test fun routeViews() {
        val expected = case["route_views"].let { if (it.isJsonNull) null else it.asLong }
        val decision = Planner.route(wiki, titles)
        check("route_views", expected, decision.views)
        check("route", if (expected != null && expected < 5000) Route.RETRIEVAL_FIRST else Route.ANSWER_FIRST, decision.route)
    }

    companion object {
        private lateinit var golden: JsonArray
        private lateinit var wikiDb: JdbcSqlDatabase
        private lateinit var voyageDb: JdbcSqlDatabase
        private lateinit var wiki: Corpus
        private lateinit var voyage: Corpus
        private var opened = false
        private var checks = 0

        private fun ids(a: JsonArray): List<Long?> = a.map { if (it.isJsonNull) null else it.asLong }

        @JvmStatic
        @Parameterized.Parameters(name = "case{0}")
        fun cases(): List<Array<Any>> = (0 until 9).map { arrayOf<Any>(it) }

        @JvmStatic
        @BeforeClass
        fun open() {
            val dir = File(
                System.getProperty("ANDROIDLM_FIXTURES") ?: System.getenv("ANDROIDLM_FIXTURES")
                    ?: (System.getProperty("user.home") + "/androidlm-tools/fixtures"),
            )
            val files = listOf("golden.json", "sample_wiki.db", "sample_voyage.db").map { File(dir, it) }
            assumeTrue("golden fixtures not found in $dir", files.all { it.isFile })
            golden = JsonParser.parseString(files[0].readText(Charsets.UTF_8)).asJsonArray
            assertEquals("number of golden cases", 9, golden.size())
            wikiDb = JdbcSqlDatabase(files[1])
            voyageDb = JdbcSqlDatabase(files[2])
            opened = true
            val zstd = JniZstdDecompressor()
            wiki = Corpus(wikiDb, zstd)
            voyage = Corpus(voyageDb, zstd)
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (opened) {
                wikiDb.close()
                voyageDb.close()
                println("GoldenTest: $checks golden comparisons passed")
            }
        }
    }
}
