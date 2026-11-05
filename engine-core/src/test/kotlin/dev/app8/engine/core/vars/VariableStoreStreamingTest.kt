package dev.app8.engine.core.vars

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of iOS `VariableStoreStreamingTests`. */
class VariableStoreStreamingTest {
    private val store = VariableStore()

    @Test
    fun setExternalValueUpdatesVariable() {
        store.defineVariable("price", VariableDefinition(VariableType.NUMBER, 0))
        store.setExternalValue("price", 42)
        assertEquals(42L, store.getValue("price"))
    }

    @Test
    fun setExternalValueFiresAnyVariableChanged() {
        store.defineVariable("price", VariableDefinition(VariableType.NUMBER, 0))
        var fired = false
        store.anyVariableChanged.subscribe { if (it == "price") fired = true }
        store.setExternalValue("price", 99)
        assertTrue(fired)
    }

    @Test
    fun setExternalValueSilentlyIgnoresUnknownVariable() {
        store.setExternalValue("nonexistent", 42)
        assertNull(store.getValue("nonexistent"))
    }

    @Test
    fun setExternalValueUpdatesComputedDependents() {
        store.defineVariable("base", VariableDefinition(VariableType.NUMBER, 10))
        store.defineVariable("doubled", VariableDefinition(VariableType.NUMBER, computed = "{{base * 2}}"))
        assertEquals(20L, store.getValue("doubled"))
        store.setExternalValue("base", 5)
        assertEquals(10L, store.getValue("doubled"))
    }

    @Test
    fun setExternalValueOnScopedStoreUpdatesLocalVariable() {
        val screenStore = ScopedVariableStore(store)
        screenStore.defineVariable("liveCount", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.setExternalValue("liveCount", 7)
        assertEquals(7L, screenStore.getValue("liveCount"))
    }

    @Test
    fun setExternalValuePropagatesDownScopeChain() {
        val screenStore = ScopedVariableStore(store)
        val componentStore = ScopedVariableStore(screenStore)
        screenStore.defineVariable("liveCount", VariableDefinition(VariableType.NUMBER, 0))
        var reached = false
        componentStore.anyVariableChanged.subscribe { if (it == "liveCount") reached = true }
        screenStore.setExternalValue("liveCount", 7)
        assertTrue(reached)
    }

    @Test
    fun setExternalValueForwardsToParentWhenNotLocal() {
        val screenStore = ScopedVariableStore(store)
        store.defineVariable("appLevel", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.setExternalValue("appLevel", 100)
        assertEquals(100L, store.getValue("appLevel"))
    }

    @Test
    fun setExternalValueDoesNotAffectSiblingStores() {
        val sibling1 = ScopedVariableStore(store)
        val sibling2 = ScopedVariableStore(store)
        sibling1.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        sibling2.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        sibling1.setExternalValue("x", 10)
        assertEquals(10L, sibling1.getValue("x"))
        assertEquals(0L, sibling2.getValue("x"))
    }

    @Test
    fun variableDefinitionIsStreamingDefault() {
        assertFalse(VariableDefinition(VariableType.NUMBER).isStreaming)
    }

    @Test
    fun variableDefinitionIsStreamingTrue() {
        assertTrue(VariableDefinition(VariableType.NUMBER, streaming = true).isStreaming)
    }

    @Test
    fun variableDefinitionStreamingDecodable() {
        val json = """{ "type": "number", "source": "datasources/live", "streaming": true }"""
        val definition = Json.decodeFromString(VariableDefinition.serializer(), json)
        assertTrue(definition.isStreaming)
        assertEquals("datasources/live", definition.source)
    }
}
