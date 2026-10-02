package org.androidlm.research

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * SetupFiles.ALL is assets/manifest.json (what install.sh downloads and checks), and the import's
 * copy keeps only a file of the right size and SHA-256.
 */
class SetupFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sameAsManifest() {
        // the repository's assets/ (a build copy outside git carries it at the same place)
        val manifest = File("../../assets/manifest.json")
        assertTrue("no ${manifest.absolutePath}", manifest.isFile)
        val files = JsonParser.parseString(manifest.readText()).asJsonObject.getAsJsonArray("files").map { it.asJsonObject }
        assertEquals(files.map { it.get("name").asString }, SetupFiles.ALL.map { it.name })
        for ((m, f) in files.zip(SetupFiles.ALL)) {
            assertEquals(m.get("role").asString, f.role)
            assertEquals(m.get("bytes").asLong, f.bytes)
            assertEquals(m.get("sha256").asString, f.sha256)
            assertEquals(m.get("url").asString, f.url)
            val path = m.get("device_path").asString
            assertEquals(f.name, if (f.role == "model") "models" else path.substringBeforeLast('/'), f.dir)
        }
        assertEquals(listOf("Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf", "wiki.db"), SetupFiles.ALL.filter { it.required }.map { it.name })
        assertEquals("sizes tell the files apart", SetupFiles.ALL.size, SetupFiles.ALL.map { it.bytes }.toSet().size)
    }

    @Test fun identifiesByNameOrSize() {
        val wiki = SetupFiles.byName("wiki.db")!!
        assertEquals(wiki, SetupFiles.identify("wiki.db", -1))
        assertEquals(wiki, SetupFiles.identify("wiki (1).db", wiki.bytes))
        assertEquals(null, SetupFiles.identify("wiki (1).db", wiki.bytes - 1))
        assertEquals(null, SetupFiles.identify("notes.txt", 12))
    }

    private val data = ByteArray(10_000_019) { (it * 31 + 7).toByte() }
    private val good = SetupFile("t.db", "corpus", data.size.toLong(), sha(data), "", "corpus", "test")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun copiesAVerifiedFile() {
        val dest = File(tmp.root, "t.db")
        var last = 0L
        SetupFiles.copyVerified(ByteArrayInputStream(data), good, dest, onProgress = { last = it })
        assertTrue(dest.readBytes().contentEquals(data))
        assertEquals(data.size.toLong(), last)
        assertEquals(good.sha256, File(dest.path + SetupFiles.STAMP).readText())
        assertFalse(File(dest.path + SetupFiles.PART).exists())
        assertTrue(SetupFiles.matches(dest, good))
    }

    @Test fun refusesADamagedFile() {
        val bad = data.copyOf().also { it[123_456] = (it[123_456] + 1).toByte() }
        expectRefused(bad, "SHA-256")
    }

    @Test fun refusesAShortOrLongFile() {
        expectRefused(data.copyOf(data.size - 1), "bytes")
        expectRefused(data + byteArrayOf(0), "larger")
    }

    private fun expectRefused(bytes: ByteArray, why: String) {
        val dest = File(tmp.root, "t.db")
        try {
            SetupFiles.copyVerified(ByteArrayInputStream(bytes), good, dest)
            fail("accepted a wrong file")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains(why))
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + SetupFiles.PART).exists())
    }

    @Test fun cancelLeavesNothing() {
        val dest = File(tmp.root, "t.db")
        var reads = 0
        try {
            SetupFiles.copyVerified(ByteArrayInputStream(data), good, dest, cancelled = { ++reads > 1 })
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(dest.exists())
        assertFalse(File(dest.path + SetupFiles.PART).exists())
    }

    @Test fun matchesBySizeAndStamp() {
        val f = File(tmp.root, "t.db").apply { writeBytes(data) }
        assertTrue("an adb-pushed file has no stamp", SetupFiles.matches(f, good))
        File(f.path + SetupFiles.STAMP).writeText("0".repeat(64))
        assertFalse("an imported file of another version", SetupFiles.matches(f, good))
        assertFalse(SetupFiles.matches(f, good.copy(bytes = good.bytes + 1)))
    }
}
