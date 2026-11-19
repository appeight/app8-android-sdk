package dev.app8.engine.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A computed variable that throws must not stop the others from being defined. */
class DefineVariablesOnErrorTest {
    private val lines = listOf(mapOf("name" to "A", "quantity" to 2.0))
    private val definitions = mapOf(
        "lines" to VariableDefinition(VariableType.ARRAY, lines),
        // `unitPrice` is missing: null * number throws.
        "total" to VariableDefinition(VariableType.NUMBER, computed = "{{sum(map(lines, item.unitPrice * item.quantity))}}"),
        "heading" to VariableDefinition(VariableType.STRING, computed = "{{title + ' (1)'}}"),
        "title" to VariableDefinition(VariableType.STRING, "Bag"),
    )

    @Test
    fun failingVariableIsReportedAndTheRestAreDefined() {
        val store = ScopedVariableStore(VariableStore())
        val failed = ArrayList<String>()
        store.defineVariables(definitions) { name, _ -> failed += name }
        assertEquals(listOf("total"), failed)
        assertFalse(store.hasVariable("total"))
        assertEquals("Bag (1)", store.getValue("heading"))
        assertEquals("Bag", store.getValue("title"))
        assertTrue(store.hasVariable("lines"))
    }

    @Test
    fun withoutAHandlerTheErrorStillThrows() {
        assertFailsWith<VariableException> { ScopedVariableStore(VariableStore()).defineVariables(definitions) }
    }
}
