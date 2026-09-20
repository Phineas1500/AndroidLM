package org.androidlm.research

import java.util.regex.Pattern
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/** A porter stem of a question word with its inverse document frequency in the index. */
data class Stem(val stem: String, val idf: Double)

/**
 * Article identity inside one retrieval: Python uses the bare id for the encyclopedia and the
 * tuple `("v", id)` for the travel guide, so that equal ids in the two databases stay apart.
 */
data class ArticleRef(val id: Long, val voyage: Boolean = false)

/**
 * One retrieved passage (rag.py's hit dict). [start] is the chunk's offset in CODE POINTS into
 * the article text, exactly as stored in the corpus; [score] is rounded to two decimals as in
 * Python; [lead] marks an article's lead passage, which [buildContext] never trims.
 * Equality is structural on all fields, like the Python dict comparison `retrieve` relies on.
 */
data class Hit(
    val aid: ArticleRef,
    val start: Int,
    val title: String,
    val section: String,
    val text: String,
    val score: Double,
    val via: String,
    val lead: Boolean = false,
) {
    /** The source line rag.py reports: `"{title} — {section} ({via})"`. */
    fun sourceLabel(): String = "$title — $section ($via)"
}

class Article(val title: String, val views: Long, val text: PyText)

/**
 * Port of rag.py `class Corpus`: planned-title lookup plus gated BM25 over one corpus database
 * (schema at the top of scripts/build_corpus.py, optional `redirects` table from
 * scripts/build_redirects.py). Not thread-safe: it owns scratch tables on the connection.
 */
class Corpus(private val db: SqlDatabase, private val zstd: ZstdDecompressor) {
    /** max(chunks.id), standing in for the number of indexed chunks (as in Python). */
    val nIndexed: Long
    val hasRedirects: Boolean

    // decompressed blocks, least recently used first (Python keeps 32, evicting the oldest)
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > BLOCK_CACHE
    }

    // decoded articles: Python decodes on every call; a retrieval touches the same few repeatedly
    private val articles = object : LinkedHashMap<Long, Article>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Article>?) = size > ARTICLE_CACHE
    }

    init {
        // vocabulary views: fts_v gives each stem's document frequency, qtok stems a query
        db.exec("CREATE VIRTUAL TABLE IF NOT EXISTS temp.fts_v USING fts5vocab(main, fts, row)")
        db.exec("CREATE VIRTUAL TABLE temp.qtok USING fts5(x, tokenize='porter unicode61 remove_diacritics 2')")
        db.exec("CREATE VIRTUAL TABLE temp.qtok_v USING fts5vocab(temp, qtok, row)")
        nIndexed = db.query("select max(id) from chunks").first()[0] as Long
        hasRedirects = db.query("select 1 from sqlite_master where name='redirects'").isNotEmpty()
    }

    /** (title, views, text); text lives at a BYTE range inside a compressed block. */
    fun article(aid: Long): Article {
        articles[aid]?.let { return it }
        val row = db.query("select title, views, block_id, off, len from articles where id=?", aid).first()
        val blockId = row[2] as Long
        val off = (row[3] as Long).toInt()
        val length = (row[4] as Long).toInt()
        val block = blocks[blockId] ?: run {
            val zdata = db.query("select zdata from blocks where id=?", blockId).first()[0] as ByteArray
            zstd.decompress(zdata).also { blocks[blockId] = it }
        }
        val end = minOf(off + length, block.size) // a Python slice clamps
        val text = String(block, off, max(0, end - off), Charsets.UTF_8)
        return Article(row[0] as String, row[1] as Long, PyText(text)).also { articles[aid] = it }
    }

    /** Monthly views of an article, or null when there is no such article (the router's input). */
    fun views(aid: Long): Long? =
        db.query("select views from articles where id=?", aid).firstOrNull()?.get(0) as Long?

    /**
     * [(stem, idf)] for the content words of [text], stemmed by FTS5 itself: the text is run
     * through a scratch table with the index's tokenizer and read back from its vocabulary
     * (so the result is ordered by stem, bytewise, and has no duplicates).
     */
    fun stems(text: String): List<Stem> {
        val words = Py.alnumRuns(Py.lower(text)).filter { it !in Lexicon.STOP && Py.len(it) > 1 }
        if (words.isEmpty()) return emptyList()
        db.exec("delete from temp.qtok")
        db.exec("insert into temp.qtok(x) values(?)", words.joinToString(" "))
        val out = ArrayList<Stem>()
        for (r in db.query("select term from temp.qtok_v")) {
            val s = r[0] as String
            val row = db.query("select doc from fts_v where term=?", s).firstOrNull()
            if (row != null) out.add(Stem(s, ln(nIndexed.toDouble() / (row[0] as Long).toDouble())))
        }
        return out
    }

    /**
     * Rarest-first stems without the very common ones: a term found in more than [maxDf] of all
     * chunks is dropped as long as [keepMin] rarer terms remain.
     */
    fun queryTerms(stems: List<Stem>, maxDf: Double = 0.02, keepMin: Int = 3): List<String> {
        val ranked = stems.sortedWith { a, b -> Py.cmp(-a.idf, -b.idf) } // stable, like sorted()
        val minIdf = ln(1 / maxDf)
        val kept = ranked.filter { it.idf >= minIdf }.map { it.stem }
        return if (kept.size >= keepMin) kept else ranked.take(keepMin).map { it.stem }
    }

    /**
     * BM25-like score of one chunk inside an already chosen article, normalised to 0..1, with
     * question terms in the section heading counting double and +0.25 when the heading names
     * an aspect the question asks about. [start] and [end] are code point offsets.
     */
    fun passageScore(text: PyText, start: Int, end: Int, stems: List<Stem>): Double {
        val body = Py.lower(text.slice(start, end))
        val heading = Py.lower(sectionOf(text, start))
        val total = Py.sum(stems.map { it.idf }).let { if (it == 0.0) 1.0 else it }
        var score = 0.0
        for ((s, idf) in stems) {
            val p = prefix(s)
            val tf = Py.countAtBoundary(p, body) + 2 * Py.countAtBoundary(p, heading)
            score += idf * tf / (tf + 1.5)
        }
        score /= total
        val words = HashSet<String>()
        val m = ASCII_WORD.matcher(heading)
        while (m.find()) words.add(m.group())
        if (stems.any { (s, _) -> Lexicon.ASPECT_HEADINGS[s].orEmpty().any { it in words } }) score += 0.25
        return score
    }

    private fun hit(aid: Long, start: Int, end: Int, score: Double, via: String): Hit {
        val a = article(aid)
        return Hit(
            ArticleRef(aid), start, a.title, sectionOf(a.text, start),
            Py.strip(a.text.slice(start, end)), Py.round(score, 2), via,
        )
    }

    /**
     * Article id for a title the model proposed: exact match (case-insensitive), then the
     * redirects table, then, unless [fuzzy] is false, a title search that prefers the most-read
     * candidate and allows at most one title word beyond the proposed ones.
     */
    fun resolveTitle(title: String, fuzzy: Boolean = true): Long? {
        db.query("select id from articles where title = ? collate nocase", title).firstOrNull()
            ?.let { return it[0] as Long }
        if (hasRedirects) {
            db.query("select article_id from redirects where title = ?", title).firstOrNull()
                ?.let { return it[0] as Long }
        }
        val words = Py.alnumRuns(Py.lower(title))
        if (words.isEmpty() || !fuzzy) return null
        val match = "title:(" + words.joinToString(" AND ") { "\"$it\"" } + ")"
        var bestKey = 0.0
        var bestAid: Long? = null
        for (r in db.query(
            "select rowid from fts where fts match ? order by bm25(fts, 8.0, 0.0, 0.0) limit 30", match,
        )) {
            val aid = db.query("select article_id from chunks where id=?", r[0]).first()[0] as Long
            val row = db.query("select title, views from articles where id=?", aid).first()
            val views = row[1] as Long
            val extra = max(0, Py.alnumRuns(row[0] as String).size - words.size)
            val key = log10((10 + views).toDouble()) - 0.5 * extra
            if (extra > 1) continue // "Sultanahmet" must not land on "Sultanahmet Jail Museum Hotel"
            // Python compares the tuples (key, aid): greater key wins, then greater id
            val best = bestAid
            if (best == null || key > bestKey || (key == bestKey && aid > best)) {
                bestKey = key
                bestAid = aid
            }
        }
        return bestAid
    }

    /** Lead chunk plus the [nSections] chunks of the article that best cover the question. */
    fun articlePassages(aid: Long, stems: List<Stem>, nSections: Int = 2): List<Hit> {
        val text = article(aid).text
        val rows = db.query("select start, end from chunks where article_id=? order by id", aid)
            .map { Span((it[0] as Long).toInt(), (it[1] as Long).toInt()) }
        if (rows.isEmpty()) return emptyList()
        var lead = rows[0]
        // the first chunk is often just the infobox facts; the prose lead follows it
        if (text.slice(lead.start, lead.end).startsWith("Key facts:") && rows.size > 1) lead = rows[1]
        // sorted(..., reverse=True) over (score, start, end) tuples
        val scored = rows.filter { it != lead }
            .map { Scored(passageScore(text, it.start, it.end, stems), it) }
            .sortedWith { a, b ->
                var c = Py.cmp(b.score, a.score)
                if (c == 0) c = b.span.start.compareTo(a.span.start)
                if (c == 0) c = b.span.end.compareTo(a.span.end)
                c
            }
        val picks = listOf(Scored(1.0, lead)) + scored.take(nSections).filter { it.score > 0.15 }
        val hits = picks.map { hit(aid, it.span.start, it.span.end, it.score, "title") }
        return listOf(hits[0].copy(lead = true)) + hits.drop(1)
    }

    /** Whole-index BM25 with a popularity prior; passages below [minCoverage] are dropped. */
    fun bm25(
        stems: List<Stem>,
        pool: Int = 80,
        prior: Double = 2.0,
        perArticle: Int = 2,
        minCoverage: Double = 0.0,
    ): List<Hit> {
        val terms = queryTerms(stems)
        if (terms.isEmpty()) return emptyList()
        val rows = db.query(
            "select rowid, bm25(fts, 8.0, 3.0, 1.0) s from fts where fts match ? order by s limit ?",
            terms.joinToString(" OR ") { "\"$it\"" }, pool,
        )
        val cands = ArrayList<Cand>()
        for (r in rows) {
            val c = db.query("select article_id, start, end from chunks where id=?", r[0]).first()
            val aid = c[0] as Long
            val views = db.query("select views from articles where id=?", aid).first()[0] as Long
            val score = -(r[1] as Number).toDouble() + prior * log10((1 + views).toDouble())
            cands.add(Cand(score, aid, (c[1] as Long).toInt(), (c[2] as Long).toInt()))
        }
        // cands.sort(reverse=True) over (score, aid, start, end) tuples
        cands.sortWith { a, b ->
            var c = Py.cmp(b.score, a.score)
            if (c == 0) c = b.aid.compareTo(a.aid)
            if (c == 0) c = b.start.compareTo(a.start)
            if (c == 0) c = b.end.compareTo(a.end)
            c
        }
        val hits = ArrayList<Hit>()
        val per = HashMap<Long, Int>()
        for (c in cands) {
            if ((per[c.aid] ?: 0) >= perArticle) continue
            val h = hit(c.aid, c.start, c.end, c.score, "bm25")
            if (minCoverage != 0.0 && coverage(h.title + " " + h.text, stems) < minCoverage) continue
            per[c.aid] = (per[c.aid] ?: 0) + 1
            hits.add(h)
        }
        return hits
    }

    /**
     * Passages for the planned titles first (round-robin so every part of a comparison is
     * represented), then gated BM25 hits. With [voyage], a travel guide of the same title
     * (exact or redirect only) competes with the encyclopedia's sections on the same score and
     * its hits are titled "Wikivoyage: ...".
     */
    fun retrieve(question: String, titles: List<String>, k: Int = 6, voyage: Corpus? = null): List<Hit> {
        val stems = stems(question)
        val perTitle = ArrayList<List<Hit>>()
        val seenAid = HashSet<ArticleRef>()
        for (t in titles) {
            val aid = resolveTitle(t)
            var passages: List<Hit> = emptyList()
            if (aid != null && seenAid.add(ArticleRef(aid))) passages = articlePassages(aid, stems)
            val vaid = voyage?.resolveTitle(t, fuzzy = false)
            if (voyage != null && vaid != null && seenAid.add(ArticleRef(vaid, voyage = true))) {
                val guide = voyage.articlePassages(vaid, stems).map {
                    it.copy(title = "Wikivoyage: " + it.title, aid = ArticleRef(it.aid.id, voyage = true))
                }
                val lead = if (passages.isNotEmpty()) passages.take(1) else guide.take(1)
                val rest = (passages.drop(1) + guide.drop(1)).sortedWith { a, b -> Py.cmp(-a.score, -b.score) }
                passages = lead + rest.take(3)
            }
            if (passages.isNotEmpty()) perTitle.add(passages)
        }
        val hits = ArrayList<Hit>()
        if (perTitle.isNotEmpty()) hits.addAll(perTitle[0].take(2))
        for (rank in 0 until 3) {
            // a generator feeding extend(): each `not in hits` test sees the items added before it
            for (p in perTitle) if (p.size > rank && p[rank] !in hits) hits.add(p[rank])
        }
        val seen = hits.mapTo(HashSet()) { it.aid to it.start }
        val topic = stems(titles.joinToString(" ")).ifEmpty { stems }
        for (h in bm25(stems)) {
            if ((h.aid to h.start) !in seen && coverage(h.title + " " + h.text, topic) >= 0.5) hits.add(h)
        }
        return hits.take(max(k, perTitle.size * 2))
    }

    private data class Span(val start: Int, val end: Int)
    private class Scored(val score: Double, val span: Span)
    private class Cand(val score: Double, val aid: Long, val start: Int, val end: Int)

    companion object {
        private const val BLOCK_CACHE = 32
        private const val ARTICLE_CACHE = 8

        // re.compile(r"^(#{1,6})\s+(.*)$", re.M): UNIX_LINES makes ^ $ . treat only \n as a line end
        private val HEADING: Pattern =
            Pattern.compile("^(#{1,6})" + Py.SPACE_CLASS + "+(.*)$", Pattern.MULTILINE or Pattern.UNIX_LINES)
        private val ASCII_WORD: Pattern = Pattern.compile("[a-z]+")

        /** Surface-form prefix for a porter stem (porter turns a final y into i: energy -> energi). */
        fun prefix(stem: String): String =
            if (stem.endsWith("i") && Py.len(stem) > 3) stem.substring(0, stem.length - 1) else stem

        /** Heading path in force at code point offset [start] (chunks store offsets only). */
        fun sectionOf(text: PyText, start: Int): String {
            val path = ArrayList<String>()
            // match against text[:start] without copying it: the region end acts as end of input
            val m = HEADING.matcher(text.value).region(0, text.utf16Index(start))
            while (m.find()) {
                val level = m.group(1).length
                while (path.size > level - 1) path.removeAt(path.size - 1)
                while (path.size < level - 1) path.add("")
                path.add(Py.strip(m.group(2)))
            }
            return path.drop(1).filter { it.isNotEmpty() }.joinToString(" > ")
        }

        /** Share of the question's idf mass whose terms occur in [text]. */
        fun coverage(text: String, stems: List<Stem>): Double {
            val low = Py.lower(text)
            val total = Py.sum(stems.map { it.idf }).let { if (it == 0.0) 1.0 else it }
            return Py.sum(stems.filter { Py.countAtBoundary(prefix(it.stem), low, firstOnly = true) > 0 }.map { it.idf }) / total
        }
    }
}
