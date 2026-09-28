package org.androidlm.research

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Reproduces fixtures/pack_golden.json (scripts/make_pack_golden.py, from rag.py) on the sample
 * Wikipedia and the Ethereum and cryptography pack (fixtures/sample_pack.db, a copy of
 * ethereum.db): the question's stems in the pack, the routing figures, the pack's passages, and
 * the answer's and the source check's contexts when they lead Wikipedia's; and the pack's prompts.
 * Skipped when the fixtures are absent.
 */
class PackGoldenTest {

    @Test fun prompts() {
        val asOf = Pack.asOf(pack)
        assertEquals(golden["as_of"].asString, asOf)
        assertEquals(golden["answer_system"].asString, Pack.answerSystem(asOf))
        assertEquals(golden["check_followup"].asString, Pack.checkFollowup(asOf))
        assertEquals(golden["wiki_n_indexed"].asLong, wiki.nIndexed)
    }

    @Test fun cases() {
        var n = 0
        for ((i, c) in golden["cases"].asJsonArray.map { it.asJsonObject }.withIndex()) {
            val q = c["question"].asString
            val what = "case $i ($q)"
            val ps = pack.stems(q)
            val ws = wiki.stems(q)
            assertEquals("$what pack stems", c["pack_stems"].asJsonArray.map { it.asJsonArray[0].asString }, ps.map { it.stem })
            c["pack_stems"].asJsonArray.forEachIndexed { k, e -> assertEquals("$what pack idf", e.asJsonArray[1].asDouble, ps[k].idf, 1e-5) }
            assertEquals("$what wiki stems", c["wiki_stems"].asJsonArray.map { it.asJsonArray[0].asString }, ws.map { it.stem })
            val a = Pack.affinity(ps, ws, wiki.nIndexed)
            val ea = c["affinity"]
            if (ea.isJsonNull) {
                assertEquals("$what affinity", null, a)
            } else {
                val best = ea.asJsonArray[0]
                if (best.isJsonNull) assertEquals("$what best", null, a!!.best) else assertEquals("$what best", best.asDouble, a!!.best!!, 1e-5)
                assertEquals("$what foreign", ea.asJsonArray[1].asDouble, a.foreign, 1e-5)
            }
            assertEquals("$what route", c["route"].asBoolean, Pack.routes(a))
            val hits = Pack.hits(pack, q)
            val expected = c["pack_hits"].asJsonArray.map { it.asJsonObject }
            assertEquals("$what pack hits", expected.size, hits.size)
            expected.forEachIndexed { k, e ->
                val h = hits[k]
                assertEquals("$what hit $k title", e["title"].asString, h.title)
                assertEquals("$what hit $k section", e["section"].asString, h.section)
                assertEquals("$what hit $k start", e["start"].asInt, h.start)
                assertEquals("$what hit $k via", e["via"].asString, h.via)
                assertEquals("$what hit $k score", e["score"].asDouble, h.score, 0.011)
                assertEquals("$what hit $k text", e["text"].asString, h.text)
                assertEquals("$what hit $k lead", e["lead"].asBoolean, h.lead)
                assertEquals("$what hit $k is the pack's", true, h.aid.pack)
            }
            val merged = hits + wiki.retrieve(q, c["titles"].asJsonArray.map { it.asString })
            val built = buildContext(merged, 4000)
            assertEquals("$what context used", c["context_used"].asInt, built.usedHits.size)
            assertEquals("$what context", c["context"].asString, built.context)
            if (c.has("draft")) {
                assertEquals("$what check context", c["check_context"].asString,
                    CheckContext.build(merged, c["draft"].asString, q, 2000, excerpt = false).context)
            }
            n++
        }
        println("PackGoldenTest: $n cases passed")
    }

    companion object {
        private lateinit var wikiDb: JdbcSqlDatabase
        private lateinit var packDb: JdbcSqlDatabase
        private lateinit var wiki: Corpus
        private lateinit var pack: Corpus
        private lateinit var golden: JsonObject
        private var opened = false

        @JvmStatic
        @BeforeClass
        fun open() {
            val dir = File(
                System.getProperty("ANDROIDLM_FIXTURES") ?: System.getenv("ANDROIDLM_FIXTURES")
                    ?: (System.getProperty("user.home") + "/androidlm-tools/fixtures"),
            )
            val files = listOf("pack_golden.json", "sample_wiki.db", "sample_pack.db").map { File(dir, it) }
            assumeTrue("pack golden fixtures not found in $dir", files.all { it.isFile })
            golden = JsonParser.parseString(files[0].readText(Charsets.UTF_8)).asJsonObject
            wikiDb = JdbcSqlDatabase(files[1])
            packDb = JdbcSqlDatabase(files[2])
            opened = true
            val zstd = JniZstdDecompressor()
            wiki = Corpus(wikiDb, zstd)
            pack = Corpus(packDb, zstd)
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (opened) {
                wikiDb.close()
                packDb.close()
            }
        }
    }
}
