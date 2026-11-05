package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Compact, key-sorted JSON for `toString()` of arrays / objects — the output of iOS
 * `JSONSerialization` with `.sortedKeys, .withoutEscapingSlashes`: no spaces, `/` unescaped,
 * booleans stay `true` / `false`, nil / null → `null`, doubles as `%.17g`.
 */
internal object CompactJson {

    /** `null` when [value] holds something JSON can't encode (NaN, infinity, non-string keys, …). */
    fun encode(value: Any?): String? {
        val sb = StringBuilder()
        return if (write(value, sb)) sb.toString() else null
    }

    private fun write(value: Any?, sb: StringBuilder): Boolean {
        when {
            value == null || value === Values.Null -> sb.append("null")
            value is Boolean -> sb.append(if (value) "true" else "false")
            value is String -> quote(value, sb)
            Values.isInt(value) -> sb.append((value as Number).toLong())
            value is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) return false
                sb.append(number(d))
            }
            value is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, element ->
                    if (i > 0) sb.append(',')
                    if (!write(element, sb)) return false
                }
                sb.append(']')
            }
            value is Map<*, *> -> {
                if (value.keys.any { it !is String }) return false
                sb.append('{')
                value.keys.map { it as String }.sorted().forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    quote(key, sb)
                    sb.append(':')
                    if (!write(value[key], sb)) return false
                }
                sb.append('}')
            }
            else -> return false
        }
        return true
    }

    /**
     * Foundation writes doubles as C `%.17g`: 17 significant digits, trailing zeros dropped,
     * exponent form below 1e-4 or from 1e17 (`0.1` → `0.10000000000000001`, `2.0` → `2`,
     * `1e20` → `1e+20`, `1e-7` → `9.9999999999999995e-08`).
     */
    internal fun number(d: Double): String {
        if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
        val bd = BigDecimal(d).round(MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val exponent = digits.length - bd.scale() - 1
        val sign = if (bd.signum() < 0) "-" else ""
        if (exponent < -4 || exponent >= 17) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val expAbs = abs(exponent)
            return sign + mantissa + "e" + (if (exponent < 0) "-" else "+") + (if (expAbs < 10) "0$expAbs" else "$expAbs")
        }
        return sign + bd.abs().toPlainString()
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
