package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.6.0 expression additions: evaluated array literals, array functions, date/time functions. */
class Dsl060ExpressionTest {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator().apply {
        locale = Locale.forLanguageTag("en-US")
        // 2026-11-15 10:30 in Berlin (UTC+1).
        clock = Clock.fixed(Instant.parse("2026-11-15T09:30:45.250Z"), ZoneId.of("Europe/Berlin"))
    }
    private val store = VariableStore().apply {
        defineVariable("name", VariableDefinition(VariableType.STRING, "Ada"))
        defineVariable("event", VariableDefinition(VariableType.OBJECT, mapOf("item" to mapOf("id" to 7L))))
        defineVariable(
            "lines",
            VariableDefinition(
                VariableType.ARRAY,
                listOf(mapOf("key" to "a", "n" to 1L), mapOf("key" to "b", "n" to 2L), mapOf("key" to "a", "n" to 3L), mapOf("n" to 4L), mapOf("n" to 5L)),
            ),
        )
        defineVariable("ids", VariableDefinition(VariableType.ARRAY, listOf(1L, 2L, 3L, 2L)))
    }
    private val context = VariableContext(store)

    private fun eval(expression: String): Any? = evaluator.evaluate(parser.parse(expression), context)

    @Test
    fun `array literals evaluate every element`() {
        assertEquals(listOf(mapOf("id" to 7L), 1L, "Ada!"), eval("{{[event.item, 1, name + \"!\"]}}"))
        assertEquals(listOf(1L, 2L), eval("{{[1, 2]}}"))
    }

    @Test
    fun `prepend and append return new arrays`() {
        assertEquals(listOf(0L, 1L, 2L, 3L, 2L), eval("{{prepend(ids, 0)}}"))
        assertEquals(listOf(1L, 2L, 3L, 2L, 9L), eval("{{append(ids, 9)}}"))
        assertEquals(listOf("x"), eval("{{append(null, 'x')}}"))
        assertEquals(listOf(1L, 2L, 3L, 2L), store.getValue("ids"), "inputs are not mutated")
    }

    @Test
    fun `concat, uniqueBy and remove`() {
        assertEquals(listOf(1L, 2L, 3L, 2L, 5L), eval("{{concat(ids, [5])}}"))
        @Suppress("UNCHECKED_CAST")
        val unique = eval("{{uniqueBy(lines, 'key')}}") as List<Map<String, Any?>>
        assertEquals(listOf(1L, 2L, 4L), unique.map { it["n"] }, "first of each key wins; keyless elements share the key null")
        assertEquals(listOf(1L, 3L), eval("{{remove(ids, 2)}}"))
        assertEquals(listOf(1L, 3L), eval("{{remove(ids, '2')}}"), "scalars compare with ==")
        @Suppress("UNCHECKED_CAST")
        val left = eval("{{remove(lines, lines[1])}}") as List<Map<String, Any?>>
        assertEquals(listOf(1L, 3L, 4L, 5L), left.map { it["n"] }, "objects compare by value")
        assertEquals(listOf(1L, 2L, 3L, 2L), eval("{{remove(ids, 'nope')}}"))
    }

    @Test
    fun `now and today read the clock`() {
        assertEquals("2026-11-15T09:30:45Z", eval("{{now()}}"))
        assertEquals("2026-11-15", eval("{{today()}}"))
        var reads = 0
        evaluator.clockObserver = { reads++ }
        eval("{{now()}}")
        eval("{{today()}}")
        eval("{{timeAgo('2026-11-15T09:00:00Z')}}")
        assertEquals(3, reads)
    }

    @Test
    fun `addDays, addHours and startOfDay`() {
        assertEquals("2026-11-22", eval("{{addDays(today(), 7)}}"), "a date stays a date")
        assertEquals("2026-11-14T09:30:45Z", eval("{{addDays(now(), -1)}}"))
        assertEquals("2026-11-17T09:30:45Z", eval("{{addDays(now(), 2.9)}}"), "whole days, truncated")
        assertEquals("2026-11-15T11:30:45Z", eval("{{addHours(now(), 2)}}"))
        assertEquals("2026-11-15T10:00:45Z", eval("{{addHours(now(), 0.5)}}"))
        // Local midnight in Berlin is 23:00 UTC the day before.
        assertEquals("2026-11-14T23:00:00Z", eval("{{startOfDay(now())}}"))
        assertNull(eval("{{addDays('not a date', 1)}}"))
    }

    @Test
    fun `date functions accept +00 00 offsets like Z`() {
        assertEquals(eval("{{daysBetween('2026-11-01T00:00:00Z', '2026-11-15T00:00:00Z')}}"), eval("{{daysBetween('2026-11-01T00:00:00+00:00', '2026-11-15T00:00:00+00:00')}}"))
        assertEquals(eval("{{timeAgo('2026-11-15T07:30:00Z')}}"), eval("{{timeAgo('2026-11-15T07:30:00+00:00')}}"))
        assertEquals("2 hours ago", eval("{{timeAgo('2026-11-15T07:30:00+00:00')}}"))
        assertTrue((eval("{{formatDate('2026-11-15T07:30:00+00:00', 'medium')}}") as String).startsWith("Nov 15"))
    }
}
