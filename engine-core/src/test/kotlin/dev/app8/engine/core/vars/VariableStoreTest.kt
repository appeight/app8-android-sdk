package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.ExpressionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of iOS `VariableStoreTests`. */
class VariableStoreTest {
    private val store = VariableStore()

    @Test
    fun defineAndGetVariable() {
        store.defineVariable("greeting", VariableDefinition(VariableType.STRING, "Hello"))
        assertTrue(store.hasVariable("greeting"))
        assertEquals("Hello", store.getValue("greeting"))
    }

    @Test
    fun setVariableValue() {
        store.defineVariable("count", VariableDefinition(VariableType.NUMBER, 10))
        store.setValue("count", 20)
        assertEquals(20L, store.getValue("count"))
    }

    @Test
    fun undefinedVariable() {
        assertFalse(store.hasVariable("undefined"))
        assertNull(store.getValue("undefined"))
        val e = assertFailsWith<VariableException.UndefinedVariable> { store.setValue("undefined", "test") }
        assertEquals("undefined", e.name)
    }

    @Test
    fun typeValidation() {
        store.defineVariable("count", VariableDefinition(VariableType.NUMBER, 0))
        store.setValue("count", 42)
        assertEquals(42L, store.getValue("count"))
        assertFailsWith<VariableException> { store.setValue("count", "not a number") }
    }

    @Test
    fun simpleComputedVariable() {
        store.defineVariable("base", VariableDefinition(VariableType.NUMBER, 10))
        store.defineVariable("doubled", VariableDefinition(VariableType.NUMBER, computed = "{{base * 2}}"))
        assertEquals(20L, store.getValue("doubled"))
    }

    @Test
    fun computedVariableUpdatesOnDependencyChange() {
        store.defineVariable("firstName", VariableDefinition(VariableType.STRING, "John"))
        store.defineVariable("lastName", VariableDefinition(VariableType.STRING, "Doe"))
        store.defineVariable("fullName", VariableDefinition(VariableType.STRING, computed = "{{firstName + ' ' + lastName}}"))
        assertEquals("John Doe", store.getValue("fullName"))
        store.setValue("firstName", "Jane")
        assertEquals("Jane Doe", store.getValue("fullName"))
    }

    @Test
    fun booleanComputedVariable() {
        store.defineVariable("age", VariableDefinition(VariableType.NUMBER, 16))
        store.defineVariable("isAdult", VariableDefinition(VariableType.BOOLEAN, computed = "{{age >= 18}}"))
        assertEquals(false, store.getValue("isAdult"))
        store.setValue("age", 20)
        assertEquals(true, store.getValue("isAdult"))
    }

    @Test
    fun circularDependencyDetection() {
        val error = assertFailsWith<VariableException> {
            store.defineVariable("a", VariableDefinition(VariableType.NUMBER, computed = "{{b + 1}}"))
            store.defineVariable("b", VariableDefinition(VariableType.NUMBER, computed = "{{a + 1}}"))
        }
        if (error is VariableException.CircularDependency) {
            assertTrue("a" in error.path || "b" in error.path)
        }
    }

    @Test
    fun selfReferencingVariable() {
        assertFailsWith<VariableException> {
            store.defineVariable("selfVar", VariableDefinition(VariableType.NUMBER, computed = "{{selfVar + 1}}"))
        }
    }

    @Test
    fun variableObserver() {
        store.defineVariable("observed", VariableDefinition(VariableType.STRING, "initial"))
        var receivedName: String? = null
        store.anyVariableChanged.subscribe { receivedName = it }
        store.setValue("observed", "changed")
        // Delivery is synchronous (main-actor semantics), so no waiting is needed.
        assertEquals("observed", receivedName)
    }

    @Test
    fun getAllValues() {
        store.defineVariable("name", VariableDefinition(VariableType.STRING, "John"))
        store.defineVariable("age", VariableDefinition(VariableType.NUMBER, 25))
        store.defineVariable("isActive", VariableDefinition(VariableType.BOOLEAN, true))
        val all = store.getAllValues()
        assertEquals(3, all.size)
        assertEquals("John", all["name"])
        assertEquals(25L, all["age"])
        assertEquals(true, all["isActive"])
    }

    @Test
    fun reset() {
        store.defineVariable("test", VariableDefinition(VariableType.STRING, "value"))
        assertTrue(store.hasVariable("test"))
        store.reset()
        assertFalse(store.hasVariable("test"))
        assertEquals(0, store.getAllValues().size)
    }

    @Test
    fun arrayVariable() {
        store.defineVariable("fruits", VariableDefinition(VariableType.ARRAY, listOf("apple", "banana")))
        assertEquals(listOf("apple", "banana"), store.getValue("fruits"))
        store.setValue("fruits", listOf("apple", "banana", "cherry"))
        assertEquals(listOf("apple", "banana", "cherry"), store.getValue("fruits"))
    }

    @Test
    fun objectVariable() {
        store.defineVariable("user", VariableDefinition(VariableType.OBJECT, mapOf("name" to "John", "age" to 30)))
        val user = store.getValue("user") as Map<*, *>
        assertEquals("John", user["name"])
        assertEquals(30L, user["age"])
    }

    @Test
    fun computedVariableWithArrayAccess() {
        store.defineVariable("items", VariableDefinition(VariableType.ARRAY, listOf("first", "second", "third")))
        store.defineVariable("firstItem", VariableDefinition(VariableType.STRING, computed = "{{items[0]}}"))
        assertEquals("first", store.getValue("firstItem"))
        store.setValue("items", listOf("new-first", "second", "third"))
        assertEquals("new-first", store.getValue("firstItem"))
    }

    @Test
    fun invalidExpression() {
        val e = assertFailsWith<Exception> {
            store.defineVariable("invalid", VariableDefinition(VariableType.STRING, computed = "{{(()}}"))
        }
        assertTrue(e is VariableException || e is ExpressionException)
    }

    @Test
    fun computedVariableCannotBeModified() {
        store.defineVariable("computed", VariableDefinition(VariableType.STRING, computed = "{{\"constant\"}}"))
        assertFailsWith<VariableException> { store.setValue("computed", "new value") }
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun notifications_are_synchronous_and_precede_dependent_recomputation() {
        store.defineVariable("base", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("doubled", VariableDefinition(VariableType.NUMBER, computed = "{{base * 2}}"))
        val events = ArrayList<String>()
        store.anyVariableChanged.subscribe { events.add("$it=${store.getValue(it)}") }
        store.setValue("base", 5)
        assertEquals(listOf("base=5", "doubled=10"), events)
    }

    @Test
    fun setting_an_equal_scalar_does_not_notify_but_collections_always_do() {
        store.defineVariable("n", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("list", VariableDefinition(VariableType.ARRAY, listOf(1)))
        val events = ArrayList<String>()
        store.anyVariableChanged.subscribe { events.add(it) }
        store.setValue("n", 1)
        store.setValue("n", 1.0) // Int vs Double: not equal on iOS either
        store.setValue("list", listOf(1))
        assertEquals(listOf("n", "list"), events)
    }

    @Test
    fun cancelled_subscription_stops_delivery() {
        store.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        var count = 0
        val sub = store.anyVariableChanged.subscribe { count++ }
        store.setValue("x", 1)
        sub.cancel()
        store.setValue("x", 2)
        assertEquals(1, count)
    }

    @Test
    fun set_multiple_values_recomputes_dependents_once() {
        store.defineVariable("a", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("b", VariableDefinition(VariableType.NUMBER, 2))
        store.defineVariable("sum", VariableDefinition(VariableType.NUMBER, computed = "{{a + b}}"))
        val events = ArrayList<String>()
        store.anyVariableChanged.subscribe { events.add(it) }
        store.setMultipleValues(mapOf("a" to 10, "b" to 20))
        assertEquals(30L, store.getValue("sum"))
        assertEquals(listOf("a", "b", "sum"), events)
    }

    @Test
    fun set_multiple_values_failure_does_not_leave_store_updating() {
        store.defineVariable("a", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("doubled", VariableDefinition(VariableType.NUMBER, computed = "{{a * 2}}"))
        assertFailsWith<VariableException.TypeMismatch> { store.setMultipleValues(mapOf("a" to "bad")) }
        store.setValue("a", 5)
        assertEquals(10L, store.getValue("doubled")) // batch mode cleared on failure
    }

    @Test
    fun computation_errors_are_wrapped() {
        val e = assertFailsWith<VariableException.ComputationError> {
            store.defineVariable("bad", VariableDefinition(VariableType.NUMBER, computed = "{{1 / 0}}"))
        }
        assertEquals("Computation error for '{{1 / 0}}': Division by zero", e.message)
        assertIs<ExpressionException.ParseError>(e.underlying)
    }

    @Test
    fun type_mismatch_message_uses_swift_type_names() {
        store.defineVariable("count", VariableDefinition(VariableType.NUMBER, 0))
        val e = assertFailsWith<VariableException.TypeMismatch> { store.setValue("count", "x") }
        assertEquals("Type mismatch for variable 'count': expected number, got String", e.message)
    }

    @Test
    fun boolean_validates_as_number_and_explicit_null_validates_as_nothing() {
        store.defineVariable("count", VariableDefinition(VariableType.NUMBER, 0))
        store.setValue("count", true) // Bool bridges to NSNumber on iOS
        assertEquals(true, store.getValue("count"))
        assertFailsWith<VariableException.TypeMismatch> { store.setValue("count", Values.Null) }
        store.setValue("count", null)
        assertNull(store.getValue("count"))
    }

    @Test
    fun external_value_errors_are_swallowed() {
        store.defineVariable("count", VariableDefinition(VariableType.NUMBER, 0))
        val logged = ArrayList<String>()
        store.logger = object : VariableLogger {
            override fun debug(message: String) {}
            override fun warning(message: String) {}
            override fun error(message: String) { logged.add(message) }
        }
        store.setExternalValue("count", "nope")
        assertEquals(0L, store.getValue("count"))
        assertEquals(1, logged.size)
    }

    @Test
    fun reset_stops_change_forwarding_of_old_variables() {
        store.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        val variable = store.getAllVariables().getValue("x")
        val events = ArrayList<String>()
        store.anyVariableChanged.subscribe { events.add(it) }
        store.reset()
        variable.setValue(5)
        assertEquals(emptyList(), events)
    }

    // endregion
}
