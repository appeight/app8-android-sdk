package dev.app8.engine.core.vars.actions

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableException
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Port of iOS `VariableActionHandlerTests`. */
class VariableActionHandlerTest {
    private lateinit var store: VariableStore
    private val handler = VariableActionHandler()
    private val context get() = VariableContext(store)

    @BeforeTest
    fun setUp() {
        store = VariableStore()
        store.defineVariable("counter", VariableDefinition(VariableType.NUMBER, 0))
        store.defineVariable("message", VariableDefinition(VariableType.STRING, "Hello"))
        store.defineVariable("isActive", VariableDefinition(VariableType.BOOLEAN, false))
        store.defineVariable("selectedItems", VariableDefinition(VariableType.ARRAY, listOf("apple")))
        store.defineVariable("doubleValue", VariableDefinition(VariableType.NUMBER, 1.5))
    }

    private fun execute(action: VariableAction) = handler.execute(action, store, context)

    /** Builds the action from JSON, as the Swift tests do via `JSONDecoder`. */
    private fun action(
        type: String,
        variableName: String? = null,
        value: Any? = null,
        by: Double? = null,
        updates: Map<String, Any?>? = null,
        matchBy: String? = null,
        field: String? = null,
    ): VariableAction = VariableAction.fromJson(
        buildJsonObject {
            put("type", JsonPrimitive(type))
            variableName?.let { put("variableName", JsonPrimitive(it)) }
            value?.let { put("value", Values.toJson(it)) }
            by?.let { put("by", JsonPrimitive(it)) }
            updates?.let { put("updates", Values.toJson(it)) }
            matchBy?.let { put("matchBy", JsonPrimitive(it)) }
            field?.let { put("field", JsonPrimitive(it)) }
        },
    )

    @Test
    fun updateVariableWithLiteralValue() {
        execute(action("updateVariable", "message", "Updated Message"))
        assertEquals("Updated Message", store.getValue("message"))
    }

    @Test
    fun updateVariableWithExpressionValue() {
        execute(action("updateVariable", "message", "{{message + ' World'}}"))
        assertEquals("Hello World", store.getValue("message"))
    }

    @Test
    fun updateBooleanVariable() {
        execute(action("updateVariable", "isActive", true))
        assertEquals(true, store.getValue("isActive"))
    }

    @Test
    fun updateVariableWithConditionalExpression() {
        execute(action("updateVariable", "message", "{{isActive ? 'Active' : 'Inactive'}}"))
        assertEquals("Inactive", store.getValue("message"))
    }

    @Test
    fun toggleArrayValueAdd() {
        execute(action("toggleArrayValue", "selectedItems", "banana"))
        assertEquals(listOf("apple", "banana"), (store.getValue("selectedItems") as List<*>).map { it as String }.sorted())
    }

    @Test
    fun toggleArrayValueRemove() {
        store.setValue("selectedItems", listOf("apple", "banana"))
        execute(action("toggleArrayValue", "selectedItems", "banana"))
        assertEquals(listOf("apple"), store.getValue("selectedItems"))
    }

    @Test
    fun toggleArrayValueExisting() {
        execute(action("toggleArrayValue", "selectedItems", "apple"))
        assertEquals(0, (store.getValue("selectedItems") as List<*>).size)
    }

    @Test
    fun incrementVariableByDefault() {
        execute(action("incrementVariable", "counter"))
        assertEquals(1L, store.getValue("counter"))
    }

    @Test
    fun incrementVariableByCustomAmount() {
        execute(action("incrementVariable", "counter", by = 5.0))
        assertEquals(5L, store.getValue("counter"))
    }

    @Test
    fun incrementVariableMultipleTimes() {
        val a = action("incrementVariable", "counter", by = 1.0)
        repeat(3) { execute(a) }
        assertEquals(3L, store.getValue("counter"))
    }

    @Test
    fun incrementDoubleVariable() {
        execute(action("incrementVariable", "doubleValue", by = 0.5))
        assertEquals(2.0, store.getValue("doubleValue"))
    }

    @Test
    fun decrementVariable() {
        store.setValue("counter", 10)
        execute(action("incrementVariable", "counter", by = -3.0))
        assertEquals(7L, store.getValue("counter"))
    }

    @Test
    fun updateMultipleVariables() {
        execute(action("updateMultipleVariables", updates = mapOf("message" to "Batch Updated", "isActive" to true)))
        assertEquals("Batch Updated", store.getValue("message"))
        assertEquals(true, store.getValue("isActive"))
    }

    @Test
    fun updateMultipleVariablesWithExpressions() {
        execute(action("updateMultipleVariables", updates = mapOf("message" to "{{message + '!'}}")))
        assertEquals("Hello!", store.getValue("message"))
    }

    @Test
    fun resetVariables() {
        store.setValue("counter", 100)
        store.setValue("message", "Modified")
        assertEquals(100L, store.getValue("counter"))
        assertEquals("Modified", store.getValue("message"))
        execute(action("resetVariables"))
        // resetVariables currently clears all variables rather than restoring initial values.
        assertNull(store.getValue("counter"))
        assertNull(store.getValue("message"))
    }

    @Test
    fun updateVariableMissingVariableName() {
        assertFailsWith<VariableException> { execute(action("updateVariable", null, "test")) }
    }

    @Test
    fun updateVariableMissingValue() {
        assertFailsWith<VariableException> { execute(action("updateVariable", "message", null)) }
    }

    @Test
    fun toggleArrayValueMissingValue() {
        assertFailsWith<VariableException> { execute(action("toggleArrayValue", "selectedItems", null)) }
    }

    @Test
    fun toggleArrayValueOnNonArray() {
        assertFailsWith<VariableException> { execute(action("toggleArrayValue", "message", "test")) }
    }

    @Test
    fun toggleArrayValueMatchByKeyRemovesObject() {
        val items = listOf(mapOf("id" to "1", "title" to "First"), mapOf("id" to "2", "title" to "Second"), mapOf("id" to "3", "title" to "Third"))
        store.defineVariable("todos", VariableDefinition(VariableType.ARRAY, items))
        execute(action("toggleArrayValue", "todos", "2", matchBy = "id"))
        val result = store.getValue("todos") as List<*>
        assertEquals(2, result.size)
        assertEquals("1", (result[0] as Map<*, *>)["id"])
        assertEquals("3", (result[1] as Map<*, *>)["id"])
    }

    @Test
    fun toggleArrayValueMatchByKeyIsNoOpWhenNotFound() {
        store.defineVariable("todos", VariableDefinition(VariableType.ARRAY, listOf(mapOf("id" to "1", "title" to "First"))))
        execute(action("toggleArrayValue", "todos", "99", matchBy = "id"))
        val result = store.getValue("todos") as List<*>
        assertEquals(1, result.size, "Array should be unchanged when matchBy target not found")
        assertEquals("1", (result[0] as Map<*, *>)["id"])
    }

    @Test
    fun toggleArrayValueWithoutMatchByStillWorks() {
        execute(action("toggleArrayValue", "selectedItems", "apple"))
        assertEquals(0, (store.getValue("selectedItems") as List<*>).size)
    }

    @Test
    fun appendToArrayAddsPrimitive() {
        store.defineVariable("nums", VariableDefinition(VariableType.ARRAY, listOf(1, 2, 3)))
        execute(action("appendToArray", "nums", 4))
        assertEquals(listOf(1L, 2L, 3L, 4L), store.getValue("nums"))
    }

    @Test
    fun appendToArrayAddsObject() {
        store.defineVariable("items", VariableDefinition(VariableType.ARRAY, listOf(mapOf("id" to "1", "name" to "First"))))
        store.defineVariable("template", VariableDefinition(VariableType.OBJECT, mapOf("id" to "2", "name" to "Second")))
        execute(action("appendToArray", "items", "{{template}}"))
        val result = store.getValue("items") as List<*>
        assertEquals(2, result.size)
        assertEquals("2", (result[1] as Map<*, *>)["id"])
        assertEquals("Second", (result[1] as Map<*, *>)["name"])
    }

    @Test
    fun appendToArrayOnEmptyArray() {
        store.defineVariable("list", VariableDefinition(VariableType.ARRAY, emptyList<Any>()))
        execute(action("appendToArray", "list", "first"))
        assertEquals(listOf("first"), store.getValue("list"))
    }

    @Test
    fun appendToArrayThrowsOnNonArray() {
        assertFailsWith<VariableException> { execute(action("appendToArray", "message", "foo")) }
    }

    @Test
    fun appendToArrayMissingValueThrows() {
        store.defineVariable("nums2", VariableDefinition(VariableType.ARRAY, emptyList<Int>()))
        assertFailsWith<VariableException> { execute(action("appendToArray", "nums2")) }
    }

    @Test
    fun incrementNonNumericVariable() {
        assertFailsWith<VariableException> { execute(action("incrementVariable", "message", by = 1.0)) }
    }

    @Test
    fun incrementUndefinedVariable() {
        assertFailsWith<VariableException> { execute(action("incrementVariable", "undefinedVar", by = 1.0)) }
    }

    // region Additional coverage (incrementArrayItem has no Swift tests)

    private fun cart(): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return store.getValue("cart") as List<Map<String, Any?>>
    }

    @Test
    fun incrementArrayItem_adds_increments_decrements_and_removes() {
        store.defineVariable("cart", VariableDefinition(VariableType.ARRAY, emptyList<Any>()))
        store.defineVariable("product", VariableDefinition(VariableType.OBJECT, mapOf("id" to "p1", "name" to "Shirt")))
        val add = action("incrementArrayItem", "cart", "{{product}}", matchBy = "id")
        execute(add)
        assertEquals(listOf(mapOf("id" to "p1", "name" to "Shirt", "quantity" to 1L)), cart())
        execute(add)
        assertEquals(2L, cart()[0]["quantity"])
        execute(action("incrementArrayItem", "cart", "p1", by = -1.0, matchBy = "id"))
        assertEquals(1L, cart()[0]["quantity"])
        execute(action("incrementArrayItem", "cart", "p1", by = -1.0, matchBy = "id"))
        assertEquals(emptyList(), cart())
    }

    @Test
    fun incrementArrayItem_custom_field_fractional_and_no_ops() {
        store.defineVariable("cart", VariableDefinition(VariableType.ARRAY, listOf(mapOf("id" to 1, "qty" to 2))))
        execute(action("incrementArrayItem", "cart", 1, by = 0.5, matchBy = "id", field = "qty"))
        assertEquals(2.5, cart()[0]["qty"])
        // Key only, not found → no-op; object with by <= 0 → no-op; object missing matchBy key → no-op.
        execute(action("incrementArrayItem", "cart", 99, matchBy = "id"))
        execute(action("incrementArrayItem", "cart", mapOf("id" to 2), by = -1.0, matchBy = "id"))
        execute(action("incrementArrayItem", "cart", mapOf("other" to 2), matchBy = "id"))
        assertEquals(1, cart().size)
        assertFailsWith<VariableException.UndefinedVariable> { execute(action("incrementArrayItem", "cart", 1)) }
    }

    @Test
    fun values_resolve_expressions_inside_objects_and_arrays() {
        store.defineVariable("obj", VariableDefinition(VariableType.OBJECT, emptyMap<String, Any>()))
        execute(action("updateVariable", "obj", mapOf("greeting" to "Hi {{message}}", "n" to "{{counter + 1}}", "list" to listOf("{{counter}}"))))
        assertEquals(mapOf("greeting" to "Hi Hello", "n" to 1L, "list" to listOf(0L)), store.getValue("obj"))
    }

    @Test
    fun increment_promotes_int_to_double_for_fractional_step() {
        execute(action("incrementVariable", "counter", by = 0.1))
        assertEquals(0.1, store.getValue("counter"))
    }

    @Test
    fun append_respects_the_array_cap() {
        store.defineVariable("big", VariableDefinition(VariableType.ARRAY, List(10_000) { it }))
        execute(action("appendToArray", "big", 1))
        assertEquals(10_000, (store.getValue("big") as List<*>).size)
    }

    @Test
    fun unknown_and_non_variable_actions_are_ignored() {
        execute(VariableAction.fromJson("""{"type": "navigation", "nextScreen": "x", "variableName": "message", "value": "x"}"""))
        assertEquals("Hello", store.getValue("message"))
    }

    @Test
    fun json_null_value_decodes_as_absent() {
        val a = VariableAction.fromJson(JsonObject(mapOf("type" to JsonPrimitive("updateVariable"), "variableName" to JsonPrimitive("message"), "value" to kotlinx.serialization.json.JsonNull)))
        assertNull(a.value)
    }

    // endregion
}
