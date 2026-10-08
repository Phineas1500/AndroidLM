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
            assertEquals(f.name, if (f.role.startsWith("model")) "models" else path.substringBeforeLast('/'), f.dir)
        }
        assertEquals(listOf("Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf", "wiki.db"), SetupFiles.ALL.filter { it.required }.map { it.name })
        assertEquals("sizes tell the files apart", SetupFiles.ALL.size, SetupFiles.ALL.map { it.bytes }.toSet().size)
        assertEquals(2, SetupFiles.ALL.count { it.extra })
        assertTrue("an extra is never required", SetupFiles.ALL.none { it.extra && it.required })
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

    // ---- downloads ----

    /** Serves [bytes] from [from], failing after [failAfter] bytes when set (a dropped connection). */
    private fun opened(bytes: ByteArray, from: Long, failAfter: Int = -1) = SetupFiles.Opened(
        object : java.io.InputStream() {
            var at = from.toInt()
            var sent = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (failAfter in 0..sent) throw IOException("connection reset")
                if (at >= bytes.size) return -1
                var n = minOf(len, bytes.size - at, 65_536)
                if (failAfter >= 0) n = minOf(n, failAfter - sent)
                System.arraycopy(bytes, at, b, off, n)
                at += n; sent += n
                return n
            }
        },
        from,
    )

    @Test fun downloadsAVerifiedFile() {
        val dest = File(tmp.root, "t.db")
        val got = SetupFiles.download("u", good, dest, open = { _, off -> opened(data, off) }, pause = {})
        assertTrue(got.readBytes().contentEquals(data))
        assertEquals(good.sha256, File(dest.path + SetupFiles.STAMP).readText())
        assertFalse(SetupFiles.partOf(dest).exists())
    }

    @Test fun resumesAfterADroppedConnection() {
        val dest = File(tmp.root, "t.db")
        val offsets = mutableListOf<Long>()
        SetupFiles.download("u", good, dest, open = { _, off ->
            offsets += off
            opened(data, off, failAfter = if (offsets.size < 3) 3_000_000 else -1)
        }, pause = {})
        assertTrue(dest.readBytes().contentEquals(data))
        assertEquals(listOf(0L, 3_000_000L, 6_000_000L), offsets)
    }

    @Test fun startsOverWhenTheServerIgnoresTheRange() {
        val dest = File(tmp.root, "t.db")
        SetupFiles.partOf(dest).writeBytes(data.copyOf(4_000_000))
        SetupFiles.download("u", good, dest, open = { _, _ -> opened(data, 0) }, pause = {})
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun cancelKeepsThePartForLater() {
        val dest = File(tmp.root, "t.db")
        var reads = 0
        try {
            SetupFiles.download("u", good, dest, open = { _, off -> opened(data, off) }, cancelled = { ++reads > 20 }, pause = {})
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        val part = SetupFiles.partOf(dest)
        assertTrue(part.length() in 1 until data.size)
        SetupFiles.download("u", good, dest, open = { _, off -> assertEquals(part.length(), off); opened(data, off) }, pause = {})
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun refusesADamagedDownload() {
        val bad = data.copyOf().also { it[5_000_000] = (it[5_000_000] + 1).toByte() }
        expectDownloadRefused(bad, "damaged")
        val long = data + byteArrayOf(1, 2, 3)
        expectDownloadRefused(long, "more than")
    }

    private fun expectDownloadRefused(served: ByteArray, why: String) {
        val dest = File(tmp.root, "t.db")
        try {
            SetupFiles.download("u", good, dest, open = { _, off -> opened(served, off) }, pause = {})
            fail("accepted a bad download")
        } catch (e: IOException) {
            assertTrue(e.message, why in e.message!!)
        }
        assertFalse(dest.exists())
        assertFalse(SetupFiles.partOf(dest).exists())
    }

    @Test fun givesUpAfterRetriesWithoutProgress() {
        val dest = File(tmp.root, "t.db")
        var tries = 0
        try {
            SetupFiles.download("u", good, dest, open = { _, _ -> tries++; throw IOException("no route to host") }, retries = 3, pause = {})
            fail("did not give up")
        } catch (e: IOException) {
            assertTrue(e.message, "resumes" in e.message!!)
        }
        assertEquals(4, tries)
    }

    @Test fun httpRangeAndRedirect() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/moved/t.db") { ex ->
            ex.responseHeaders.add("Location", "/files/t.db")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        var drop = true
        server.createContext("/files/t.db") { ex ->
            val m = Regex("""bytes=(\d+)-""").find(ex.requestHeaders.getFirst("Range") ?: "")
            val start = m?.groupValues?.get(1)?.toInt() ?: 0
            if (m != null) ex.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(if (m != null) 206 else 200, (data.size - start).toLong())
            ex.responseBody.use { out ->
                // the first response stops halfway, as a dropped connection does
                val end = if (drop) data.size / 2 else data.size
                drop = false
                out.write(data, start, end - start)
            }
        }
        server.start()
        try {
            val dest = File(tmp.root, "t.db")
            SetupFiles.download("http://127.0.0.1:${server.address.port}/moved/t.db", good, dest, pause = {})
            assertTrue(dest.readBytes().contentEquals(data))
        } finally {
            server.stop(0)
        }
    }
}
