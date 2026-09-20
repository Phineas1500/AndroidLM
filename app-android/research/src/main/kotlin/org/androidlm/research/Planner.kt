package org.androidlm.research

/** Which pipeline a question takes (rag.py `--mode auto`). */
enum class Route {
    /** rag.py "plan": retrieve first, the model answers with the sources in context. */
    RETRIEVAL_FIRST,

    /** rag.py "verify": the model answers from its own knowledge, then a source check follows. */
    ANSWER_FIRST,
}

/**
 * [views] is null when the first planned title did not resolve. [travel] is true when the
 * optional travel route decided (rag.py `--travel-route`): the route is then retrieval-first
 * whatever [views] says.
 */
data class RouteDecision(val route: Route, val views: Long?, val travel: Boolean = false)

object Planner {
    const val PLAN_MAX_TOKENS = 60
    const val MAX_TITLES = 4
    const val DEFAULT_ROUTE_VIEWS = 5000L

    /**
     * Titles from the planning model's raw output (rag.py `plan`): per line, drop leading list
     * markers (see [cleanLine]), strip whitespace, then strip double
     * quotes; keep titles with 1 < length < 80 code points, at most four.
     */
    fun parsePlanOutput(text: String): List<String> =
        Py.splitLines(text).map { cleanLine(it) }.filter { Py.len(it) in 2..79 }.take(MAX_TITLES)

    /**
     * `LIST_MARKER.sub("", line).strip().strip('"')` with
     * `LIST_MARKER = ^\s*(?:[-*\u2022]\s+|\d{1,2}[.)]\s+)?`: a list marker is a bullet, or one or
     * two digits followed by "." or ")". Anything else that starts with digits is part of the
     * title ("1983 Harrods bombing"). `\s` and `\d` are Python's Unicode classes.
     */
    private fun cleanLine(line: String): String {
        val cps = line.codePoints().toArray()
        var i = 0
        while (i < cps.size && Py.isSpace(cps[i])) i++
        fun isDigit(cp: Int) = Character.getType(cp).toByte() == Character.DECIMAL_DIGIT_NUMBER
        fun skipSpaces(from: Int): Int {
            var j = from
            while (j < cps.size && Py.isSpace(cps[j])) j++
            return j
        }
        var j = i
        if (j < cps.size && (cps[j] == '-'.code || cps[j] == '*'.code || cps[j] == 0x2022)) {
            val k = skipSpaces(j + 1)
            if (k > j + 1) i = k
        } else {
            while (j < cps.size && j - i < 2 && isDigit(cps[j])) j++
            if (j > i && j < cps.size && (cps[j] == '.'.code || cps[j] == ')'.code)) {
                val k = skipSpaces(j + 1)
                if (k > j + 1) i = k
            }
        }
        val rest = String(cps, i, cps.size - i)
        return Py.strip(rest).trim('"')
    }

    /**
     * The router rule: retrieval-first when the subject article is known and little read (below
     * [routeViews] monthly views, where the model's own draft is usually fabricated), otherwise
     * answer-first. `views == null` means the first planned title did not resolve.
     */
    fun routeFor(views: Long?, routeViews: Long = DEFAULT_ROUTE_VIEWS): Route =
        if (views != null && views < routeViews) Route.RETRIEVAL_FIRST else Route.ANSWER_FIRST

    /**
     * Router decision for planned [titles]: the FIRST title is resolved (fuzzy allowed) and its views decide.
     *
     * With [travelRoute] (rag.py `--travel-route`, off by default) a travel question about a place
     * that has a travel guide goes retrieval-first regardless of views: [voyage] is present, the
     * first planned title resolves in it (fuzzy allowed), and one of the [question]'s stems (from
     * [corpus], as in Python) is in rag.py's TRAVEL_STEMS. Without the flag, [voyage] and
     * [question] are not looked at.
     */
    fun route(
        corpus: Corpus,
        titles: List<String>,
        routeViews: Long = DEFAULT_ROUTE_VIEWS,
        question: String = "",
        voyage: Corpus? = null,
        travelRoute: Boolean = false,
    ): RouteDecision {
        val aid = titles.firstOrNull()?.let { corpus.resolveTitle(it) }
        // Python tests `if aid`, so article id 0 counts as unresolved too
        val views = if (aid != null && aid != 0L) corpus.views(aid) else null
        // (the guide lookup tests `is not None`, so a guide with id 0 counts as resolved)
        if (travelRoute && voyage != null && titles.isNotEmpty() && voyage.resolveTitle(titles[0]) != null &&
            corpus.stems(question).any { it.stem in Lexicon.TRAVEL_STEMS }
        ) {
            return RouteDecision(Route.RETRIEVAL_FIRST, views, travel = true)
        }
        return RouteDecision(routeFor(views, routeViews), views)
    }
}
