package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.DependencyExtractor
import dev.app8.engine.core.expr.ExpressionEvaluator
import dev.app8.engine.core.expr.ExpressionParser

/**
 * Computed-variable machinery shared by [VariableStore] and [ScopedVariableStore] (the Swift
 * classes duplicate this code verbatim). [variables] is the store's own (local) variable map.
 */
internal class ComputedSupport(
    private val store: VariableStoreProtocol,
    private val variables: Map<String, Variable>,
) {
    val parser = ExpressionParser()
    val evaluator = ExpressionEvaluator()
    val extractor = DependencyExtractor()
    val graph = DependencyGraph()

    /** Shared part of `defineVariable`: computes the variable's initial value. */
    fun initialize(name: String, variable: Variable, definition: VariableDefinition, logger: VariableLogger?) {
        if (variable.isComputed) {
            val expression = variable.expression ?: throw VariableException.InvalidExpression(
                "No expression provided for computed variable", IllegalStateException(),
            )
            val node = parser.parse(expression)
            val dependencies = extractor.extractDependencies(node)
            checkCircularDependency(name, dependencies)
            for (dependency in dependencies) graph.addDependency(dependency, name)
            updateComputedVariable(variable)
        } else {
            val raw = definition.rawInitialValue
            if (raw is String && raw.contains("{{")) {
                // initialValue is an expression string — evaluate it against the current context.
                try {
                    val node = parser.parse(raw)
                    val evaluated = evaluator.evaluate(node, VariableContext(store))
                    if (evaluated != null) variable.setValueUnchecked(evaluated)
                } catch (e: Exception) {
                    logger?.error("Failed to evaluate initialValue expression '$raw' for '$name': ${e.message}")
                }
            }
        }
    }

    fun updateDependentVariables(changedVariable: String) {
        val order = graph.getUpdateOrder(graph.getDependents(changedVariable))
        for (name in order) {
            val variable = variables[name]
            if (variable != null && variable.isComputed) updateComputedVariable(variable)
        }
    }

    fun updateDependentVariablesForMultiple(changedVariables: Set<String>) {
        val all = LinkedHashSet<String>()
        for (changed in changedVariables) all.addAll(graph.getDependents(changed))
        for (name in graph.getUpdateOrder(all)) {
            val variable = variables[name]
            if (variable != null && variable.isComputed) updateComputedVariable(variable)
        }
    }

    fun updateComputedVariable(variable: Variable) {
        val expression = variable.expression ?: return
        try {
            val node = parser.parse(expression)
            val dependencies = extractor.extractDependencies(node)
            val newValue = evaluator.evaluate(node, VariableContext(store))
            variable.setComputedValue(newValue, dependencies)
        } catch (e: Exception) {
            throw VariableException.ComputationError(expression, e)
        }
    }

    private fun checkCircularDependency(name: String, dependencies: Set<String>) {
        var visited = HashSet<String>()
        var path = ArrayList<String>()

        fun hasCircularDependency(varName: String): Boolean {
            if (varName in path) return true
            if (varName in visited) return false
            visited.add(varName)
            path.add(varName)
            val variable = variables[varName]
            if (variable != null && variable.isComputed) {
                val expression = variable.expression ?: return false
                val deps = try {
                    extractor.extractDependencies(parser.parse(expression))
                } catch (_: Exception) {
                    return false
                }
                for (dep in deps) if (hasCircularDependency(dep)) return true
            }
            path.removeAt(path.size - 1)
            return false
        }

        for (dependency in dependencies) {
            if (dependency == name) throw VariableException.CircularDependency(listOf(name))
            path = arrayListOf(name)
            visited = HashSet()
            if (hasCircularDependency(dependency)) throw VariableException.CircularDependency(path.toList())
        }
    }
}

/** Main (app-scope) variable store with dependency tracking (Swift `VariableStore`). */
public class VariableStore : VariableStoreProtocol {
    private val variables = LinkedHashMap<String, Variable>()
    private val support = ComputedSupport(this, variables)
    private var isUpdating = false
    private val anyVariableChangedSubject = PassthroughSubject<String>()
    private val cancellables = ArrayList<Cancellable>()

    /** Optional diagnostics sink. */
    public var logger: VariableLogger? = null

    /**
     * Storage for `persist: true` variables. Set it before defining variables: a stored value
     * replaces the initial value at definition time, and every later change is saved.
     */
    public var persistence: VariablePersistence? = null

    private val persisted = HashSet<String>()

    override val anyVariableChanged: ChangePublisher<String> get() = anyVariableChangedSubject

    override fun defineVariable(name: String, definition: VariableDefinition) {
        val variable = Variable(name, definition)
        support.initialize(name, variable, definition, logger)
        cancellables.add(variable.valueChanged.subscribe { anyVariableChangedSubject.send(name) })
        variable.captureInitialValue()
        variables[name] = variable
        if (definition.persist == true) setUpPersistence(name, variable)
    }

    /** Restores a stored value (`initialValue` stays the reset target) and saves later changes. */
    private fun setUpPersistence(name: String, variable: Variable) {
        if (variable.isComputed) {
            logger?.warning("variable.persist.scope", "`persist` on computed variable '$name' is ignored")
            return
        }
        val store = persistence ?: return
        persisted.add(name)
        store.stored(name)?.let { raw ->
            val value = PersistCodec.decode(raw)
            if (value == null || value === Values.Null || !variable.type.validate(value)) {
                logger?.warning(
                    "variable.persist.typeMismatch",
                    "Stored value of '$name' is not ${variable.type.rawValue}; using initialValue",
                )
                store.remove(name)
            } else {
                variable.setValueUnchecked(value)
                // Dependents already defined (ordering puts them later, but be safe).
                runCatching { support.updateDependentVariables(name) }
            }
        }
        cancellables.add(
            variable.valueChanged.subscribe { (_, new) ->
                PersistCodec.encode(new)?.let { store.save(name, it) } ?: store.remove(name)
            },
        )
    }

    override fun getValue(name: String): Any? = variables[name]?.value

    override fun setValue(name: String, value: Any?) {
        val variable = variables[name] ?: throw VariableException.UndefinedVariable(name)
        variable.setValue(value)
        if (!isUpdating) updateDependentVariables(name)
    }

    /**
     * Unknown names reject the whole batch before anything is written. If a write still throws
     * midway (type mismatch), the store leaves batch mode and recomputes dependents of what was
     * already written before rethrowing, so a later [setValue] always recomputes dependents.
     */
    override fun setMultipleValues(updates: Map<String, Any?>) {
        updates.keys.firstOrNull { !variables.containsKey(it) }?.let { throw VariableException.UndefinedVariable(it) }
        val changed = LinkedHashSet<String>()
        isUpdating = true
        try {
            for ((name, value) in updates) {
                variables.getValue(name).setValue(value)
                changed.add(name)
            }
        } catch (e: Exception) {
            isUpdating = false
            runCatching { support.updateDependentVariablesForMultiple(changed) }
            throw e
        }
        isUpdating = false
        support.updateDependentVariablesForMultiple(changed)
    }

    override fun hasVariable(name: String): Boolean = variables.containsKey(name)

    override fun setExternalValue(name: String, value: Any) {
        val variable = variables[name] ?: return
        try {
            variable.setValue(value)
            if (!isUpdating) updateDependentVariables(name)
        } catch (e: Exception) {
            logger?.error("VariableStore.setExternalValue failed for '$name': ${e.message}")
        }
    }

    override fun getAllValues(): Map<String, Any?> = variables.mapValuesTo(LinkedHashMap()) { it.value.value }

    override fun getAllVariables(): Map<String, Variable> = LinkedHashMap(variables)

    override fun getParentStore(): VariableStoreProtocol? = null

    override fun reset() {
        variables.clear()
        persisted.clear()
        support.graph.reset()
        cancellables.forEach { it.cancel() }
        cancellables.clear()
    }

    override fun resetToInitialValues() {
        val names = variables.filterValues { !it.isComputed }.keys.toList()
        isUpdating = true
        for (name in names) variables[name]?.restoreInitialValue()
        isUpdating = false
        // `resetVariables` also clears the stored value of a persisted variable.
        persistence?.let { store -> for (name in names) if (name in persisted) store.remove(name) }
        try {
            support.updateDependentVariablesForMultiple(names.toSet())
        } catch (e: Exception) {
            logger?.error("resetToInitialValues: failed to recompute dependents: ${e.message}")
        }
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
