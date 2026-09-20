package org.androidlm.research.android

import android.content.Context
import org.androidlm.research.Corpus
import org.androidlm.research.CorpusProvider
import java.io.File

/** The corpus databases found on the device: [wiki] is what research mode needs, [voyage] is optional. */
data class CorpusFiles(val wiki: File, val voyage: File?) {
    fun label(): String = if (voyage != null) "${wiki.name} + ${voyage.name}" else wiki.name
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
        return CorpusFiles(first(WIKI) ?: return null, first(VOYAGE))
    }

    /** Where to put the files, for the screen that says research mode is unavailable. */
    fun hint(ctx: Context): String {
        val external = ctx.getExternalFilesDir(null)?.let { File(it, DIR).path }
        return buildString {
            append("Research mode needs the offline Wikipedia corpus: $WIKI (and optionally $VOYAGE) in a ")
            append("\"$DIR\" directory. Push it with adb, then tap Refresh:\n")
            append("adb shell mkdir -p ${TMP_ROOTS[0].path}/$DIR\n")
            append("adb push $WIKI $VOYAGE ${TMP_ROOTS[0].path}/$DIR/\n")
            append("adb shell chmod -R a+rX ${TMP_ROOTS[0].path}")
            if (external != null) append("\nor: adb push $WIKI $VOYAGE $external/")
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
    private val databases = ArrayList<AndroidSqlDatabase>()
    private var wiki: Corpus? = null
    private var voyage: Corpus? = null

    private fun open(f: File): Corpus {
        val db = AndroidSqlDatabase(f)
        databases.add(db)
        return Corpus(db, zstd)
    }

    override fun wiki(): Corpus = wiki ?: open(files.wiki).also { wiki = it }

    override fun voyage(): Corpus? {
        val f = files.voyage ?: return null
        return voyage ?: open(f).also { voyage = it }
    }

    override fun close() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
        wiki = null
        voyage = null
    }
}
