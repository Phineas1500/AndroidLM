package org.androidlm.research

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals

/**
 * The golden comparisons themselves, independent of where the storage comes from: the JVM
 * GoldenTest runs them over sqlite-jdbc, the app's DeviceGoldenTest over the Android storage
 * implementations. [golden] is fixtures/golden.json (written by scripts/make_golden.py from the
 * Python implementation); [wiki] and [voyage] are the two sample corpora.
 */
class GoldenChecks(private val golden: JsonArray, private val wiki: Corpus, private val voyage: Corpus) {

    /** Number of comparisons that have passed so far. */
    var checks = 0
        private set

    private fun case(i: Int): JsonObject = golden[i].asJsonObject
    private fun question(i: Int): String = case(i)["question"].asString
    private fun titles(i: Int): List<String> = case(i)["titles"].asJsonArray.map { it.asString }
    private fun ids(a: JsonArray): List<Long?> = a.map { if (it.isJsonNull) null else it.asLong }

    private fun check(i: Int, what: String, expected: Any?, actual: Any?) {
        assertEquals("case $i $what", expected, actual)
        checks++
    }

    private fun checkNear(i: Int, what: String, expected: Double, actual: Double, tolerance: Double) {
        assertEquals("case $i $what", expected, actual, tolerance)
        checks++
    }

    fun stems(i: Int) {
        val expected = case(i)["stems"].asJsonArray
        val actual = wiki.stems(question(i))
        check(i, "stems (names, in order)", expected.map { it.asJsonArray[0].asString }, actual.map { it.stem })
        expected.forEachIndexed { k, e ->
            checkNear(i, "idf of ${actual[k].stem}", e.asJsonArray[1].asDouble, actual[k].idf, 1e-3)
        }
    }

    fun queryTerms(i: Int) {
        check(i, "query_terms", case(i)["query_terms"].asJsonArray.map { it.asString }, wiki.queryTerms(wiki.stems(question(i))))
    }

    fun resolved(i: Int) {
        check(i, "resolved", ids(case(i)["resolved"].asJsonArray), titles(i).map { wiki.resolveTitle(it) })
    }

    fun resolvedVoyage(i: Int) {
        check(i, "resolved_voyage", ids(case(i)["resolved_voyage"].asJsonArray), titles(i).map { voyage.resolveTitle(it, fuzzy = false) })
    }

    fun hits(i: Int) = checkRetrieval(i, "hits", null)

    fun hitsVoyage(i: Int) = checkRetrieval(i, "hits_voyage", voyage)

    private fun checkRetrieval(i: Int, name: String, v: Corpus?) {
        val case = case(i)
        val expected = case[name].asJsonArray.map { it.asJsonObject }
        val hits = wiki.retrieve(question(i), titles(i), voyage = v)
        check(i, "$name count", expected.size, hits.size)
        expected.forEachIndexed { k, e ->
            val h = hits[k]
            check(i, "$name[$k].title", e["title"].asString, h.title)
            check(i, "$name[$k].section", e["section"].asString, h.section)
            check(i, "$name[$k].start", e["start"].asInt, h.start)
            check(i, "$name[$k].via", e["via"].asString, h.via)
            checkNear(i, "$name[$k].score", e["score"].asDouble, h.score, 0.011)
        }
        val built = buildContext(hits, 4000)
        check(i, "${name}_used", case[name + "_used"].asInt, built.usedHits.size)
        check(i, "${name}_context", case[name + "_context"].asString, built.context)
    }

    fun routeViews(i: Int) {
        val expected = case(i)["route_views"].let { if (it.isJsonNull) null else it.asLong }
        val decision = Planner.route(wiki, titles(i))
        check(i, "route_views", expected, decision.views)
        check(i, "route", if (expected != null && expected < 5000) Route.RETRIEVAL_FIRST else Route.ANSWER_FIRST, decision.route)
    }

    companion object {
        /** golden.json holds exactly this many cases. */
        const val CASES = 9

        val FIXTURE_FILES = listOf("golden.json", "sample_wiki.db", "sample_voyage.db")

        fun parse(goldenJson: String): JsonArray {
            val golden = JsonParser.parseString(goldenJson).asJsonArray
            assertEquals("number of golden cases", CASES, golden.size())
            return golden
        }
    }
}
