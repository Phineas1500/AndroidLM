package org.androidlm.research

import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * [GoldenTest] again with the word-count file attached (fixtures/sample_wiki_df.db, written by
 * scripts/build_df.py with min_doc 2 so that most stems are read from it): the search must return
 * exactly what it returns without the file. Skipped when the fixtures are absent.
 */
@RunWith(Parameterized::class)
class GoldenWordCountsTest(private val caseIndex: Int) {

    @Test fun stems() = checks.stems(caseIndex)

    @Test fun queryTerms() = checks.queryTerms(caseIndex)

    @Test fun resolved() = checks.resolved(caseIndex)

    @Test fun hits() = checks.hits(caseIndex)

    @Test fun hitsVoyage() = checks.hitsVoyage(caseIndex)

    @Test fun wordCountsAttached() = assertTrue(wiki.hasWordCounts)

    /** A word-count file built from another database is not used. */
    @Test fun anotherDatabasesCountsAreRefused() {
        JdbcSqlDatabase(voyageFile).use { db ->
            assertFalse(Corpus(db, JniZstdDecompressor(), dfFile.path).hasWordCounts)
        }
    }

    companion object {
        private lateinit var wikiDb: JdbcSqlDatabase
        private lateinit var voyageDb: JdbcSqlDatabase
        private lateinit var dfFile: File
        private lateinit var voyageFile: File
        private lateinit var wiki: Corpus
        private lateinit var checks: GoldenChecks
        private var opened = false

        @JvmStatic
        @Parameterized.Parameters(name = "case{0}")
        fun cases(): List<Array<Any>> = (0 until GoldenChecks.CASES).map { arrayOf<Any>(it) }

        @JvmStatic
        @BeforeClass
        fun open() {
            val dir = File(
                System.getProperty("ANDROIDLM_FIXTURES") ?: System.getenv("ANDROIDLM_FIXTURES")
                    ?: (System.getProperty("user.home") + "/androidlm-tools/fixtures"),
            )
            val files = GoldenChecks.FIXTURE_FILES.map { File(dir, it) }
            dfFile = File(Corpus.wordCountsPath(files[1].path))
            voyageFile = files[2]
            assumeTrue("golden fixtures or $dfFile not found", files.all { it.isFile } && dfFile.isFile)
            val golden = GoldenChecks.parse(files[0].readText(Charsets.UTF_8))
            wikiDb = JdbcSqlDatabase(files[1])
            voyageDb = JdbcSqlDatabase(files[2])
            opened = true
            val zstd = JniZstdDecompressor()
            wiki = Corpus(wikiDb, zstd, dfFile.path)
            checks = GoldenChecks(golden, wiki, Corpus(voyageDb, zstd))
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (opened) {
                wikiDb.close()
                voyageDb.close()
                if (::checks.isInitialized) println("GoldenWordCountsTest: ${checks.checks} golden comparisons passed")
            }
        }
    }
}
