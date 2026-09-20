package org.androidlm.research

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The pieces of Python semantics that scripts/rag.py relies on and that differ from Kotlin's
 * defaults. Everything here is written against explicit Unicode categories instead of regex
 * flags, so that it behaves the same on a desktop JVM and on Android (whose regex engine is ICU).
 *
 *  - Python strings index by code point, Kotlin by UTF-16 unit: see [PyText].
 *  - `\w` in Python 3 `re` is `str.isalnum()` or `_`, i.e. categories L* and N* plus `_`.
 *    (Java's UNICODE_CHARACTER_CLASS `\w` also takes combining marks and all connector
 *    punctuation, and its look-behind cannot see a supplementary code point, so word tests
 *    and `\b` are done by hand here.)
 *  - `\s` and `str.strip()` use `str.isspace()`, which includes U+001C..U+001F and U+0085.
 */
internal object Py {
    /** `str.isalnum()`: what `[^\W_]` matches. */
    fun isAlnum(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }

    /** What `\w` matches. */
    fun isWord(cp: Int): Boolean = cp == '_'.code || isAlnum(cp)

    /** `str.isspace()`: what `\s` matches and what `strip()` removes. */
    fun isSpace(cp: Int): Boolean = when (cp) {
        in 0x09..0x0D, in 0x1C..0x1F, 0x20, 0x85, 0x2028, 0x2029 -> true
        else -> Character.getType(cp).toByte() == Character.SPACE_SEPARATOR
    }

    /** Character class equivalent to Python's `\s`, for use inside a java.util.regex pattern. */
    const val SPACE_CLASS = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\p{Zs}\\u2028\\u2029]"

    /** `str.lower()`: full Unicode lower-casing, locale independent. */
    fun lower(s: String): String = s.lowercase(java.util.Locale.ROOT)

    /** `re.findall(r"[^\W_]+", s)`. */
    fun alnumRuns(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var runStart = -1
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isAlnum(cp)) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                out.add(s.substring(runStart, i))
                runStart = -1
            }
            i += Character.charCount(cp)
        }
        if (runStart >= 0) out.add(s.substring(runStart))
        return out
    }

    /** True when `\b` matches at UTF-16 index [i] of [s]. */
    fun isBoundary(s: String, i: Int): Boolean {
        val before = i > 0 && isWord(s.codePointBefore(i))
        val after = i < s.length && isWord(s.codePointAt(i))
        return before != after
    }

    /**
     * `len(re.findall(r"\b" + re.escape(literal), s))`: non-overlapping matches, scanning left
     * to right. With [firstOnly] it stops at the first match (`re.search`), returning 0 or 1.
     */
    fun countAtBoundary(literal: String, s: String, firstOnly: Boolean = false): Int {
        var count = 0
        if (literal.isEmpty()) { // the pattern is a bare \b: one empty match per boundary
            for (i in 0..s.length) {
                if (i > 0 && i < s.length && Character.isLowSurrogate(s[i]) && Character.isHighSurrogate(s[i - 1])) continue
                if (isBoundary(s, i)) { count++; if (firstOnly) return 1 }
            }
            return count
        }
        var from = 0
        while (true) {
            val j = s.indexOf(literal, from)
            if (j < 0) return count
            if (isBoundary(s, j)) {
                count++
                if (firstOnly) return 1
                from = j + literal.length
            } else {
                from = j + 1
            }
        }
    }

    /** `str.strip()`. */
    fun strip(s: String): String = rstrip(lstrip(s))

    fun lstrip(s: String): String {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isSpace(cp)) break
            i += Character.charCount(cp)
        }
        return s.substring(i)
    }

    /** `str.rstrip()`. */
    fun rstrip(s: String): String {
        var e = s.length
        while (e > 0) {
            val cp = s.codePointBefore(e)
            if (!isSpace(cp)) break
            e -= Character.charCount(cp)
        }
        return s.substring(0, e)
    }

    /** `len(s)`: the number of code points. */
    fun len(s: String): Int = s.codePointCount(0, s.length)

    /** `str.splitlines()`. */
    fun splitLines(s: String): List<String> {
        val out = ArrayList<String>()
        var lineStart = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val isBreak = when (c.code) {
                0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x85, 0x2028, 0x2029 -> true
                else -> false
            }
            if (isBreak) {
                out.add(s.substring(lineStart, i))
                if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                lineStart = i + 1
            }
            i++
        }
        if (lineStart < s.length) out.add(s.substring(lineStart))
        return out
    }

    /**
     * Built-in `sum()` over floats. CPython 3.12+ uses Neumaier compensated summation here (a
     * plain `+=` loop differs in the last bit), and the reference outputs come from such a
     * Python; the difference can only matter exactly at a threshold or a tie.
     */
    fun sum(values: Iterable<Double>): Double {
        var total = 0.0
        var c = 0.0
        for (x in values) {
            val t = total + x
            c += if (Math.abs(total) >= Math.abs(x)) (total - t) + x else (x - t) + total
            total = t
        }
        return if (c != 0.0 && !c.isNaN() && !c.isInfinite()) total + c else total
    }

    /** `round(x, ndigits)`: round-half-even on the exact binary value, like CPython. */
    fun round(x: Double, ndigits: Int): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).toDouble()
    }

    /** Float comparison as Python does it (-0.0 == 0.0, unlike `Double.compareTo`). */
    fun cmp(a: Double, b: Double): Int = if (a < b) -1 else if (a > b) 1 else 0
}

/**
 * A string addressed by code point, as Python addresses it. Corpus chunk offsets (`start`,
 * `end`) are Python string indices, so every slice of article text goes through here.
 */
class PyText(val value: String) {
    /** `len(text)`. */
    val length: Int = value.codePointCount(0, value.length)

    // code point index -> UTF-16 index; only needed when the text has supplementary characters
    private val index: IntArray? = if (length == value.length) null else IntArray(length + 1).also { map ->
        var u = 0
        for (cp in 0 until length) {
            map[cp] = u
            u += Character.charCount(value.codePointAt(u))
        }
        map[length] = u
    }

    /** UTF-16 index of code point index [cp], clamped to the text the way a Python slice bound is. */
    fun utf16Index(cp: Int): Int {
        val c = cp.coerceIn(0, length)
        return index?.get(c) ?: c
    }

    /** `text[start:end]` for non-negative bounds. */
    fun slice(start: Int, end: Int): String {
        val a = utf16Index(start)
        val b = utf16Index(end)
        return if (b <= a) "" else value.substring(a, b)
    }

    override fun toString(): String = value
}
