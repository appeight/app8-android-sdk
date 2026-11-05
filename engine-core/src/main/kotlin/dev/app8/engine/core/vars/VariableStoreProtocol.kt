package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.DependencyExtractor
import dev.app8.engine.core.expr.ExpressionParser

/**
 * Variable storage (Swift `VariableStoreProtocol`).
 *
 * Threading: like the `@MainActor` iOS types, implementations are confined to a single thread
 * (the main thread) and do no locking. Change notifications are delivered synchronously from the
 * mutating call.
 */
public interface VariableStoreProtocol {
    /** Defines (or redefines) a variable. Computed variables are evaluated immediately. */
    public fun defineVariable(name: String, definition: VariableDefinition)

    /**
     * Defines several variables, ordering them so that each computed variable (or expression
     * `initialValue`) is defined after its same-batch dependencies. Dependencies outside the
     * batch (parent scopes) are ignored for ordering.
     */
    public fun defineVariables(definitions: Map<String, VariableDefinition>) {
        val parser = ExpressionParser()
        val extractor = DependencyExtractor()
        val sorted = ArrayList<Pair<String, VariableDefinition>>()
        val visited = HashSet<String>()

        fun visit(name: String) {
            if (name in visited) return
            val definition = definitions[name] ?: return
            visited.add(name)
            val raw = definition.rawInitialValue
            val expression = definition.computed ?: (raw as? String)?.takeIf { it.contains("{{") }
            if (expression != null) {
                val node = runCatching { parser.parse(expression) }.getOrNull()
                if (node != null) {
                    for (dep in extractor.extractDependencies(node)) if (definitions.containsKey(dep)) visit(dep)
                }
            }
            sorted.add(name to definition)
        }

        for (name in definitions.keys) visit(name)
        for ((name, definition) in sorted) defineVariable(name, definition)
    }

    /** Current value, or `null` when undefined (or defined without a value). */
    public fun getValue(name: String): Any?

    /** @throws VariableException */
    public fun setValue(name: String, value: Any?)

    /** Batch update; dependents are recomputed once, in dependency order. @throws VariableException */
    public fun setMultipleValues(updates: Map<String, Any?>)

    /** Streaming fast path: sets a value from an external source; unknown names are ignored, errors logged. */
    public fun setExternalValue(name: String, value: Any)

    public fun hasVariable(name: String): Boolean

    public fun getAllValues(): Map<String, Any?>

    public fun getAllVariables(): Map<String, Variable>

    /** Emits the variable name on every change (`""` signals a reparent in scoped stores). */
    public val anyVariableChanged: ChangePublisher<String>

    /** Parent store, for `$parent` access in nested scopes. */
    public fun getParentStore(): VariableStoreProtocol?

    /** Removes every variable from the store (teardown / screen replacement). */
    public fun reset()

    /**
     * Restores every local (non-computed) variable to the value it had right after definition,
     * then recomputes local computed dependents. Variables are kept; parent stores are untouched.
     */
    public fun resetToInitialValues()
}
