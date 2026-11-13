package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.ExpressionEvaluator
import dev.app8.engine.core.expr.ExpressionParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `persist: true` app variables and `map[key]` object access. */
class PersistedVariablesTest {

    private class Log : VariableLogger {
        val codes = ArrayList<String>()
        override fun debug(message: String) {}
        override fun warning(message: String) {}
        override fun error(message: String) {}
        override fun warning(code: String, message: String) {
            codes.add(code)
        }
    }

    private fun def(json: String) = Json.decodeFromString(VariableDefinition.serializer(), json)

    @Test
    fun persistDecodes() {
        assertEquals(true, def("""{"type":"array","initialValue":[],"persist":true}""").persist)
        assertNull(def("""{"type":"array"}""").persist)
    }

    @Test
    fun storedValueReplacesInitialValueAndChangesAreSaved() {
        val storage = InMemoryVariablePersistence(mapOf("cart" to """["a","b"]"""))
        val store = VariableStore().apply { persistence = storage }
        store.defineVariables(
            mapOf(
                "cart" to def("""{"type":"array","initialValue":[],"persist":true}"""),
                "count" to def("""{"type":"number","computed":"{{length(cart)}}"}"""),
                "plain" to def("""{"type":"number","initialValue":1}"""),
            ),
        )
        assertEquals(listOf("a", "b"), store.getValue("cart"))
        assertEquals(2L, Values.asInt(store.getValue("count"))?.toLong())

        store.setValue("cart", listOf("a", "b", "c"))
        assertEquals("""["a","b","c"]""", storage.values["cart"])
        store.setValue("plain", 5)
        assertNull(storage.values["plain"])
    }

    @Test
    fun resetClearsStoredValue() {
        val storage = InMemoryVariablePersistence(mapOf("sel" to """{"p1":"v2"}"""))
        val store = VariableStore().apply { persistence = storage }
        store.defineVariable("sel", def("""{"type":"object","initialValue":{},"persist":true}"""))
        assertEquals(mapOf("p1" to "v2"), store.getValue("sel"))
        store.resetToInitialValues()
        assertEquals(emptyMap<String, Any?>(), store.getValue("sel"))
        assertTrue("sel" !in storage.values)
    }

    @Test
    fun typeMismatchIsDroppedWithDiagnostic() {
        val log = Log()
        val storage = InMemoryVariablePersistence(mapOf("wishlist" to """{"oops":1}"""))
        val store = VariableStore().apply { persistence = storage; logger = log }
        store.defineVariable("wishlist", def("""{"type":"array","initialValue":["x"],"persist":true}"""))
        assertEquals(listOf("x"), store.getValue("wishlist"))
        assertEquals(listOf("variable.persist.typeMismatch"), log.codes)
        assertTrue("wishlist" !in storage.values)
    }

    @Test
    fun persistOnScreenScopeWarns() {
        val log = Log()
        val app = VariableStore().apply { logger = log }
        val screen = ScopedVariableStore(app)
        screen.defineVariable("x", def("""{"type":"number","initialValue":1,"persist":true}"""))
        assertEquals(listOf("variable.persist.scope"), log.codes)
    }

    @Test
    fun objectKeyAccess() {
        val store = VariableStore()
        store.defineVariable("sel", VariableDefinition(VariableType.OBJECT, mapOf("h1" to "red", "3" to "three")))
        store.defineVariable("item", VariableDefinition(VariableType.OBJECT, mapOf("handle" to "h1", "variant" to "blue")))
        store.defineVariable("other", VariableDefinition(VariableType.OBJECT, mapOf("handle" to "h2", "variant" to "blue")))
        val ctx = VariableContext(store)
        fun eval(e: String) = ExpressionEvaluator().evaluate(ExpressionParser().parse(e), ctx)
        assertEquals("red", eval("{{sel[item.handle]}}"))
        assertEquals("red", eval("{{sel['h1']}}"))
        assertEquals("three", eval("{{sel[3]}}"))
        assertNull(eval("{{sel['missing']}}"))
        assertEquals("red", eval("{{sel[item.handle] != null ? sel[item.handle] : item.variant}}"))
        assertEquals("blue", eval("{{sel[other.handle] != null ? sel[other.handle] : other.variant}}"))
    }
}
