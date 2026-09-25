package org.androidlm.research.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.androidlm.research.Corpus
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Where the on-device search time goes (not a correctness test): times each retrieval step on the
 * installed corpus for a few real questions, first cold, then again warm, then with larger SQLite
 * caches. Results go to logcat under the tag SearchTiming. Skipped when the corpus is not installed.
 *
 *   adb shell am instrument -w -e class org.androidlm.research.android.SearchTimingTest \
 *     io.github.phineas1500.androidlm.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SearchTimingTest {

    private class Case(val question: String, val titles: List<String>)

    private val cases = listOf(
        Case("Why does the Dead Sea have such high salinity, and why is its water level falling?",
            listOf("Dead Sea", "Evaporation", "Jordan River", "Arnon Dam")),
        Case("What happened to the Japanese used-car dealer Big Motor in 2023, who founded it, and what became of the company afterwards?",
            listOf("Big Motor", "Big Motor scandal", "Masayoshi Yamauchi", "Big Motor bankruptcy")),
        Case("What is Pink Floyd's 2022 song 'Hey, Hey, Rise Up!' based on, who sings on it, and what was the purpose of its release?",
            listOf("Hey, Hey, Rise Up!", "Pink Floyd", "Roger Waters", "The War in Ukraine")),
        Case("The scientist who discovered penicillin shared a Nobel Prize with two others. Who were they and what did each contribute?",
            listOf("Alexander Fleming", "Howard Florey", "Ernst Boris Chain", "Penicillin")),
        Case("What should I know about staying safe and healthy at altitude when travelling to Leh in Ladakh, and how should I plan my first days there?",
            listOf("Leh", "Ladakh", "Altitude sickness", "Acute mountain sickness")),
    )

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println("$TAG: $msg")
    }

    /** One pass over every case, step by step, then the whole retrieve() as the pipeline calls it. */
    private fun pass(label: String, wiki: Corpus, voyage: Corpus?) {
        var total = 0L
        for (c in cases) {
            var t = System.nanoTime()
            val stems = wiki.stems(c.question)
            val tStems = ms(t)
            t = System.nanoTime()
            val aids = c.titles.map { wiki.resolveTitle(it) }
            val tResolve = ms(t)
            t = System.nanoTime()
            aids.filterNotNull().forEach { wiki.articlePassages(it, stems) }
            val tPassages = ms(t)
            t = System.nanoTime()
            val bm = wiki.bm25(stems)
            val tBm25 = ms(t)
            t = System.nanoTime()
            val topic = wiki.stems(c.titles.joinToString(" "))
            val tTopic = ms(t)
            t = System.nanoTime()
            val hits = wiki.retrieve(c.question, c.titles, 6, voyage)
            val tRetrieve = ms(t)
            total += tRetrieve
            log("$label | ${c.titles[0]} | stems ${tStems}ms (${stems.size}) | resolve ${tResolve}ms | " +
                "passages ${tPassages}ms | bm25 ${tBm25}ms (${bm.size}) | topic stems ${tTopic}ms (${topic.size}) | " +
                "retrieve() ${tRetrieve}ms (${hits.size} hits)")
        }
        log("$label | total retrieve() ${total}ms over ${cases.size} questions")
    }

    @Test
    fun timeSearch() {
        val wikiFile = File(CORPUS_DIR, "wiki.db")
        assumeTrue("corpus not installed at $wikiFile", wikiFile.canRead())
        val voyageFile = File(CORPUS_DIR, "voyage.db").takeIf { it.canRead() }
        val zstd = AndroidZstd()

        // trivial-query overhead: one small indexed lookup, many times
        AndroidSqlDatabase(wikiFile).use { db ->
            val t = System.nanoTime()
            repeat(500) { db.query("select views from articles where id=?", (it + 1).toLong()) }
            log("overhead | 500 indexed single-row queries: ${ms(t)}ms")
        }
        AndroidSqlDatabase(wikiFile, cursorWindowBytes = 64 * 1024).use { db ->
            val t = System.nanoTime()
            repeat(500) { db.query("select views from articles where id=?", (it + 1).toLong()) }
            log("overhead | same with a 64 KB cursor window: ${ms(t)}ms")
        }

        AndroidSqlDatabase(wikiFile).use { wdb ->
            val vdb = voyageFile?.let { AndroidSqlDatabase(it) }
            val wiki = Corpus(wdb, zstd)
            val voyage = vdb?.let { Corpus(it, zstd) }
            pass("cold", wiki, voyage)
            pass("warm", wiki, voyage)
            vdb?.close()
        }

        // larger SQLite caches: page cache 64 MB, memory-mapped reads up to 2 GB
        AndroidSqlDatabase(wikiFile).use { wdb ->
            wdb.query("pragma cache_size=-65536")
            wdb.query("pragma mmap_size=2147483648")
            log("pragmas | cache_size=${wdb.query("pragma cache_size").first()[0]} mmap_size=${wdb.query("pragma mmap_size").first()[0]}")
            val vdb = voyageFile?.let { AndroidSqlDatabase(it) }
            val wiki = Corpus(wdb, zstd)
            val voyage = vdb?.let { Corpus(it, zstd) }
            pass("bigcache", wiki, voyage)
            vdb?.close()
        }
    }

    companion object {
        private const val TAG = "SearchTiming"
        private const val CORPUS_DIR = "/data/local/tmp/bmoe/corpus"
    }
}
