package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of iOS `FormattingFunctionTests` (locale pinned to en_US, as on iOS). */
class FormattingFunctionTest {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator().apply { locale = Locale.forLanguageTag("en-US") }
    private val context = VariableContext(VariableStore())

    private fun evaluate(expression: String): Any? = evaluator.evaluate(parser.parse(expression), context)

    private val zone: ZoneId get() = evaluator.clock.zone
    private fun ymd(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    // formatDate

    @Test fun formatDateShort() = assertEquals("Jan 12", evaluate("{{formatDate('2026-01-12', 'short')}}"))
    @Test fun formatDateMedium() = assertEquals("Jan 12, 2026", evaluate("{{formatDate('2026-01-12', 'medium')}}"))
    @Test fun formatDateLong() = assertEquals("January 12, 2026", evaluate("{{formatDate('2026-01-12', 'long')}}"))

    @Test
    fun formatDateWeekday() {
        val result = evaluate("{{formatDate('2026-01-12', 'weekday')}}") as String
        assertTrue(result.contains("Monday"), "Expected 'Monday' in '$result'")
    }

    @Test fun formatDateWeekdayShort() = assertEquals("Mon", evaluate("{{formatDate('2026-01-12', 'weekdayShort')}}"))
    @Test fun formatDateNilInput() = assertEquals("", evaluate("{{formatDate(null, 'short')}}"))

    @Test
    fun formatDateISOWithTime() {
        evaluator.clock = Clock.system(ZoneOffset.UTC)
        assertEquals("Jan 12, 2026", evaluate("{{formatDate('2026-01-12T10:30:00Z', 'medium')}}"))
    }

    // formatTime

    @Test
    fun formatTime24h() {
        evaluator.clock = Clock.system(ZoneOffset.UTC)
        assertEquals("14:30", evaluate("{{formatTime('2026-01-12T14:30:00Z', '24h')}}"))
    }

    @Test
    fun formatTime12hContainsHoursAndMinutes() {
        val result = evaluate("{{formatTime('2026-01-12T14:30:00Z', '12h')}}") as String
        assertTrue(result.contains("30"), "Expected ':30' in '$result'")
    }

    @Test fun formatTimeNilInput() = assertEquals("", evaluate("{{formatTime(null)}}"))

    // formatDuration

    @Test fun formatDurationZero() = assertEquals("0:00", evaluate("{{formatDuration(0)}}"))
    @Test fun formatDurationSeconds() = assertEquals("0:42", evaluate("{{formatDuration(42)}}"))
    @Test fun formatDurationMinutes() = assertEquals("1:30", evaluate("{{formatDuration(90)}}"))
    @Test fun formatDurationHours() = assertEquals("1:01:01", evaluate("{{formatDuration(3661)}}"))
    @Test fun formatDurationExactHour() = assertEquals("1:00:00", evaluate("{{formatDuration(3600)}}"))

    // formatMinutes

    @Test fun formatMinutesLessThan60() = assertEquals("15 min", evaluate("{{formatMinutes(15)}}"))
    @Test fun formatMinutesSixty() = assertEquals("1h", evaluate("{{formatMinutes(60)}}"))
    @Test fun formatMinutesPartialHour() = assertEquals("1h 30min", evaluate("{{formatMinutes(90)}}"))
    @Test fun formatMinutesTwoHours() = assertEquals("2h", evaluate("{{formatMinutes(120)}}"))
    @Test fun formatMinutesZero() = assertEquals("0 min", evaluate("{{formatMinutes(0)}}"))

    // ageInYears

    @Test
    fun ageInYears() {
        val expected = ChronoUnit.YEARS.between(LocalDate.of(1990, 1, 1).atStartOfDay(zone), Instant.now().atZone(zone))
        assertEquals(expected, evaluate("{{ageInYears('1990-01-01')}}"))
    }

    @Test fun ageInYearsFutureDate() = assertEquals(0L, evaluate("{{ageInYears('2099-01-01')}}"))
    @Test fun ageInYearsInvalidInput() = assertEquals(0L, evaluate("{{ageInYears('not-a-date')}}"))

    // daysBetween

    @Test fun daysBetween() = assertEquals(5L, evaluate("{{daysBetween('2026-01-12', '2026-01-17')}}"))
    @Test fun daysBetweenSameDate() = assertEquals(0L, evaluate("{{daysBetween('2026-01-12', '2026-01-12')}}"))
    @Test fun daysBetweenReversedIsAbsolute() = assertEquals(5L, evaluate("{{daysBetween('2026-01-17', '2026-01-12')}}"))
    @Test fun daysBetweenInvalidInput() = assertEquals(0L, evaluate("{{daysBetween('bad', '2026-01-12')}}"))

    // timeAgo

    @Test
    fun timeAgoJustNow() {
        val now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        assertEquals("just now", evaluate("{{timeAgo('$now')}}"))
    }

    @Test
    fun timeAgoOldDate() {
        val result = evaluate("{{timeAgo('2000-01-01T00:00:00Z')}}") as String
        assertTrue(result.contains("days ago") || !result.contains("years"), "Expected 'days ago' in '$result'")
    }

    @Test fun timeAgoInvalidInput() = assertEquals("", evaluate("{{timeAgo('not-a-date')}}"))

    // daysUntil

    @Test
    fun daysUntilFuture() {
        val future = LocalDate.now(evaluator.clock).plusDays(5)
        assertEquals(5L, evaluate("{{daysUntil('${ymd(future)}')}}"))
    }

    @Test
    fun daysUntilPast() {
        val past = LocalDate.now(evaluator.clock).minusDays(2)
        assertEquals(-2L, evaluate("{{daysUntil('${ymd(past)}')}}"))
    }

    @Test
    fun daysUntilToday() = assertEquals(0L, evaluate("{{daysUntil('${ymd(LocalDate.now(evaluator.clock))}')}}"))

    // formatCurrency

    @Test
    fun formatCurrencyUSD() {
        val result = evaluate("{{formatCurrency(24.99)}}") as String
        assertTrue(result.contains("24"))
        assertTrue(result.contains("99"))
    }

    @Test
    fun formatCurrencyZero() {
        assertFalse((evaluate("{{formatCurrency(0)}}") as String).isEmpty())
    }

    @Test
    fun formatCurrencyWithCode() {
        assertTrue((evaluate("{{formatCurrency(24.99, 'EUR')}}") as String).contains("24"))
    }

    @Test fun formatCurrencyInvalidInput() = assertEquals("", evaluate("{{formatCurrency(null)}}"))

    // formatNumber

    @Test
    fun formatNumberDecimal() {
        val result = evaluate("{{formatNumber(6250)}}") as String
        assertTrue(result.contains("6") && result.contains("250"), "got '$result'")
    }

    @Test fun formatNumberPercent() = assertEquals("86%", evaluate("{{formatNumber(0.856, 'percent')}}"))
    @Test fun formatNumberPercentRounding() = assertEquals("100%", evaluate("{{formatNumber(0.999, 'percent')}}"))
    @Test fun formatNumberInvalidInput() = assertEquals("", evaluate("{{formatNumber(null)}}"))

    // round

    @Test
    fun roundOneArg() {
        assertEquals(5L, evaluate("{{round(4.7)}}"))
        assertEquals(4L, evaluate("{{round(4.2)}}"))
    }

    @Test fun roundTwoArgsOneDecimal() = assertEquals(4.6, evaluate("{{round(4.567, 1)}}") as Double, 0.0001)
    @Test fun roundTwoArgsTwoDecimals() = assertEquals(4.57, evaluate("{{round(4.567, 2)}}") as Double, 0.0001)
    @Test fun roundTwoArgsZeroDecimals() = assertEquals(5.0, (evaluate("{{round(4.7, 0)}}") as Number).toDouble(), 0.0001)

    // plural

    @Test fun pluralSingular() = assertEquals("1 glass", evaluate("{{plural(1, 'glass', 'glasses')}}"))
    @Test fun pluralPlural() = assertEquals("5 glasses", evaluate("{{plural(5, 'glass', 'glasses')}}"))
    @Test fun pluralZero() = assertEquals("0 items", evaluate("{{plural(0, 'item', 'items')}}"))
    @Test fun pluralLargeNumber() = assertEquals("100 cards left", evaluate("{{plural(100, 'card left', 'cards left')}}"))

    // region Additional coverage (not in the Swift suite)

    @Test
    fun time_relative_functions_with_fixed_clock() {
        evaluator.clock = Clock.fixed(Instant.parse("2026-01-12T12:00:00Z"), ZoneOffset.UTC)
        assertEquals("just now", evaluate("timeAgo('2026-01-12T11:59:01Z')"))
        assertEquals("1 minute ago", evaluate("timeAgo('2026-01-12T11:59:00Z')"))
        assertEquals("59 minutes ago", evaluate("timeAgo('2026-01-12T11:00:01Z')"))
        assertEquals("1 hour ago", evaluate("timeAgo('2026-01-12T11:00:00Z')"))
        assertEquals("23 hours ago", evaluate("timeAgo('2026-01-11T12:00:01Z')"))
        assertEquals("yesterday", evaluate("timeAgo('2026-01-11T12:00:00Z')"))
        assertEquals("2 days ago", evaluate("timeAgo('2026-01-10T12:00:00Z')"))
        assertEquals("just now", evaluate("timeAgo('2026-02-01T00:00:00Z')"))
        assertEquals(36L, evaluate("ageInYears('1990-01-12')"))
        assertEquals(35L, evaluate("ageInYears('1990-01-13')"))
        assertEquals(3L, evaluate("daysUntil('2026-01-15T23:00:00Z')"))
    }

    @Test
    fun date_only_strings_are_local_midnight_in_the_clock_zone() {
        evaluator.clock = Clock.fixed(Instant.parse("2026-01-12T12:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals("Jan 12, 2026", evaluate("formatDate('2026-01-12')"))
        // An instant late in the UTC day is the previous local day in New York.
        assertEquals("Jan 11, 2026", evaluate("formatDate('2026-01-12T03:00:00Z')"))
    }

    @Test
    fun currency_formatting_matches_icu_for_en_us() {
        assertEquals("$1,234.50", evaluate("formatCurrency(1234.5)"))
        assertEquals("¥1,234", evaluate("formatCurrency(1234.5, 'JPY')"))
        assertEquals("CHF 1,234.50", evaluate("formatCurrency(1234.5, 'CHF')"))
        assertEquals("BHD 1,234.500", evaluate("formatCurrency(1234.5, 'BHD')"))
        assertEquals("$1.12", evaluate("formatCurrency(1.125)"))
        assertEquals("$0.02", evaluate("formatCurrency(0.015)"))
        assertEquals("NaN", evaluate("formatCurrency('nan')"))
    }

    @Test
    fun other_locales_use_cldr_formats() {
        evaluator.locale = Locale.GERMANY
        assertEquals("1.234,50 €", evaluate("formatCurrency(1234.5, 'EUR')"))
        assertEquals("1.234,5", evaluate("formatNumber(1234.5)"))
    }

    @Test
    fun invalid_custom_date_pattern_yields_empty_string() {
        assertEquals("", evaluate("formatDate('2026-01-12', 'foo')"))
    }

    // endregion
}
