package dev.app8.engine.render

import androidx.compose.runtime.snapshots.Snapshot
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.core.vars.PropertyResolver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Read tracking: resolving through a [Scope]'s tracked context records exactly the
 * variables read, so only their changes invalidate the reader (web `store.ts` semantics,
 * on the Compose snapshot system).
 */
class ReadTrackingTest {
    private val resolver = PropertyResolver()

    /** The state objects read while resolving [expr]. */
    private fun reads(scope: Scope, expr: String): Set<Any> {
        val read = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot { read.add(it) }
        try {
            snapshot.enter { resolver.resolve(expr, scope.tracked) }
        } finally {
            snapshot.dispose()
        }
        return read
    }

    /** Whether a change between two resolutions invalidates [expr]'s reads. */
    private fun invalidates(scope: Scope, expr: String, change: () -> Unit): Boolean {
        val before = reads(scope, expr)
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        change()
        Snapshot.sendApplyNotifications()
        handle.dispose()
        return before.any { it in changed }
    }

    private fun store(): VariableStore = VariableStore().apply {
        defineVariable("a", VariableDefinition(VariableType.NUMBER, 1L))
        defineVariable("b", VariableDefinition(VariableType.NUMBER, 2L))
    }

    @Test
    fun `a change invalidates readers of that variable only`() {
        val s = store()
        val scope = Scope(s, null)
        assertTrue(invalidates(scope, "{{a + 1}}") { s.setValue("a", 5L) })
        assertEquals(false, invalidates(scope, "{{a + 1}}") { s.setValue("b", 7L) })
        assertEquals(6L, resolver.resolve("{{a + 1}}", VariableContext(s)))
    }

    @Test
    fun `parent changes reach readers of a child scope`() {
        val app = store()
        val screen = ScopedVariableStore(app).apply { defineVariable("c", VariableDefinition(VariableType.STRING, "x")) }
        val scope = Scope(screen, Scope(app, null))
        assertTrue(invalidates(scope, "{{a}}") { app.setValue("a", 3L) })
        assertEquals(false, invalidates(scope, "{{c}}") { app.setValue("a", 4L) })
        assertTrue(invalidates(scope, "{{c}}") { screen.setValue("c", "y") })
    }

    @Test
    fun `a shadowing local variable is tracked in its own scope`() {
        val app = store()
        val child = ScopedVariableStore(app).apply { defineVariable("a", VariableDefinition(VariableType.NUMBER, 100L)) }
        val scope = Scope(child, null)
        assertEquals(100L, resolver.resolve("{{a}}", scope.tracked))
        assertTrue(invalidates(scope, "{{a}}") { child.setValue("a", 101L) })
    }

    @Test
    fun `untracked context records nothing`() {
        val scope = Scope(store(), null)
        val read = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot { read.add(it) }
        snapshot.enter { resolver.resolve("{{a + b}}", scope.untracked) }
        snapshot.dispose()
        assertTrue(read.isEmpty())
    }
}
