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
