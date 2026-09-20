package org.androidlm.research

import org.junit.AfterClass
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
 * The comparisons live in [GoldenChecks] (testFixtures), shared with the app's DeviceGoldenTest.
 */
@RunWith(Parameterized::class)
class GoldenTest(private val caseIndex: Int) {

    @Test fun stems() = checks.stems(caseIndex)

    @Test fun queryTerms() = checks.queryTerms(caseIndex)

    @Test fun resolved() = checks.resolved(caseIndex)

    @Test fun resolvedVoyage() = checks.resolvedVoyage(caseIndex)

    @Test fun hits() = checks.hits(caseIndex)

    @Test fun hitsVoyage() = checks.hitsVoyage(caseIndex)

    @Test fun routeViews() = checks.routeViews(caseIndex)

    companion object {
        private lateinit var wikiDb: JdbcSqlDatabase
        private lateinit var voyageDb: JdbcSqlDatabase
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
            assumeTrue("golden fixtures not found in $dir", files.all { it.isFile })
            val golden = GoldenChecks.parse(files[0].readText(Charsets.UTF_8))
            wikiDb = JdbcSqlDatabase(files[1])
            voyageDb = JdbcSqlDatabase(files[2])
            opened = true
            val zstd = JniZstdDecompressor()
            checks = GoldenChecks(golden, Corpus(wikiDb, zstd), Corpus(voyageDb, zstd))
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (opened) {
                wikiDb.close()
                voyageDb.close()
                if (::checks.isInitialized) println("GoldenTest: ${checks.checks} golden comparisons passed")
            }
        }
    }
}
