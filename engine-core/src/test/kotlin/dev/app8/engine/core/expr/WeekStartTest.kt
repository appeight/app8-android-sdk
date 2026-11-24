package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * startOfWeek / weekdayIndex (app8-dsl docs/variables.md, 0.7.16): the week starts on the
 * locale's first weekday (en-US Sunday, de-AT Monday). The shared input/output table from
 * app8-conformance (fixtures/functions/week-start.json) is kept as test resource
 * functions/week-start.json.
 */
class WeekStartTest {
    private val text = javaClass.classLoader!!.getResource("functions/week-start.json")!!.readText()
    private val table = Json.parseToJsonElement(text).jsonObject
    private val parser = ExpressionParser()

    private fun evaluator(locale: String, zone: String = "Europe/Vienna") = ExpressionEvaluator().apply {
        firstWeekdayLocale = Locale.forLanguageTag(locale)
        clock = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneId.of(zone))
    }

    private fun eval(expression: String, locale: String, zone: String = "Europe/Vienna", store: VariableStore = VariableStore()): Any? =
        evaluator(locale, zone).evaluate(parser.parse(expression), VariableContext(store))

    /** Numbers compare by value (1 == 1.0). */
    private fun normalized(v: Any?): Any? = when (v) {
        null, Values.Null -> null
        is Boolean -> v
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to normalized(x) }
        is List<*> -> v.map(::normalized)
        else -> v
    }

    @Test
    fun `en-US starts on Sunday, de-AT on Monday`() {
        assertEquals("2026-11-22", eval("{{startOfWeek('2026-11-25')}}", "en-US"))
        assertEquals(3L, eval("{{weekdayIndex('2026-11-25')}}", "en-US"))
        assertEquals("2026-11-23", eval("{{startOfWeek('2026-11-25')}}", "de-AT"))
        assertEquals(2L, eval("{{weekdayIndex('2026-11-25')}}", "de-AT"))
    }

    @Test
    fun `a date that is the first weekday starts its own week`() {
        assertEquals("2026-11-22", eval("{{startOfWeek('2026-11-22')}}", "en-US"))
        assertEquals(0L, eval("{{weekdayIndex('2026-11-22')}}", "en-US"))
        assertEquals("2026-11-23", eval("{{startOfWeek('2026-11-23')}}", "de-AT"))
        assertEquals(0L, eval("{{weekdayIndex('2026-11-23')}}", "de-AT"))
    }

    @Test
    fun `Saturday and Sunday edges`() {
        assertEquals(6L, eval("{{weekdayIndex('2026-11-28')}}", "en-US"))
        assertEquals("2026-11-29", eval("{{startOfWeek('2026-11-29')}}", "en-US"))
        assertEquals(6L, eval("{{weekdayIndex('2026-11-29')}}", "de-AT"))
        assertEquals("2026-11-23", eval("{{startOfWeek('2026-11-29')}}", "de-AT"))
    }

    @Test
    fun `across a month and a year`() {
        assertEquals("2026-11-29", eval("{{startOfWeek('2026-12-01')}}", "en-US"))
        assertEquals("2026-11-30", eval("{{startOfWeek('2026-12-01')}}", "de-AT"))
        assertEquals("2026-12-27", eval("{{startOfWeek('2027-01-01')}}", "en-US"))
        assertEquals("2026-12-28", eval("{{startOfWeek('2027-01-01')}}", "de-AT"))
    }

    @Test
    fun `date-times give the start of that day in the device zone as UTC`() {
        assertEquals("2026-11-22T23:00:00Z", eval("{{startOfWeek('2026-11-25T10:00:00Z')}}", "de-AT"))
        assertEquals("2026-11-22T05:00:00Z", eval("{{startOfWeek('2026-11-25T10:00:00Z')}}", "en-US", "America/New_York"))
        assertEquals("2026-03-29T22:00:00Z", eval("{{startOfWeek('2026-03-31T10:00:00Z')}}", "de-AT"), "across the switch to summer time")
    }

    @Test
    fun `the region decides and an fw keyword wins`() {
        assertEquals(2L, eval("{{weekdayIndex('2026-11-25')}}", "en-GB"))
        assertEquals(3L, eval("{{weekdayIndex('2026-11-25')}}", "en"), "en is en-US")
        assertEquals(2L, eval("{{weekdayIndex('2026-11-25')}}", "de"), "de is de-DE")
        assertEquals(0L, eval("{{weekdayIndex('2026-11-28')}}", "ar-EG"), "Egypt starts on Saturday")
        assertEquals(2L, eval("{{weekdayIndex('2026-11-25')}}", "en-US-u-fw-mon"))
        assertEquals(DayOfWeek.SUNDAY, Formatting.firstDayOfWeek(Locale.forLanguageTag("de-AT-u-fw-sun")))
    }

    @Test
    fun `default follows the device locale`() {
        val saved = Locale.getDefault(Locale.Category.FORMAT)
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-AT"))
            val evaluator = ExpressionEvaluator()
            assertEquals(2L, evaluator.evaluate(parser.parse("{{weekdayIndex('2026-11-25')}}"), VariableContext(VariableStore())))
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("en-US"))
            assertEquals(3L, evaluator.evaluate(parser.parse("{{weekdayIndex('2026-11-25')}}"), VariableContext(VariableStore())))
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, saved)
        }
    }

    @Test
    fun `invalid input is null and wrong arity throws`() {
        assertNull(eval("{{startOfWeek('soon')}}", "de-AT"))
        assertNull(eval("{{weekdayIndex(5)}}", "de-AT"))
        assertFailsWith<ExpressionException> { eval("{{startOfWeek()}}", "de-AT") }
        assertFailsWith<ExpressionException> { eval("{{weekdayIndex('2026-11-25', 1)}}", "de-AT") }
    }

    @Test
    fun sharedTable() {
        val store = VariableStore()
        for ((name, value) in table["variables"]!!.jsonObject) {
            val v = Values.fromJson(value)
            val type = when (v) { is List<*> -> VariableType.ARRAY; is String -> VariableType.STRING; else -> VariableType.OBJECT }
            store.defineVariable(name, VariableDefinition(type, v))
        }
        val zone = table["timeZone"]!!.jsonPrimitive.content
        val cases = table["cases"]!!.jsonArray
        assert(cases.size > 30)
        for (c in cases) {
            val expression = c.jsonObject["expression"]!!.jsonPrimitive.content
            val locale = c.jsonObject["locale"]!!.jsonPrimitive.content
            val result = eval(expression, locale, zone, store)
            assertEquals(normalized(Values.fromJson(c.jsonObject["expected"] as JsonElement)), normalized(result), "$locale: $expression")
        }
    }

    @Test
    fun vendoredTableMatchesConformance() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "app8-conformance/fixtures/functions/week-start.json")
            if (f.isFile) {
                assertEquals(Json.parseToJsonElement(f.readText()), Json.parseToJsonElement(text), "${f.path} differs: copy it to src/test/resources/functions/")
                return
            }
            dir = dir.parentFile
        }
    }
}
