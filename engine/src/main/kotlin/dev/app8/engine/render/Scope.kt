package dev.app8.engine.render

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import dev.app8.engine.core.vars.Cancellable
import dev.app8.engine.core.vars.ChangePublisher
import dev.app8.engine.core.vars.Variable
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStoreProtocol

/**
 * Snapshot-state mirror of a variable store's change stream: one counter per variable name.
 *
 * This is the Compose version of shoppu-web `render/store.ts` read tracking. Resolving a
 * property through [Scope.tracked] reads the counter of every variable the expression
 * touches, so the snapshot system records exactly those dependencies; a change bumps only
 * that variable's counter and recomposes (or re-lays-out) only the readers. Writes always go
 * to the real store.
 */
@Stable
internal class VariableSignals(store: VariableStoreProtocol) {
    private val counters = HashMap<String, MutableIntState>()
    /** Bumped on every change and on reparent: read by whole-store reads (`$parent`, getAllValues). */
    private val any = mutableIntStateOf(0)
    private val subscription: Cancellable = store.anyVariableChanged.subscribe { name -> bump(name) }

    /** Records a dependency on [name] in the current snapshot observer (composition, layout, derived state). */
    fun read(name: String) {
        counter(name).intValue
    }

    fun readAll() {
        any.intValue
    }

    private fun counter(name: String): MutableIntState =
        counters[name] ?: mutableIntStateOf(0).also { counters[name] = it }

    private fun bump(name: String) {
        any.intValue++
        if (name.isEmpty()) {
            // Reparent: every name may resolve differently now.
            counters.values.forEach { it.intValue++ }
        } else {
            counters[name]?.let { it.intValue++ }
        }
    }

    fun dispose() = subscription.cancel()
}

/**
 * A variable scope (app → screen → component), with a tracked context for rendering and an
 * untracked one for actions (iOS `getVariableContext()`).
 */
@Stable
internal class Scope(val store: VariableStoreProtocol, val parent: Scope?) {
    val signals = VariableSignals(store)
    val tracked: VariableContext = VariableContext(TrackingStore(store, signals))
    val untracked: VariableContext = VariableContext(store)

    fun dispose() = signals.dispose()
}

/** Read-only view of [delegate] that reports every read to [signals]; mutations pass through. */
internal class TrackingStore(
    private val delegate: VariableStoreProtocol,
    private val signals: VariableSignals,
) : VariableStoreProtocol {
    override fun getValue(name: String): Any? {
        signals.read(name)
        return delegate.getValue(name)
    }

    override fun hasVariable(name: String): Boolean {
        signals.read(name)
        return delegate.hasVariable(name)
    }

    override fun getAllValues(): Map<String, Any?> {
        signals.readAll()
        return delegate.getAllValues()
    }

    override fun getAllVariables(): Map<String, Variable> {
        signals.readAll()
        return delegate.getAllVariables()
    }

    /** `$parent` reads the parent's values; parent changes reach this store's stream, so track all. */
    override fun getParentStore(): VariableStoreProtocol? {
        val parent = delegate.getParentStore() ?: return null
        return object : VariableStoreProtocol by parent {
            override fun getAllValues(): Map<String, Any?> {
                signals.readAll()
                return parent.getAllValues()
            }
        }
    }

    override fun defineVariable(name: String, definition: VariableDefinition) = delegate.defineVariable(name, definition)
    override fun setValue(name: String, value: Any?) = delegate.setValue(name, value)
    override fun setMultipleValues(updates: Map<String, Any?>) = delegate.setMultipleValues(updates)
    override fun setExternalValue(name: String, value: Any) = delegate.setExternalValue(name, value)
    override val anyVariableChanged: ChangePublisher<String> get() = delegate.anyVariableChanged
    override fun reset() = delegate.reset()
}
