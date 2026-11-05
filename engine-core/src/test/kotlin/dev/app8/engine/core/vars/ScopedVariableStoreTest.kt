package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.ExpressionEvaluator
import dev.app8.engine.core.expr.ExpressionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Port of iOS `ScopedVariableStoreTests`. */
class ScopedVariableStoreTest {
    private val appStore = VariableStore()
    private val screenStore = ScopedVariableStore(appStore)
    private val componentStore = ScopedVariableStore(screenStore)

    @Test
    fun localVariableTakesPrecedence() {
        appStore.defineVariable("value", VariableDefinition(VariableType.STRING, "app"))
        screenStore.defineVariable("value", VariableDefinition(VariableType.STRING, "screen"))
        componentStore.defineVariable("value", VariableDefinition(VariableType.STRING, "component"))
        assertEquals("app", appStore.getValue("value"))
        assertEquals("screen", screenStore.getValue("value"))
        assertEquals("component", componentStore.getValue("value"))
    }

    @Test
    fun parentLookup() {
        appStore.defineVariable("appOnly", VariableDefinition(VariableType.STRING, "from-app"))
        assertEquals("from-app", screenStore.getValue("appOnly"))
        assertEquals("from-app", componentStore.getValue("appOnly"))
    }

    @Test
    fun hasVariableInParentScope() {
        appStore.defineVariable("appVar", VariableDefinition(VariableType.NUMBER, 42))
        assertTrue(appStore.hasVariable("appVar"))
        assertTrue(screenStore.hasVariable("appVar"))
        assertTrue(componentStore.hasVariable("appVar"))
        assertFalse(appStore.hasVariable("nonexistent"))
        assertFalse(screenStore.hasVariable("nonexistent"))
    }

    @Test
    fun setValueInParentScope() {
        appStore.defineVariable("shared", VariableDefinition(VariableType.NUMBER, 0))
        componentStore.setValue("shared", 100)
        assertEquals(100L, appStore.getValue("shared"))
        assertEquals(100L, screenStore.getValue("shared"))
        assertEquals(100L, componentStore.getValue("shared"))
    }

    @Test
    fun computedVariableAccessingParentScope() {
        appStore.defineVariable("multiplier", VariableDefinition(VariableType.NUMBER, 2))
        screenStore.defineVariable("base", VariableDefinition(VariableType.NUMBER, 5))
        componentStore.defineVariable("result", VariableDefinition(VariableType.NUMBER, computed = "{{base * multiplier}}"))
        assertEquals(10L, componentStore.getValue("result"))
        appStore.setValue("multiplier", 3)
        assertEquals(15L, componentStore.getValue("result"))
        screenStore.setValue("base", 10)
        assertEquals(30L, componentStore.getValue("result"))
    }

    @Test
    fun computedVariableUpdatesWhenParentVariableChanges() {
        screenStore.defineVariable("counter1", VariableDefinition(VariableType.NUMBER, 0))
        componentStore.defineVariable("count", VariableDefinition(VariableType.NUMBER, computed = "{{counter1}}"))
        assertEquals(0L, componentStore.getValue("count"))
        screenStore.setValue("counter1", 5)
        assertEquals(5L, componentStore.getValue("count"))
    }

    @Test
    fun twoIndependentComponentStoresDontInterfere() {
        screenStore.defineVariable("counter1", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.defineVariable("counter2", VariableDefinition(VariableType.NUMBER, 0))
        val cardA = ScopedVariableStore(screenStore)
        val cardB = ScopedVariableStore(screenStore)
        cardA.defineVariable("count", VariableDefinition(VariableType.NUMBER, computed = "{{counter1}}"))
        cardB.defineVariable("count", VariableDefinition(VariableType.NUMBER, computed = "{{counter2}}"))

        screenStore.setValue("counter1", 3)
        assertEquals(3L, cardA.getValue("count"))
        assertEquals(0L, cardB.getValue("count"))

        screenStore.setValue("counter2", 7)
        assertEquals(3L, cardA.getValue("count"))
        assertEquals(7L, cardB.getValue("count"))
    }

    @Test
    fun computedVariableReactiveAcrossThreeScopeLevels() {
        appStore.defineVariable("appCounter", VariableDefinition(VariableType.NUMBER, 10))
        componentStore.defineVariable("doubled", VariableDefinition(VariableType.NUMBER, computed = "{{appCounter * 2}}"))
        assertEquals(20L, componentStore.getValue("doubled"))
        appStore.setValue("appCounter", 5)
        assertEquals(10L, componentStore.getValue("doubled"))
    }

    @Test
    fun getAllValuesMergesScopes() {
        appStore.defineVariable("appVar", VariableDefinition(VariableType.STRING, "app"))
        screenStore.defineVariable("screenVar", VariableDefinition(VariableType.STRING, "screen"))
        componentStore.defineVariable("componentVar", VariableDefinition(VariableType.STRING, "component"))
        val all = componentStore.getAllValues()
        assertEquals("app", all["appVar"])
        assertEquals("screen", all["screenVar"])
        assertEquals("component", all["componentVar"])
    }

    @Test
    fun getLocalVariablesOnly() {
        appStore.defineVariable("appVar", VariableDefinition(VariableType.STRING, "app"))
        screenStore.defineVariable("screenVar", VariableDefinition(VariableType.STRING, "screen"))
        val local = screenStore.getLocalVariables()
        assertEquals(1, local.size)
        assertNotNull(local["screenVar"])
        assertNull(local["appVar"])
    }

    @Test
    fun resetLocalDoesNotAffectParent() {
        appStore.defineVariable("appVar", VariableDefinition(VariableType.STRING, "app"))
        screenStore.defineVariable("screenVar", VariableDefinition(VariableType.STRING, "screen"))
        screenStore.reset()
        assertFalse("screenVar" in screenStore.getLocalVariables())
        assertEquals("app", screenStore.getValue("appVar"))
    }

    @Test
    fun setValueOnUndefinedVariableInAnyScope() {
        assertFailsWith<VariableException> { componentStore.setValue("nonexistent", "value") }
    }

    @Test
    fun circularDependencyWithinScope() {
        assertFailsWith<VariableException> {
            screenStore.defineVariable("a", VariableDefinition(VariableType.NUMBER, computed = "{{b + 1}}"))
            screenStore.defineVariable("b", VariableDefinition(VariableType.NUMBER, computed = "{{a + 1}}"))
        }
    }

    @Test
    fun computedBeforeSiblingDep_reproducer() {
        val error = assertFailsWith<VariableException.ComputationError> {
            screenStore.defineVariable(
                "displayedTasks",
                VariableDefinition(
                    VariableType.ARRAY,
                    computed = "{{currentFilter == 'all' ? tasks : filter(tasks, item.done == false)}}",
                ),
            )
        }
        assertEquals("currentFilter", assertIs<VariableException.UndefinedVariable>(error.underlying).name)
    }

    @Test
    fun defineVariables_filterDemoTasksScenario() {
        val tasks = listOf(
            mapOf("id" to 1, "title" to "Fix bug", "done" to false),
            mapOf("id" to 2, "title" to "Write docs", "done" to true),
            mapOf("id" to 3, "title" to "Deploy", "done" to false),
        )
        val definitions = linkedMapOf(
            "displayedTasks" to VariableDefinition(
                VariableType.ARRAY,
                computed = "{{currentFilter == 'all' ? tasks : filter(tasks, item.done == false)}}",
            ),
            "currentFilter" to VariableDefinition(VariableType.STRING, "all"),
            "tasks" to VariableDefinition(VariableType.ARRAY, tasks),
        )
        screenStore.defineVariables(definitions)
        assertEquals(3, (screenStore.getValue("displayedTasks") as List<*>).size)
        screenStore.setValue("currentFilter", "active")
        assertEquals(2, (screenStore.getValue("displayedTasks") as List<*>).size)
    }

    @Test
    fun defineVariables_computedDependsOnComputed() {
        screenStore.defineVariables(
            linkedMapOf(
                "quadrupled" to VariableDefinition(VariableType.NUMBER, computed = "{{doubled * 2}}"),
                "doubled" to VariableDefinition(VariableType.NUMBER, computed = "{{base * 2}}"),
                "base" to VariableDefinition(VariableType.NUMBER, 3),
            ),
        )
        assertEquals(6L, screenStore.getValue("doubled"))
        assertEquals(12L, screenStore.getValue("quadrupled"))
        screenStore.setValue("base", 5)
        assertEquals(10L, screenStore.getValue("doubled"))
        assertEquals(20L, screenStore.getValue("quadrupled"))
    }

    @Test
    fun defineVariables_computedReferencingParentScope() {
        appStore.defineVariable("multiplier", VariableDefinition(VariableType.NUMBER, 4))
        screenStore.defineVariables(
            linkedMapOf(
                "result" to VariableDefinition(VariableType.NUMBER, computed = "{{base * multiplier}}"),
                "base" to VariableDefinition(VariableType.NUMBER, 7),
            ),
        )
        assertEquals(28L, screenStore.getValue("result"))
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun parent_changes_are_forwarded_to_children() {
        appStore.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        val events = ArrayList<String>()
        componentStore.anyVariableChanged.subscribe { events.add(it) }
        appStore.setValue("x", 1)
        assertEquals(listOf("x"), events)
    }

    @Test
    fun propagation_filter_limits_forwarding_but_not_reparent_signal() {
        appStore.defineVariable("a", VariableDefinition(VariableType.NUMBER, 0))
        appStore.defineVariable("b", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.setParentPropagationFilter { it == "a" }
        val events = ArrayList<String>()
        screenStore.anyVariableChanged.subscribe { events.add(it) }
        appStore.setValue("a", 1)
        appStore.setValue("b", 1)
        screenStore.reparent(appStore)
        assertEquals(listOf("a", ""), events)
    }

    @Test
    fun reparent_switches_lookup_and_forwarding() {
        val otherApp = VariableStore()
        appStore.defineVariable("v", VariableDefinition(VariableType.STRING, "old"))
        otherApp.defineVariable("v", VariableDefinition(VariableType.STRING, "new"))
        screenStore.defineVariable("echo", VariableDefinition(VariableType.STRING, computed = "{{v}}"))
        val events = ArrayList<String>()
        screenStore.anyVariableChanged.subscribe { events.add(it) }

        screenStore.reparent(otherApp)
        assertEquals("new", screenStore.getValue("v"))
        assertSame(otherApp, screenStore.getParentStore())
        appStore.setValue("v", "ignored")
        otherApp.setValue("v", "newer")
        assertEquals(listOf("", "echo", "v"), events) // dependents recompute before the forward
        assertEquals("newer", screenStore.getValue("echo"))

        screenStore.reparent(screenStore) // self: ignored
        assertSame(otherApp, screenStore.parent)
    }

    @Test
    fun detach_stops_forwarding() {
        appStore.defineVariable("x", VariableDefinition(VariableType.NUMBER, 0))
        val events = ArrayList<String>()
        screenStore.anyVariableChanged.subscribe { events.add(it) }
        screenStore.detach()
        appStore.setValue("x", 1)
        assertEquals(emptyList(), events)
    }

    @Test
    fun local_nil_falls_through_but_explicit_null_shadows_parent() {
        appStore.defineVariable("v", VariableDefinition(VariableType.STRING, "app"))
        screenStore.defineVariable("v", VariableDefinition(VariableType.STRING, "screen"))
        screenStore.setValue("v", null)
        assertEquals("app", screenStore.getValue("v"))
        screenStore.defineVariable("w", VariableDefinition(VariableType.STRING, Values.Null))
        appStore.defineVariable("w", VariableDefinition(VariableType.STRING, "app"))
        assertSame(Values.Null, screenStore.getValue("w"))
    }

    @Test
    fun parent_values_are_reachable_via_dollar_parent() {
        appStore.defineVariable("title", VariableDefinition(VariableType.STRING, "App"))
        screenStore.defineVariable("title", VariableDefinition(VariableType.STRING, "Screen"))
        val result = ExpressionEvaluator().evaluate(ExpressionParser().parse("\$parent.title"), VariableContext(screenStore))
        assertEquals("App", result)
    }

    @Test
    fun logger_is_inherited_from_parent() {
        val logger = object : VariableLogger {
            override fun debug(message: String) {}
            override fun warning(message: String) {}
            override fun error(message: String) {}
        }
        appStore.logger = logger
        assertSame(logger, ScopedVariableStore(appStore).logger)
        assertSame(logger, ScopedVariableStore(ScopedVariableStore(appStore)).logger)
    }

    @Test
    fun scoped_set_multiple_values_splits_local_and_parent_updates() {
        appStore.defineVariable("appVar", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.defineVariable("local", VariableDefinition(VariableType.NUMBER, 0))
        screenStore.defineVariable("sum", VariableDefinition(VariableType.NUMBER, computed = "{{appVar + local}}"))
        screenStore.setMultipleValues(mapOf("appVar" to 2, "local" to 3))
        assertEquals(2L, appStore.getValue("appVar"))
        assertEquals(5L, screenStore.getValue("sum"))
        assertFailsWith<VariableException.UndefinedVariable> { screenStore.setMultipleValues(mapOf("nope" to 1)) }
    }

    // endregion
}
