package org.androidlm.research.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.androidlm.research.Corpus
import org.androidlm.research.GoldenChecks
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * research's GoldenTest, replayed on a device over [AndroidSqlDatabase] and [AndroidZstd]: the
 * same comparisons ([GoldenChecks], from research's testFixtures) against the same golden.json.
 *
 * Fixtures (golden.json, sample_wiki.db, sample_voyage.db) are looked for in, in this order:
 *  1. the instrumentation argument `fixturesDir`, when given;
 *  2. /data/local/tmp/androidlm-fixtures (adb push; survives the uninstall that ends a
 *     connected test run; needs no permission, but the directory must be o+rx, the files o+r);
 *  3. <external files dir>/fixtures, i.e. /sdcard/Android/data/<applicationId>/files/fixtures.
 * All tests are skipped when no directory has all three files. See research/DEVICE_TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class DeviceGoldenTest {

    private fun eachCase(check: GoldenChecks.(Int) -> Unit) {
        for (i in 0 until GoldenChecks.CASES) checks.check(i)
    }

    @Test fun stems() = eachCase { stems(it) }

    @Test fun queryTerms() = eachCase { queryTerms(it) }

    @Test fun resolved() = eachCase { resolved(it) }

    @Test fun resolvedVoyage() = eachCase { resolvedVoyage(it) }

    @Test fun hits() = eachCase { hits(it) }

    @Test fun hitsVoyage() = eachCase { hitsVoyage(it) }

    @Test fun routeViews() = eachCase { routeViews(it) }

    /** The bundled SQLite is the one in use, and the main database really is read-only. */
    @Test fun mainDatabaseIsReadOnly() {
        val version = wikiDb.sqliteVersion()
        val (major, minor) = version.split(".").map { it.toInt() }
        assertTrue("bundled SQLite (3.49 or later) expected, got $version", major > 3 || (major == 3 && minor >= 49))
        assertThrows(Exception::class.java) { wikiDb.exec("create table main.androidlm_probe(x)") }
        assertEquals(emptyList<Array<Any?>>(), wikiDb.query("select 1 from sqlite_master where name='androidlm_probe'"))
    }

    /** The largest compressed block comes back whole through the cursor window and inflates. */
    @Test fun largestBlockReadsFully() {
        val (id, length) = wikiDb.query("select id, length(zdata) from blocks order by length(zdata) desc limit 1").first()
        val zdata = wikiDb.query("select zdata from blocks where id=?", id).first()[0] as ByteArray
        assertEquals(length, zdata.size.toLong())
        assertTrue(AndroidZstd().decompress(zdata).isNotEmpty())
    }

    companion object {
        private const val ADB_DIR = "/data/local/tmp/androidlm-fixtures"

        private lateinit var wikiDb: AndroidSqlDatabase
        private lateinit var voyageDb: AndroidSqlDatabase
        private lateinit var checks: GoldenChecks

        @JvmStatic
        @BeforeClass
        fun open() {
            val candidates = listOfNotNull(
                InstrumentationRegistry.getArguments().getString("fixturesDir")?.let { File(it) },
                File(ADB_DIR),
                InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)?.let { File(it, "fixtures") },
            )
            val dir = candidates.firstOrNull { d -> GoldenChecks.FIXTURE_FILES.all { File(d, it).canRead() } }
            assumeTrue("golden fixtures not found (or not readable) in any of $candidates", dir != null)
            val files = GoldenChecks.FIXTURE_FILES.map { File(dir, it) }
            val golden = GoldenChecks.parse(files[0].readText(Charsets.UTF_8))
            wikiDb = AndroidSqlDatabase(files[1])
            voyageDb = AndroidSqlDatabase(files[2])
            val zstd = AndroidZstd()
            checks = GoldenChecks(golden, Corpus(wikiDb, zstd), Corpus(voyageDb, zstd))
        }

        @JvmStatic
        @AfterClass
        fun close() {
            if (::wikiDb.isInitialized) wikiDb.close()
            if (::voyageDb.isInitialized) voyageDb.close()
            if (::checks.isInitialized) println("DeviceGoldenTest: ${checks.checks} golden comparisons passed")
        }
    }
}
