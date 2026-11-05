package dev.app8.engine.core.expr

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * Foundation-formatter equivalents (`NumberFormatter`, `DateFormatter`, `ISO8601DateFormatter`)
 * built on java.text / java.time. Output matches iOS for `en_US`; other locales use the JDK's
 * CLDR data, which agrees with ICU for common cases but may differ in edge cases (e.g. time-zone
 * names in custom date patterns, rarely used pattern letters).
 */
internal object Formatting {

    private const val NBSP = ' '

    // region Numbers

    /**
     * ICU formats the shortest decimal representation of a double (not its exact binary value),
     * so 0.0125 rounds half-even to 0.012. Formatting a BigDecimal built from `toString()` matches.
     */
    private fun decimalOf(d: Double): BigDecimal = BigDecimal(d.toString())

    /** `NumberFormatter` with `.decimal` style: grouping, at most 3 fraction digits, half-even. */
    fun formatDecimal(d: Double, locale: Locale): String {
        val f = NumberFormat.getNumberInstance(locale)
        f.maximumFractionDigits = 3
        if (d.isNaN() || d.isInfinite() || d == 0.0) return f.format(d)
        return f.format(decimalOf(d))
    }

    /**
     * `NumberFormatter` with `.currency` style and optional `currencyCode`.
     * ICU semantics replicated: the code is upper-cased and truncated to 3 letters; an invalid
     * code falls back to the locale's currency; an unknown ISO code uses the code as symbol;
     * a letter symbol adjacent to a digit gets a no-break space (`CHF 1,234.50`).
     */
    fun formatCurrency(d: Double, locale: Locale, code: String?): String {
        val f = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        val normalized = code
            ?.takeIf { it.length >= 3 && it.substring(0, 3).all { c -> c in 'a'..'z' || c in 'A'..'Z' } }
            ?.substring(0, 3)
            ?.uppercase(Locale.ROOT)
        var symbol: String = f.decimalFormatSymbols.currencySymbol
        if (normalized != null) {
            val currency = runCatching { Currency.getInstance(normalized) }.getOrNull()
            if (currency != null) {
                f.currency = currency
                val digits = currency.defaultFractionDigits.takeIf { it >= 0 } ?: 2
                f.minimumFractionDigits = digits
                f.maximumFractionDigits = digits
                symbol = currency.getSymbol(locale)
            } else {
                val symbols = DecimalFormatSymbols.getInstance(locale)
                symbols.currencySymbol = normalized
                symbols.internationalCurrencySymbol = normalized
                f.decimalFormatSymbols = symbols
                f.minimumFractionDigits = 2
                f.maximumFractionDigits = 2
                symbol = normalized
            }
        }
        if (d.isNaN()) return f.decimalFormatSymbols.naN
        if (d.isInfinite()) return (if (d > 0) "+" else "-") + f.decimalFormatSymbols.infinity
        val formatted = if (d == 0.0) f.format(d) else f.format(decimalOf(d))
        return applyCurrencySpacing(formatted, symbol)
    }

    private fun applyCurrencySpacing(s: String, symbol: String): String {
        if (symbol.isEmpty()) return s
        val idx = s.indexOf(symbol)
        if (idx < 0) return s
        var out = s
        val after = idx + symbol.length
        if (after < out.length && out[after].isDigit() && symbol.last().isLetter()) {
            out = out.substring(0, after) + NBSP + out.substring(after)
        }
        if (idx > 0 && out[idx - 1].isDigit() && symbol.first().isLetter()) {
            out = out.substring(0, idx) + NBSP + out.substring(idx)
        }
        return out
    }

    // endregion

    // region Dates

    /** `DateFormatter` with an explicit `dateFormat`; returns "" for patterns java.time rejects. */
    fun formatDate(instant: Instant, pattern: String, locale: Locale, zone: ZoneId): String = try {
        DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))
    } catch (_: IllegalArgumentException) {
        ""
    } catch (_: java.time.DateTimeException) {
        ""
    }

    private val isoRegex = Regex(
        """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})""",
    )
    private val dateOnlyRegex = Regex("""\s*(\d+)-(\d{1,2})-(\d{1,2})\s*""")

    /**
     * Swift `parseDate`: ISO-8601 internet date-time (seconds required, optional fraction,
     * `Z` or `±hh:mm` / `±hhmm`), else `yyyy-MM-dd` at local midnight in [zone].
     */
    fun parseDate(value: Any?, zone: ZoneId): Instant? {
        val str = value as? String ?: return null
        isoRegex.matchEntire(str)?.let { m ->
            return try {
                val (y, mo, d, h, mi, s) = m.destructured
                val fraction = m.groupValues[7]
                val nanos = if (fraction.isEmpty()) 0 else (fraction.substring(1).take(3).padEnd(3, '0').toInt() * 1_000_000)
                val tz = m.groupValues[8]
                val offset = if (tz == "Z") ZoneOffset.UTC else ZoneOffset.of(if (tz.length == 5) tz.substring(0, 3) + ":" + tz.substring(3) else tz)
                OffsetDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt(), nanos, offset).toInstant()
            } catch (_: java.time.DateTimeException) {
                null
            }
        }
        dateOnlyRegex.matchEntire(str)?.let { m ->
            return try {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                    .atStartOfDay(zone).toInstant()
            } catch (_: java.time.DateTimeException) {
                null
            } catch (_: NumberFormatException) {
                null
            }
        }
        return null
    }

    // endregion
}
