package dev.app8.engine.core.vars

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValuesTest {
    private fun fromJson(text: String): Any = Values.fromJson(Json.parseToJsonElement(text))

    @Test
    fun json_numbers_decode_like_swift_anycodablevalue() {
        // Captured from the Swift oracle: integral values (even 1.0 / 1e2 / -0.0) become Int.
        assertEquals(1L, fromJson("1.0"))
        assertEquals(100L, fromJson("1e2"))
        assertEquals(100L, fromJson("1E+2"))
        assertEquals(0L, fromJson("-0.0"))
        assertEquals(1.5, fromJson("1.5e0"))
        assertEquals(9007199254740993L, fromJson("9007199254740993"))
        assertEquals(1.2345678901234567E19, fromJson("12345678901234567890"))
    }

    @Test
    fun json_round_trip() {
        val text = """{"a":[1,2.5,"x",true,null],"b":{}}"""
        val value = fromJson(text)
        assertEquals(mapOf("a" to listOf(1L, 2.5, "x", true, Values.Null), "b" to emptyMap<String, Any?>()), value)
        assertEquals(Json.parseToJsonElement(text), Values.toJson(value))
        assertEquals(JsonNull, Values.toJson(null))
        assertFailsWith<IllegalArgumentException> { Values.toJson(Any()) }
    }

    @Test
    fun normalize_converts_lenient_host_values() {
        val raw = mapOf("i" to 1, "f" to 1.5f, "l" to setOf(1, 2), "a" to arrayOf<Any?>(3))
        assertEquals(mapOf("i" to 1L, "f" to 1.5, "l" to listOf(1L, 2L), "a" to listOf(3L)), Values.normalize(raw))
        val canonical = listOf(1L, "x")
        assertSame(canonical, Values.normalize(canonical))
    }

    @Test
    fun nullish() {
        assertTrue(Values.isNullish(null))
        assertTrue(Values.isNullish(Values.Null))
        assertFalse(Values.isNullish(""))
    }

    @Test
    fun variable_definition_json() {
        val def = Json.decodeFromString(
            VariableDefinition.serializer(),
            """{"type":"array","initialValue":[1,null],"preview":{"source":"datasources/x","index":2,"value":{"k":1}}}""",
        )
        assertEquals(listOf(1L, Values.Null), def.rawInitialValue)
        assertEquals(2, def.preview?.index)
        assertEquals(mapOf("k" to 1L), def.preview?.value?.value)
        // Top-level null initialValue is "absent" (Swift decodeIfPresent) → type default.
        val nullInit = Json.decodeFromString(VariableDefinition.serializer(), """{"type":"string","initialValue":null}""")
        assertNull(nullInit.initialValue)
        assertEquals("", Variable("v", nullInit).value)
        assertEquals(
            """{"type":"number","computed":"{{a}}"}""",
            Json.encodeToString(VariableDefinition.serializer(), VariableDefinition(VariableType.NUMBER, computed = "{{a}}")),
        )
        assertFailsWith<Exception> { Json.decodeFromString(VariableDefinition.serializer(), """{"type":"date"}""") }
    }

    @Test
    fun input_parameter_definition_defaults_required_to_true() {
        val p = Json.decodeFromString(InputParameterDefinition.serializer(), """{"type":"string","defaultValue":"x"}""")
        assertTrue(p.required)
        assertEquals("x", p.defaultValue?.value)
    }

    @Test
    fun variable_type_validation_and_inference() {
        assertTrue(VariableType.NUMBER.validate(5))
        assertTrue(VariableType.NUMBER.validate(true))
        assertFalse(VariableType.BOOLEAN.validate(1L))
        assertFalse(VariableType.STRING.validate(Values.Null))
        assertTrue(VariableType.OBJECT.validate(null))
        assertEquals(VariableType.BOOLEAN, VariableType.inferType(true))
        assertEquals(VariableType.NUMBER, VariableType.inferType(2.5))
        assertEquals(VariableType.STRING, VariableType.inferType(Values.Null))
        assertEquals(0L, VariableType.NUMBER.defaultValue())
    }

    @Test
    fun computed_variable_starts_nil_and_rejects_set() {
        val v = Variable("c", VariableDefinition(VariableType.NUMBER, computed = "{{1}}"))
        assertNull(v.value)
        assertFailsWith<VariableException.CannotModifyComputedVariable> { v.setValue(1) }
    }

    @Test
    fun variable_emits_old_and_new_values() {
        val v = Variable("x", VariableDefinition(VariableType.NUMBER, 1))
        val seen = ArrayList<Pair<Any?, Any?>>()
        v.valueChanged.subscribe { seen.add(it) }
        v.setValue(2)
        v.setValue(2)
        assertEquals(listOf<Pair<Any?, Any?>>(1L to 2L), seen)
    }

    @Test
    fun dependency_graph_orders_transitive_dependents() {
        val g = DependencyGraph()
        g.addDependency("a", "b")
        g.addDependency("b", "c")
        g.addDependency("a", "c")
        assertEquals(setOf("b", "c"), g.getDependents("a"))
        assertEquals(listOf("b", "c"), g.getUpdateOrder(setOf("c", "b")))
        g.removeDependencies("b")
        assertEquals(setOf("c"), g.getDependents("a"))
    }
}
