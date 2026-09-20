package org.androidlm.research

/** The packed prompt context and the hits that made it in, in source-number order. */
data class BuiltContext(val context: String, val usedHits: List<Hit>)

/**
 * Port of rag.py `build_context`: pack passages into the prompt budget. Callers must report
 * `usedHits`, not `hits`, as the sources, because whatever does not fit never reaches the model.
 * Passages longer than [passageChars] are cut at a sentence or line end, except an article's
 * lead; a block that does not fit is skipped, since a shorter later passage may still fit.
 * All lengths are in code points, as in Python.
 */
fun buildContext(hits: List<Hit>, budgetChars: Int = 4000, passageChars: Int = 650): BuiltContext {
    val parts = ArrayList<String>()
    val usedHits = ArrayList<Hit>()
    var used = 0
    for (h in hits) {
        var text = h.text
        if (Py.len(text) > passageChars && !h.lead) {
            // text.rfind(x, 0, passage_chars) searches text[:passage_chars]
            val window = text.substring(0, text.offsetByCodePoints(0, passageChars))
            val cut16 = maxOf(window.lastIndexOf(". "), window.lastIndexOf("\n"))
            val cut = if (cut16 < 0) -1 else window.codePointCount(0, cut16)
            // both separators start with a one-unit character, so cut + 1 is cut16 + 1
            text = Py.rstrip(if (cut > passageChars / 2) window.substring(0, cut16 + 1) else window)
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
