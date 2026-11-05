package dev.app8.engine.core.vars

import java.lang.ref.WeakReference

/**
 * Variable store with parent-scope chain lookup (Swift `ScopedVariableStore`), used for screen-
 * and component-level variables: app [VariableStore] → screen scope → component scope.
 *
 * - Reads fall through to [parent] when the local value is `null` (an explicit [Values.Null]
 *   shadows the parent, as `NSNull` does on iOS).
 * - Writes go to the scope that defines the variable.
 * - Parent changes are forwarded to [anyVariableChanged] after recomputing local computed
 *   variables that depend on them; [reparent] emits `""`.
 *
 * Lifetime: the parent holds this store's forwarding listener through a weak reference (like the
 * `[weak self]` sink on iOS), so an unreachable child stops receiving updates once collected.
 * Call [detach] for deterministic teardown. Unlike iOS, [parent] itself is a strong reference.
 */
public class ScopedVariableStore(parent: VariableStoreProtocol? = null) : VariableStoreProtocol {

    /** Parent store for scope-chain lookup. */
    public var parent: VariableStoreProtocol? = parent
        private set

    private val localVariables = LinkedHashMap<String, Variable>()
    private val support = ComputedSupport(this, localVariables)
    private var isUpdating = false
    private val anyVariableChangedSubject = PassthroughSubject<String>()
    private val cancellables = ArrayList<Cancellable>()
    private var parentForwarding: Cancellable? = null
    private var parentPropagationFilter: ((String) -> Boolean)? = null

    /** Optional diagnostics sink; inherited from a [ScopedVariableStore] / [VariableStore] parent. */
    public var logger: VariableLogger? = when (parent) {
        is ScopedVariableStore -> parent.logger
        is VariableStore -> parent.logger
        else -> null
    }

    override val anyVariableChanged: ChangePublisher<String> get() = anyVariableChangedSubject

    init {
        subscribeToParent(parent)
    }

    /**
     * Restricts which parent variable changes propagate to this store; `null` allows all (default).
     * The reparent signal `""` always propagates.
     */
    public fun setParentPropagationFilter(filter: ((String) -> Boolean)?) {
        parentPropagationFilter = filter
        subscribeToParent(parent)
    }

    /**
     * Swaps the parent store, keeping local variables. [newParent] must not be this store or one of
     * its descendants. Always emits `""` so observers re-resolve against the new parent.
     */
    public fun reparent(newParent: VariableStoreProtocol) {
        if (newParent === this) return
        if (parent !== newParent) {
            parent = newParent
            subscribeToParent(newParent)
        }
        anyVariableChangedSubject.send("")
    }

    /** Stops forwarding parent changes (Android addition for deterministic teardown). */
    public fun detach() {
        parentForwarding?.cancel()
        parentForwarding = null
    }

    private fun subscribeToParent(parent: VariableStoreProtocol?) {
        parentForwarding?.cancel()
        parentForwarding = null
        if (parent == null) return
        val weakSelf = WeakReference(this)
        var subscription: Cancellable? = null
        subscription = parent.anyVariableChanged.subscribe { variableName ->
            val self = weakSelf.get()
            if (self == null) {
                subscription?.cancel()
                return@subscribe
            }
            if (variableName.isNotEmpty()) {
                val filter = self.parentPropagationFilter
                if (filter != null && !filter(variableName)) return@subscribe
                // Recompute local computed variables depending on the changed parent variable
                // (cross-scope edges are recorded at defineVariable time). Errors are swallowed (`try?`).
                try {
                    self.updateDependentVariables(variableName)
                } catch (_: Exception) {
                }
            }
            self.anyVariableChangedSubject.send(variableName)
        }
        parentForwarding = subscription
    }

    // region Definition

    override fun defineVariable(name: String, definition: VariableDefinition) {
        val variable = Variable(name, definition)
        support.initialize(name, variable, definition, logger)

        val value = variable.value
        if (value != null && value !== Values.Null) {
            if (!definition.type.validate(value)) {
                val actual = VariableType.inferType(value)
                logger?.warning(
                    "Variable '$name' declared as ${definition.type.rawValue} but received ${actual.rawValue}: " +
                        Values.describe(value).take(200),
                )
            }
        } else if (!variable.isComputed && definition.computed == null) {
            logger?.debug("Variable '$name' (${definition.type.rawValue}) initialized without a value")
        }

        cancellables.add(variable.valueChanged.subscribe { anyVariableChangedSubject.send(name) })
        localVariables[name] = variable
    }

    // endregion

    // region Access (scope chain)

    override fun getValue(name: String): Any? = localVariables[name]?.value ?: parent?.getValue(name)

    override fun setValue(name: String, value: Any?) {
        val variable = localVariables[name]
        if (variable != null) {
            variable.setValue(value)
            if (!isUpdating) updateDependentVariables(name)
            return
        }
        if (parent?.hasVariable(name) == true) {
            parent?.setValue(name, value)
            return
        }
        throw VariableException.UndefinedVariable(name)
    }

    /** See [VariableStore.setMultipleValues] for the (faithfully ported) failure-mode caveat. */
    override fun setMultipleValues(updates: Map<String, Any?>) {
        val localChanges = LinkedHashSet<String>()
        val parentUpdates = LinkedHashMap<String, Any?>()
        isUpdating = true
        for ((name, value) in updates) {
            val variable = localVariables[name]
            if (variable != null) {
                variable.setValue(value)
                localChanges.add(name)
            } else if (parent?.hasVariable(name) == true) {
                parentUpdates[name] = value
            } else {
                throw VariableException.UndefinedVariable(name)
            }
        }
        if (parentUpdates.isNotEmpty()) parent?.setMultipleValues(parentUpdates)
        support.updateDependentVariablesForMultiple(localChanges)
        isUpdating = false
    }

    override fun hasVariable(name: String): Boolean =
        localVariables.containsKey(name) || (parent?.hasVariable(name) ?: false)

    override fun setExternalValue(name: String, value: Any) {
        if (localVariables.containsKey(name)) {
            try {
                setValue(name, value)
            } catch (e: Exception) {
                logger?.error("ScopedVariableStore.setExternalValue failed for '$name': ${e.message}")
            }
        } else {
            parent?.setExternalValue(name, value)
        }
    }

    override fun getAllValues(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(parent?.getAllValues() ?: emptyMap())
        for ((key, variable) in localVariables) result[key] = variable.value
        return result
    }

    override fun getAllVariables(): Map<String, Variable> {
        val result = LinkedHashMap<String, Variable>(parent?.getAllVariables() ?: emptyMap())
        result.putAll(localVariables)
        return result
    }

    /** Only this scope's variables (not the parent's). */
    public fun getLocalVariables(): Map<String, Variable> = LinkedHashMap(localVariables)

    override fun getParentStore(): VariableStoreProtocol? = parent

    // endregion

    /** Clears local variables only; the parent and parent forwarding are untouched. */
    override fun reset() {
        localVariables.clear()
        support.graph.reset()
        cancellables.forEach { it.cancel() }
        cancellables.clear()
    }

    private fun updateDependentVariables(changedVariable: String) {
        isUpdating = true
        try {
            support.updateDependentVariables(changedVariable)
        } finally {
            isUpdating = false
        }
    }
}
