package dev.app8.engine.core.expr

import java.math.BigDecimal
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Re-implementations of the Swift / Foundation primitives the iOS engine relies on, so that
 * observable output (number descriptions, string lengths, numeric parsing, trimming) matches.
 */
internal object SwiftCompat {

    // region Double description (Swift `String(Double)` / `"\(d)"`)

    /**
     * Swift's shortest round-trip description: `2.0`, `0.1`, `1e+16`, `1e-05`, `nan`, `inf`.
     * Exponential form is used when the decimal exponent is `< -4` or `>= 16`.
     */
    fun doubleDescription(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d == Double.POSITIVE_INFINITY) return "inf"
        if (d == Double.NEGATIVE_INFINITY) return "-inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        // Double.toString is the shortest repr that round-trips (JDK 19+ / Android).
        val bd = BigDecimal(d.toString()).stripTrailingZeros()
        val negative = bd.signum() < 0
        val digits = bd.unscaledValue().abs().toString()
        val exponent = digits.length - bd.scale() - 1
        val sign = if (negative) "-" else ""
        if (exponent < -4 || exponent >= 16) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val expAbs = abs(exponent)
            return sign + mantissa + "e" + (if (exponent < 0) "-" else "+") + (if (expAbs < 10) "0$expAbs" else "$expAbs")
        }
        val plain = bd.abs().toPlainString()
        return sign + (if (plain.contains('.')) plain else "$plain.0")
    }

    // endregion

    // region Numeric parsing (Swift `Double(String)` / `Int(String)`)

    private val decimalRegex = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
    private val hexRegex = Regex("""([+-]?)0[xX]([0-9a-fA-F]+\.?[0-9a-fA-F]*|\.[0-9a-fA-F]+)([pP][+-]?\d+)?""")
    private val specialRegex = Regex("""([+-]?)(inf|infinity|nan(\([0-9A-Za-z_]*\))?)""", RegexOption.IGNORE_CASE)

    /**
     * Swift `Double(String)`: strict (no surrounding whitespace, ASCII digits only), accepts
     * `1e3`, `.5`, `5.`, `+5`, hex floats (`0x10`, `0x1p3`), `inf`/`infinity`/`nan` (any case).
     */
    fun parseDouble(s: String): Double? {
        if (decimalRegex.matches(s)) return s.toDouble()
        hexRegex.matchEntire(s)?.let { m ->
            val body = m.groupValues[2]
            val exp = m.groupValues[3].ifEmpty { "p0" }
            val normalizedBody = if (body.startsWith(".")) "0$body" else body
            return (m.groupValues[1] + "0x" + normalizedBody + exp).toDoubleOrNull()
        }
        specialRegex.matchEntire(s)?.let { m ->
            val negative = m.groupValues[1] == "-"
            return if (m.groupValues[2].startsWith("n", ignoreCase = true)) {
                Double.NaN
            } else if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        return null
    }

    /** Swift `Int(String)`: optional sign followed by ASCII digits only; nil on overflow. */
    fun parseInt(s: String): Long? {
        if (s.isEmpty()) return null
        val start = if (s[0] == '+' || s[0] == '-') 1 else 0
        if (start == s.length) return null
        for (i in start until s.length) if (s[i] !in '0'..'9') return null
        return s.toLongOrNull()
    }

    // endregion

    // region Characters (grapheme clusters)

    private fun isSimple(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i]
            if (c.code >= 0x80) return false
            if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') return false
        }
        return true
    }

    /** Swift `String.count` — number of extended grapheme clusters. */
    fun characterCount(s: String): Int {
        if (isSimple(s)) return s.length
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /** Swift `Array(string)` — the string split into Characters. */
    fun characters(s: String): List<String> {
        if (isSimple(s)) return s.map { it.toString() }
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(s.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    /** UTF-16 offset of the [charIndex]-th Character (clamped to the end). */
    fun utf16Offset(s: String, charIndex: Int): Int {
        if (isSimple(s)) return charIndex.coerceAtMost(s.length)
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(s)
        var pos = 0
        repeat(charIndex) {
            val next = bi.next()
            if (next == BreakIterator.DONE) return s.length
            pos = next
        }
        return pos
    }

    // endregion

    // region Whitespace (Foundation CharacterSet / Character.isWhitespace)

    /** `CharacterSet.whitespaces`: Unicode general category Zs plus tab. */
    fun isWhitespaceSetMember(cp: Int): Boolean =
        cp == '\t'.code || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    /** `CharacterSet.newlines`: U+000A–U+000D, U+0085, U+2028, U+2029. */
    fun isNewlineSetMember(cp: Int): Boolean =
        cp in 0x0A..0x0D || cp == 0x85 || cp == 0x2028 || cp == 0x2029

    /** Swift `Character.isWhitespace` (Unicode White_Space property). */
    fun isUnicodeWhiteSpace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
            cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F ||
            cp == 0x205F || cp == 0x3000

    /** `trimmingCharacters(in: .whitespaces)`. */
    fun trimWhitespaces(s: String): String = trim(s) { isWhitespaceSetMember(it) }

    /** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
    fun trimWhitespacesAndNewlines(s: String): String =
        trim(s) { isWhitespaceSetMember(it) || isNewlineSetMember(it) }

    private inline fun trim(s: String, pred: (Int) -> Boolean): String {
        var start = 0
        var end = s.length
        while (start < end) {
            val cp = s.codePointAt(start)
            if (!pred(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!pred(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    // endregion

    // region Misc

    /** Swift `Double.rounded()` — schoolbook rounding (half away from zero). */
    fun roundHalfAwayFromZero(x: Double): Double {
        if (x.isNaN() || x.isInfinite()) return x
        val t = truncate(x)
        return if (abs(x - t) >= 0.5) t + sign(x) else t
    }

    /** Swift `Int(double)` for values Swift would not trap on; saturates otherwise (Swift traps). */
    fun toIntTruncating(d: Double): Long = d.toLong()

    /** Swift `String <` — ordering by Unicode scalar values. */
    fun compareScalars(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        val aDone = i >= a.length
        val bDone = j >= b.length
        return if (aDone && bDone) 0 else if (aDone) -1 else 1
    }

    // endregion
}
