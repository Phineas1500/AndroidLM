package org.androidlm.research.android

import android.content.Context
import org.androidlm.research.Corpus
import org.androidlm.research.CorpusProvider
import org.androidlm.research.Pack
import org.androidlm.research.Places
import java.io.File
import java.security.MessageDigest

/**
 * The corpus databases found on the device: [wiki] is what research mode needs, [voyage] (the
 * travel guide), [places] (where to eat, drink and stay) and [pack] (the Ethereum and
 * cryptography library) are optional.
 */
data class CorpusFiles(val wiki: File, val voyage: File?, val places: File? = null, val pack: File? = null) {
    fun label(): String = listOfNotNull(wiki, voyage, places, pack).joinToString(" + ") { it.name }
}

/**
 * The Ethereum and cryptography pack ships inside the APK (assets/ethereum.db, about 23 MB, with
 * its SHA-256 in assets/ethereum.db.sha256). SQLite needs a file, so the first use copies it to
 * files/corpus/, and copies it again when the APK carries a different one.
 */
object BundledPack {
    private const val SHA = Pack.FILE + ".sha256"

    /** Blocking: call off the main thread. The pack's file, or null when the APK has none. */
    @Synchronized
    fun file(ctx: Context): File? {
        val sha = runCatching { ctx.assets.open(SHA).use { String(it.readBytes()).trim() } }.getOrNull() ?: return null
        val dir = File(ctx.filesDir, CorpusLocator.DIR).apply { mkdirs() }
        val out = File(dir, Pack.FILE)
        val stamp = File(dir, SHA)
        if (out.isFile && stamp.isFile && stamp.readText().trim() == sha) return out
        val tmp = File(dir, Pack.FILE + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        ctx.assets.open(Pack.FILE).use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != sha) {
            tmp.delete()
            return null
        }
        if (!tmp.renameTo(out)) return null
        stamp.writeText(sha)
        return out
    }
}

/**
 * Finds the retrieval corpus. It is looked for in a `corpus` directory under the roots the model
 * scan uses: the app's files dir, /data/local/tmp/androidlm (what adb pushes there is readable
 * without any permission, provided the mode bits allow it; upstream's /data/local/tmp/bmoe is
 * accepted too), and the app-specific external files dir. The databases are tens of GB and are
 * opened in place, read-only; nothing is copied. The first readable file of each name wins.
 */
object CorpusLocator {
    const val DIR = "corpus"
    const val WIKI = "wiki.db"
    const val VOYAGE = "voyage.db"
    const val PLACES = "places.db"
    const val PACK = Pack.FILE

    private val TMP_ROOTS = listOf(File("/data/local/tmp/androidlm"), File("/data/local/tmp/bmoe"))

    fun dirs(ctx: Context): List<File> = buildList {
        add(File(ctx.filesDir, DIR))
        TMP_ROOTS.forEach { add(File(it, DIR)) }
        ctx.getExternalFilesDir(null)?.let { add(File(it, DIR)) }
    }

    /** Blocking stats: call off the main thread. Null when there is no readable wiki.db. */
    fun find(ctx: Context): CorpusFiles? {
        val dirs = dirs(ctx)
        fun first(name: String) = dirs.map { File(it, name) }.firstOrNull { it.isFile && it.canRead() }
        val wiki = first(WIKI) ?: return null
        // the APK's own pack first: it is the one this version of the app was built and tested with
        val pack = runCatching { BundledPack.file(ctx) }.getOrNull() ?: first(PACK)
        return CorpusFiles(wiki, first(VOYAGE), first(PLACES), pack)
    }

    /** Where to put the files, for the screen that says research mode is unavailable. */
    fun hint(ctx: Context): String {
        val external = ctx.getExternalFilesDir(null)?.let { File(it, DIR).path }
        return buildString {
            append("Research mode needs the offline Wikipedia corpus: $WIKI (and optionally $VOYAGE and $PLACES) in a ")
            append("\"$DIR\" directory. Import it with Set up above, or push it with adb and tap Refresh:\n")
            append("adb shell mkdir -p ${TMP_ROOTS[0].path}/$DIR\n")
            append("adb push $WIKI $VOYAGE $PLACES ${TMP_ROOTS[0].path}/$DIR/\n")
            append("adb shell chmod -R a+rX ${TMP_ROOTS[0].path}")
            if (external != null) append("\nor: adb push $WIKI $VOYAGE $PLACES $external/")
        }
    }
}

/**
 * The open corpora of one research session. A Corpus owns scratch tables on its connection and
 * is not thread-safe, so EVERY call here, [close] included, must come from the one corpus
 * thread. The databases are opened on first use and stay open until [close].
 */
class AndroidCorpora(private val files: CorpusFiles) : CorpusProvider, AutoCloseable {
    private val zstd = AndroidZstd()
    // written on the corpus thread, read by interrupt() from any thread
    private val databases = java.util.concurrent.CopyOnWriteArrayList<AndroidSqlDatabase>()
    private var wiki: Corpus? = null
    private var voyage: Corpus? = null
    private var places: Places? = null
    private var pack: Corpus? = null

    private fun open(f: File): Corpus {
        val db = AndroidSqlDatabase(f)
        databases.add(db)
        // the word-count file next to it (wiki_df.db), when installed; the search is the same without it
        val counts = File(Corpus.wordCountsPath(f.path)).takeIf { it.isFile && it.canRead() }
        return Corpus(db, zstd, counts?.path)
    }

    override fun wiki(): Corpus = wiki ?: open(files.wiki).also { wiki = it }

    override fun voyage(): Corpus? {
        val f = files.voyage ?: return null
        return voyage ?: open(f).also { voyage = it }
    }

    override fun places(): Places? {
        val f = files.places ?: return null
        return places ?: Places(AndroidSqlDatabase(f).also { databases.add(it) }).also { places = it }
    }

    override fun pack(): Corpus? {
        val f = files.pack ?: return null
        return pack ?: open(f).also { pack = it }
    }

    override fun interrupt() {
        databases.forEach { it.interrupt() }
    }

    override fun close() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
        wiki = null
        voyage = null
        places = null
        pack = null
    }
}
