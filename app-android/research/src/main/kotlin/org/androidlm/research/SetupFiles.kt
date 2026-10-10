package org.androidlm.research

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * One of the large files the app reads from the phone's storage, as assets/manifest.json lists it
 * (SetupFilesTest holds the two together). [dir] is where an import puts it under the app's files
 * dir: "models" for the model (ModelManager.internalModelsDir), "corpus" for the rest
 * (CorpusLocator.DIR).
 */
data class SetupFile(
    val name: String,
    val role: String,
    val bytes: Long,
    val sha256: String,
    val url: String,
    val dir: String,
    /** What the file is for, in the words the setup screen uses. */
    val label: String,
) {
    /** Research mode needs the model and wiki.db; the rest add faster search, the travel guide and places. */
    val required: Boolean get() = role == "model" || name == SetupFiles.WIKI

    /** A part of the optional second model (Qwen3.8-Flash-Next): offered apart, never needed. */
    val extra: Boolean get() = role == "model-extra"
}

/**
 * The files of a working install, and the two ways one reaches app storage, where the engine's
 * direct reads work: an import copies a file that reached the phone some other way (the phone's
 * browser, a USB drive, adb), and a download (the app's "online" build only) fetches it from a
 * server. Either way the file counts only once its size and SHA-256 are the manifest's.
 */
object SetupFiles {
    const val WIKI = "wiki.db"

    /** Next to an imported file, its SHA-256: an import that finds the same hash there skips the file. */
    const val STAMP = ".sha256"
    const val PART = ".part"

    val ALL: List<SetupFile> = listOf(
        SetupFile(
            "Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf", "model", 12_290_628_576L,
            "96b9c0af5c77a4ecaabe3983175112b5ece763261c1ece12b2494b692a70dad7",
            "https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/main/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf",
            "models", "The model (Qwen3.6-35B-A3B)",
        ),
        SetupFile(
            WIKI, "corpus", 30_096_670_720L,
            "68af223e091ff9ca4ec3ef7a412f6cf1b835841ff19a2167f1e3185f02981204",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/v2/wiki.db",
            "corpus", "Wikipedia",
        ),
        SetupFile(
            "wiki_df.db", "corpus", 2_293_760L,
            "0a75f1f5f686c35c1d47a68eb0854d894c161627e15c05a51ff4318afbef429d",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/v2/wiki_df.db",
            "corpus", "Wikipedia word counts (faster search)",
        ),
        SetupFile(
            "voyage.db", "corpus-optional", 328_810_496L,
            "f59a2708b3c9bc3b96e2a9ded7fa082765e7ad1b62f3d302d50d0f75be7f7983",
            "https://huggingface.co/datasets/rammingaway/androidlm-corpus/resolve/main/voyage.db",
            "corpus", "Wikivoyage travel guide",
        ),
        SetupFile(
            "places.db", "places", 3_480_481_792L,
            "79037d79a6c5626cad83d3723a6350d04b3b2c3677a6a63795efc425cd1ee4b6",
            "https://huggingface.co/datasets/rammingaway/androidlm-places/resolve/main/v2/places.db",
            "corpus", "Places to eat, stay, shop and go out",
        ),
        // the optional second model, in two parts that must sit side by side; the second is its
        // n-gram table, the same file in every published build of this model
        SetupFile(
            "Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00001-of-00002.gguf", "model-extra", 47_039_860_096L,
            "219ea929900dfa9ef091f3aa473fdba6874b65fcb36526d7d851ac9e95856d15",
            "https://huggingface.co/ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF/resolve/ed59f92082b1e93c0e96d60a8b11aab089b52f09/IQ3_XXS/Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00001-of-00002.gguf",
            "models", "Larger model, part 1 of 2",
        ),
        SetupFile(
            "Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf", "model-extra", 28_800_138_432L,
            "316b46f3a2dbd68c900f43136ab9449f9dcc3725dfd8c794847c204bc161e113",
            "https://huggingface.co/ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF/resolve/ed59f92082b1e93c0e96d60a8b11aab089b52f09/IQ3_XXS/Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf",
            "models", "Larger model, part 2 of 2 (its n-gram table)",
        ),
    )

    fun byName(name: String): SetupFile? = ALL.firstOrNull { it.name == name }

    /**
     * Which file a picked document is: by its name, or by its size when a browser renamed the
     * download ("wiki (1).db"). The sizes all differ, and the copy checks the SHA-256 anyway.
     */
    fun identify(name: String, size: Long): SetupFile? = byName(name) ?: ALL.firstOrNull { it.bytes == size }

    /**
     * Copies [input] to [dest] through `dest.part`, hashing as it goes, and renames it to [dest]
     * only when its size and SHA-256 are [file]'s; then writes the stamp next to it. Otherwise the
     * part is deleted and an IOException says what was wrong. [onProgress] gets the bytes copied so
     * far; [cancelled] is polled between reads (a CancellationException then, the part deleted).
     */
    fun copyVerified(
        input: InputStream,
        file: SetupFile,
        dest: File,
        onProgress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): File {
        val part = File(dest.path + PART)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(4 shl 20)
                while (true) {
                    if (cancelled()) throw CancellationException("import cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    copied += n
                    if (copied > file.bytes) throw IOException("${file.name} is larger than ${file.bytes} bytes: not the file this app expects")
                    onProgress(copied)
                }
                out.fd.sync()
            }
            if (copied != file.bytes) throw IOException("${file.name} has $copied bytes, not ${file.bytes}: incomplete or a different version")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha != file.sha256) throw IOException("${file.name} is damaged or a different version (SHA-256 does not match)")
            if (!part.renameTo(dest)) throw IOException("could not rename ${part.name}")
            File(dest.path + STAMP).writeText(sha)
            return dest
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** Where a copy or a download keeps what it has so far; a download resumes from its length. */
    fun partOf(dest: File): File = File(dest.path + PART)

    /** An open download: the bytes, from [start] (0 when the server did not honour the range). */
    class Opened(val input: InputStream, val start: Long)

    /**
     * Opens [url] from byte [offset] with an HTTP range request. HttpURLConnection follows the
     * redirects a file host uses (Hugging Face sends its files from a CDN). A download only asks
     * for bytes it lacks, so a 416 (nothing past [offset]) means the server's file is shorter than
     * the one expected.
     */
    fun httpOpen(url: String, offset: Long): Opened {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "AndroidLM-setup")
        if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
        return when (val code = c.responseCode) {
            200 -> Opened(c.inputStream, 0)
            206 -> {
                // "bytes 1000-1999/2000": trust the server's start over the one asked for
                val start = c.getHeaderField("Content-Range")
                    ?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: offset
                Opened(c.inputStream, start)
            }
            416 -> { c.disconnect(); throw WrongFile("$url is shorter than $offset bytes: not the file this app expects") }
            else -> { c.disconnect(); throw IOException("$url: the server answered HTTP $code") }
        }
    }

    /**
     * Downloads [file] from [url] to [dest] through its part file, resuming from what an earlier
     * attempt left and retrying a dropped connection (up to [retries] times in a row without
     * progress, [pause] between tries). The finished file is then read once more and kept only if
     * its size and SHA-256 are [file]'s; otherwise the part is deleted and an IOException says so.
     * [onProgress] gets the bytes so far, [onChecking] is called when the check starts; [cancelled]
     * is polled between reads (a CancellationException then, the part kept for a later resume).
     */
    fun download(
        url: String,
        file: SetupFile,
        dest: File,
        onProgress: (Long) -> Unit = {},
        onChecking: () -> Unit = {},
        cancelled: () -> Boolean = { false },
        open: (String, Long) -> Opened = ::httpOpen,
        retries: Int = 6,
        pause: (Int) -> Unit = { Thread.sleep(RETRY_SECONDS[minOf(it, RETRY_SECONDS.size - 1)] * 1000L) },
    ): File {
        val part = partOf(dest)
        if (part.length() > file.bytes) part.delete()
        var failures = 0
        while (part.length() < file.bytes) {
            if (cancelled()) throw CancellationException("download cancelled")
            val before = part.length()
            try {
                val o = open(url, before)
                o.input.use { input ->
                    java.io.RandomAccessFile(part, "rw").use { raf ->
                        // a server that ignored the range sends the file from its start
                        raf.setLength(o.start)
                        raf.seek(o.start)
                        var at = o.start
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            if (cancelled()) throw CancellationException("download cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            if (at + n > file.bytes) throw WrongFile("${file.name}: the server sent more than ${file.bytes} bytes: not the file this app expects")
                            raf.write(buf, 0, n)
                            at += n
                            onProgress(at)
                        }
                        raf.fd.sync()
                    }
                }
                if (part.length() < file.bytes) throw IOException("the connection ended at ${part.length()} of ${file.bytes} bytes")
            } catch (e: WrongFile) {
                part.delete()
                throw IOException(e.message)
            } catch (e: IOException) {
                if (part.length() > before) failures = 0
                if (++failures > retries) throw IOException("${file.name}: the download stopped (${e.message}); Download resumes it", e)
                pause(failures - 1)
            }
        }
        onChecking()
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(4 shl 20)
            while (true) {
                if (cancelled()) throw CancellationException("download cancelled")
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != file.sha256) {
            part.delete()
            throw IOException("${file.name} arrived damaged or is a different version (SHA-256 does not match); it was deleted")
        }
        if (!part.renameTo(dest)) throw IOException("could not rename ${part.name}")
        File(dest.path + STAMP).writeText(sha)
        return dest
    }

    /** The server's file cannot be the expected one: not retried. */
    private class WrongFile(message: String) : IOException(message)

    /** Seconds between the tries of a dropped download. */
    private val RETRY_SECONDS = longArrayOf(2, 5, 10, 30, 60)

    /**
     * Is [f] [file], as far as can be told without reading it? Its size must match, and when an
     * import's stamp is next to it, the stamp too (a file adb pushed has none: install.sh checked it).
     */
    fun matches(f: File, file: SetupFile): Boolean {
        if (!f.isFile || f.length() != file.bytes) return false
        val stamp = File(f.path + STAMP)
        return !stamp.isFile || stamp.readText().trim() == file.sha256
    }
}
