package org.androidlm.research

/** The packed prompt context and the hits that made it in, in source-number order. */
data class BuiltContext(val context: String, val usedHits: List<Hit>)

/**
 * Port of rag.py `build_context`: pack passages into the prompt budget. Callers must report
 * `usedHits`, not `hits`, as the sources, because whatever does not fit never reaches the model.
 * A passage longer than its limit ([leadChars] for an article's lead, which carries its defining
 * facts, [passageChars] for any other) is cut at a sentence or line end; a block that does not
 * fit is skipped, since a shorter later passage may still fit.
 * All lengths are in code points, as in Python.
 */
fun buildContext(
    hits: List<Hit>, budgetChars: Int = 4000, passageChars: Int = 650, leadChars: Int = 1000,
): BuiltContext {
    val parts = ArrayList<String>()
    val usedHits = ArrayList<Hit>()
    var used = 0
    for (h in hits) {
        var text = h.text
        val limit = if (h.lead) leadChars else passageChars
        if (Py.len(text) > limit) {
            // text.rfind(x, 0, limit) searches text[:limit]
            val window = text.substring(0, text.offsetByCodePoints(0, limit))
            val cut16 = maxOf(window.lastIndexOf(". "), window.lastIndexOf("\n"))
            val cut = if (cut16 < 0) -1 else window.codePointCount(0, cut16)
            // both separators start with a one-unit character, so cut + 1 is cut16 + 1
            text = Py.rstrip(if (cut > limit / 2) window.substring(0, cut16 + 1) else window)
        }
        val head = "[${parts.size + 1}] ${h.title}" + if (h.section.isNotEmpty()) " — ${h.section}" else ""
        val block = head + "\n" + text
        val blockLen = Py.len(block)
        if (used + blockLen > budgetChars && parts.isNotEmpty()) continue
        parts.add(block)
        usedHits.add(h)
        used += blockLen
    }
    return BuiltContext(parts.joinToString("\n\n"), usedHits)
}

/**
 * Port of rag.py `check_context` and its helpers: a shorter context for the source check. The
 * check tests the draft's statements, so it reads the passages (with [excerpt], the sentences)
 * that share most with the draft's and the question's names and numbers, not six passages in
 * full; on the phone reading those is most of the check's time.
 */
object CheckContext {
    private val U = if (System.getProperty("java.vm.name") == "Dalvik") "" else "(?U)"
    private val NUMBER = Regex(U + "\\d[\\d,.]*\\d|\\d")
    private val WORD = Regex(U + "[^\\W\\d_][\\w'\u2019-]*")
    // a sentence ends after a word of two lowercase letters or a number (not "U.S." or "H. W.")
    private val SENTENCE = Regex(U + "(?<=[a-z0-9)\\]][a-z0-9%)\\]][.!?])\\s+(?=[A-Z\"\u201c(])|\\n+")

    /** rag.py `check_terms`: term -> weight (numbers 3, capitalised words 2, other words of 4+ letters 1). */
    fun terms(text: String): Map<String, Int> {
        val terms = HashMap<String, Int>()
        for (m in NUMBER.findAll(text)) {
            val t = m.value.replace(",", "").trimEnd('.')
            terms[t] = maxOf(terms[t] ?: 0, 3)
        }
        for (m in WORD.findAll(text)) {
            val w = m.value
            val low = w.lowercase(java.util.Locale.ROOT)
            val upper = Character.isUpperCase(w.codePointAt(0))
            if (low in Lexicon.STOP || (Py.len(low) < 4 && !upper)) continue
            terms[low] = maxOf(terms[low] ?: 0, if (upper) 2 else 1)
        }
        return terms
    }

    /** rag.py `check_overlap`. */
    fun overlap(text: String, terms: Map<String, Int>): Int {
        val have = terms(text).keys
        return terms.entries.sumOf { (t, w) -> if (t in have) w else 0 }
    }

    /** rag.py `check_excerpt`. */
    fun excerpt(text: String, terms: Map<String, Int>, limit: Int): String {
        val sents = SENTENCE.split(text).map { Py.strip(it) }.filter { it.isNotEmpty() }
        if (sents.isEmpty()) return text
        val scores = sents.map { overlap(it, terms) }
        val keep = sortedSetOf(0)
        var used = Py.len(sents[0])
        for (i in (1 until sents.size).sortedWith(compareBy({ -scores[it] }, { it }))) {
            if (scores[i] == 0) break
            if (used + 1 + Py.len(sents[i]) > limit) continue
            keep.add(i)
            used += 1 + Py.len(sents[i])
        }
        val out = ArrayList<String>()
        var last = -1
        for (i in keep) {
            if (last >= 0 && i != last + 1) out.add("\u2026")
            out.add(sents[i])
            last = i
        }
        return out.joinToString(" ")
    }

    /** rag.py `check_context`: [buildContext] over the passages that share most with the draft and the question first. */
    fun build(hits: List<Hit>, draft: String, question: String, budgetChars: Int, excerpt: Boolean): BuiltContext {
        val terms = HashMap(terms(draft))
        for ((t, w) in terms(question)) terms[t] = maxOf(terms[t] ?: 0, w)
        val scored = hits.mapIndexed { i, h ->
            val text = if (excerpt) excerpt(h.text, terms, if (h.lead) 500 else 400) else h.text
            Triple(-overlap(text, terms), i, h.copy(text = text))
        }
        return buildContext(scored.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }, budgetChars)
    }
}

/**
 * Port of rag.py `clean_check`: drop the model's visible second-guessing from a source check.
 * Everything from the first line that starts deliberating is cut, i.e. the first line matching
 * `\s*(?:[-*]\s*)?(?:But wait|Wait[,. ]|Hmm|Let me|Let's|Actually,|On second thought)`
 * (case-sensitive, at the start of the line, optionally after a bullet). The text is stripped
 * before and after, and the kept lines are joined with "\n" whatever separated them.
 */
fun cleanCheck(text: String): String {
    val kept = ArrayList<String>()
    for (line in Py.splitLines(Py.strip(text))) {
        if (startsDeliberating(line)) break
        kept.add(line)
    }
    return Py.strip(kept.joinToString("\n"))
}

private val DELIBERATION_STARTS =
    listOf("But wait", "Wait,", "Wait.", "Wait ", "Hmm", "Let me", "Let's", "Actually,", "On second thought")

private fun startsDeliberating(line: String): Boolean {
    // the optional groups of the pattern cannot change the outcome by backtracking: no
    // alternative starts with whitespace or a bullet
    var rest = Py.lstrip(line)
    if (rest.startsWith("-") || rest.startsWith("*")) rest = Py.lstrip(rest.substring(1))
    return DELIBERATION_STARTS.any { rest.startsWith(it) }
}
