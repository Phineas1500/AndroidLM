package org.androidlm.research

import kotlin.math.ln
import kotlin.math.max

/**
 * The Ethereum and cryptography pack (scripts/build_pack.py): Ethereum's specifications (EIPs,
 * ERCs, consensus specs) and documentation (ethereum.org, "Upgrading Ethereum") and NIST's
 * post-quantum standards, as a corpus database of its own. Port of rag.py `pack_affinity`,
 * `pack_route`, `pack_hits` and the pack's prompts.
 *
 * A question goes to the pack when one of its words is at least e^3 = 20 times more common in the
 * pack than in Wikipedia (ethereum, eip, rollup, signature, quantum), and less than a quarter of
 * its weight is in words the pack uses no more than Wikipedia does ("passport" in "is my passport
 * still valid?", "fish" in "which fork for fish?"). A word's weight is its Wikipedia idf.
 */
object Pack {
    const val FILE = "ethereum.db"
    const val MIN_RATIO = 3.0
    const val FOREIGN_RATIO = 0.5
    const val MAX_FOREIGN = 0.25

    /** rag.py `--pack-passages`: the pack's passages ahead of Wikipedia's. */
    const val PASSAGES = 4

    /** [best]: the largest log ratio (null when no word is in the pack); [foreign]: the foreign share. */
    data class Affinity(val best: Double?, val foreign: Double)

    /**
     * rag.py `pack_affinity`: [packStems] and [wikiStems] are the question's stems in each index
     * ([Corpus.stems]), [wikiIndexed] the encyclopedia's [Corpus.nIndexed]. A word the pack has and
     * Wikipedia has not counts as if Wikipedia had it in one chunk. Null when neither knows a word.
     */
    fun affinity(packStems: List<Stem>, wikiStems: List<Stem>, wikiIndexed: Long): Affinity? {
        val ps = packStems.associate { it.stem to it.idf }
        val ws = wikiStems.associate { it.stem to it.idf }
        if (ws.isEmpty() && ps.isEmpty()) return null
        val unseen = if (wikiIndexed > 0) ln(wikiIndexed.toDouble()) else 0.0
        var best: Double? = null
        var foreign = 0.0
        var total = 0.0
        // sorted(set(ws) | set(ps)): the sums are taken in Python's order (code point order)
        for (s in (ws.keys + ps.keys).toSortedSet(CODE_POINT_ORDER)) {
            val widf = ws[s] ?: unseen
            total += widf
            val pidf = ps[s]
            if (pidf != null) {
                val lr = widf - pidf
                best = best?.let { max(it, lr) } ?: lr
                if (lr < FOREIGN_RATIO) foreign += widf
            } else {
                foreign += widf
            }
        }
        return Affinity(best, if (total != 0.0) foreign / total else 1.0)
    }

    /** rag.py `pack_route`. */
    fun routes(a: Affinity?): Boolean = a?.best != null && a.best >= MIN_RATIO && a.foreign < MAX_FOREIGN

    /**
     * rag.py `pack_terms`: the pack search's query terms, the question's words rarest in the pack
     * first, without the ones common in English, which is to say in Wikipedia (more than [maxDf] of
     * its chunks: "between", "differ", "work"). Unlike [Corpus.queryTerms], a word common only in
     * the pack stays ("signature" in a question about signatures). Fewer than [keepMin] left: the
     * [keepMin] rarest in the pack.
     */
    fun terms(packStems: List<Stem>, wikiStems: List<Stem>, maxDf: Double = 0.02, keepMin: Int = 3): List<String> {
        val ws = wikiStems.associate { it.stem to it.idf }
        val ranked = packStems.sortedWith { a, b -> Py.cmp(-a.idf, -b.idf) } // stable, like sorted()
        val minIdf = ln(1 / maxDf)
        val kept = ranked.filter { (ws[it.stem] ?: minIdf) >= minIdf }.map { it.stem }
        return if (kept.size >= keepMin) kept else ranked.take(keepMin).map { it.stem }
    }

    /**
     * rag.py `pack_hits`: the pack's best [n] passages for the question (whole-index BM25 over
     * [terms], at most two per document). A document's key facts (an EIP's status and the network
     * upgrade that shipped it) lead the first passage taken from it, which then counts as a lead
     * (the longer limit of [buildContext]). [wikiStems] are the question's stems in Wikipedia.
     */
    fun hits(pack: Corpus, question: String, wikiStems: List<Stem>, n: Int = PASSAGES): List<Hit> {
        val out = ArrayList<Hit>()
        val seen = HashSet<Long>()
        val stems = pack.stems(question)
        for (h in pack.bm25(stems, terms = terms(stems, wikiStems)).take(n)) {
            var hit = h
            val text = pack.article(h.aid.id).text.value
            if (h.aid.id !in seen && text.startsWith("Key facts:")) {
                val facts = text.split("\n\n", limit = 2)[0]
                if (!h.text.contains(facts)) hit = hit.copy(text = facts + "\n" + h.text, lead = true)
            }
            seen.add(h.aid.id)
            out.add(hit.copy(aid = ArticleRef(h.aid.id, pack = true)))
        }
        return out
    }

    /** rag.py `pack_as_of`: when the pack's sources date from (its meta `as_of`). */
    fun asOf(pack: Corpus): String = pack.meta("as_of") ?: "2026"

    /** rag.py PACK_LIBRARY. */
    const val LIBRARY: String =
        "an offline library: Ethereum's specifications (EIPs, ERCs, consensus specs) and " +
            "documentation, NIST's cryptography standards, and Wikipedia"

    /** rag.py `pack_answer_system`. */
    fun answerSystem(asOf: String): String =
        "You are an offline research assistant. Answer the question directly and completely, using " +
            "your own knowledge together with the numbered sources from $LIBRARY. The sources date " +
            "from $asOf. Cite a source like [1] where it supports a statement. Where a source gives a " +
            "specific name, number, date or status, use it rather than your memory. Ignore sources that are " +
            "off-topic. If something important is not covered by the sources, still answer it from your own " +
            "knowledge. If you are unsure of a specific name, date or number, say so instead of guessing. " +
            "Address every part of the question in the first few lines, then elaborate. No preamble, no " +
            "restating the question, no LaTeX, no visible deliberation. Be concise."

    /** rag.py `pack_check_followup`. */
    fun checkFollowup(asOf: String): String = Prompts.CHECK_FOLLOWUP.replace(
        "these numbered sources from an offline copy of Wikipedia.",
        "these numbered sources from $LIBRARY, dating from $asOf.",
    )

    private val CODE_POINT_ORDER = Comparator<String> { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return@Comparator ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        (a.length - i).compareTo(b.length - j)
    }
}
