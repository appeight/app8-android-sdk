package dev.app8.engine.core.vars

/** Runtime variable instance (Swift `Variable`). Confine to the main thread. */
public class Variable(public val name: String, definition: VariableDefinition) {
    public val type: VariableType = definition.type
    public val isComputed: Boolean = definition.computed != null
    public val expression: String? = definition.computed

    public var value: Any? = if (isComputed) null else definition.rawInitialValue ?: definition.type.defaultValue()
        private set

    public var dependencies: Set<String> = emptySet()
        private set

    /**
     * Value captured when the variable was defined (after evaluating an expression /
     * datasource / injected-param initial value). Restored by the `resetVariables` action.
     */
    public var initialValue: Any? = null
        private set

    private val valueSubject = PassthroughSubject<Pair<Any?, Any?>>()

    /** Emits `(oldValue, newValue)` synchronously whenever the value actually changes. */
    public val valueChanged: ChangePublisher<Pair<Any?, Any?>> = valueSubject

    /**
     * Sets the value of a non-computed variable.
     * @throws VariableException.CannotModifyComputedVariable for computed variables
     * @throws VariableException.TypeMismatch when [newValue] does not match [type]
     */
    public fun setValue(newValue: Any?) {
        if (isComputed) throw VariableException.CannotModifyComputedVariable(name)
        val normalized = Values.normalize(newValue)
        if (normalized != null && !type.validate(normalized)) {
            throw VariableException.TypeMismatch(name, type, Values.swiftTypeName(normalized))
        }
        assign(normalized)
    }

    internal fun setComputedValue(newValue: Any?, dependencies: Set<String>) {
        if (!isComputed) return
        this.dependencies = dependencies
        assign(newValue)
    }

    /**
     * Snapshots the current value as the `resetVariables` target. The stores call it once the
     * definition-time value is final; hosts call it again after injecting navigation params.
     */
    public fun captureInitialValue() {
        initialValue = value
    }

    /** Restores the captured initial value (no-op for computed variables; their store recomputes them). */
    internal fun restoreInitialValue() {
        if (isComputed) return
        setValueUnchecked(initialValue)
    }

    /** Sets the value without validation (internal batch updates / expression initial values). */
    internal fun setValueUnchecked(newValue: Any?) {
        assign(Values.normalize(newValue))
    }

    private fun assign(newValue: Any?) {
        val oldValue = value
        if (valuesEqual(oldValue, newValue)) return
        value = newValue
        valueSubject.send(oldValue to newValue)
    }

    private companion object {
        /**
         * Equality for known scalar types only; collections (and [Values.Null]) always compare
         * unequal so a change is always notified — the iOS "safe default".
         */
        fun valuesEqual(lhs: Any?, rhs: Any?): Boolean {
            if (lhs == null && rhs == null) return true
            if (lhs == null || rhs == null) return false
            if (lhs is Boolean && rhs is Boolean) return lhs == rhs
            val li = Values.asInt(lhs)
            val ri = Values.asInt(rhs)
            if (li != null && ri != null) return li == ri
            val ld = Values.asDouble(lhs)
            val rd = Values.asDouble(rhs)
            if (ld != null && rd != null) return ld == rd
            if (lhs is String && rhs is String) return lhs == rhs
            return false
        }
    }
}
