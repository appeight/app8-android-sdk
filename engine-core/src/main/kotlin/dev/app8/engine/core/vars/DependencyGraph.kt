package dev.app8.engine.core.vars

/** Tracks dependencies between computed variables for update ordering (Swift `DependencyGraph`). */
public class DependencyGraph {
    /** variable → variables that depend on it */
    private val dependents = LinkedHashMap<String, MutableSet<String>>()

    /** variable → variables it depends on */
    private val dependencies = LinkedHashMap<String, MutableSet<String>>()

    /** Records that [to] depends on [from]: when `from` changes, `to` must update. */
    public fun addDependency(from: String, to: String) {
        dependents.getOrPut(from) { LinkedHashSet() }.add(to)
        dependencies.getOrPut(to) { LinkedHashSet() }.add(from)
    }

    /** Removes all dependency edges of [variable]. */
    public fun removeDependencies(variable: String) {
        for (set in dependents.values) set.remove(variable)
        dependencies[variable]?.forEach { dependents[it]?.remove(variable) }
        dependencies.remove(variable)
    }

    /** All variables that transitively depend on [variable]. */
    public fun getDependents(variable: String): Set<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<String>().apply { add(variable) }
        val visited = HashSet<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            dependents[current]?.forEach {
                result.add(it)
                queue.add(it)
            }
        }
        return result
    }

    /** Topological update order for [variables] (dependencies first); cycles are skipped. */
    public fun getUpdateOrder(variables: Set<String>): List<String> {
        val result = ArrayList<String>()
        val visited = HashSet<String>()
        val visiting = HashSet<String>()

        fun visit(variable: String) {
            if (variable in visited || variable in visiting) return
            visiting.add(variable)
            dependencies[variable]?.forEach { if (it in variables) visit(it) }
            visiting.remove(variable)
            visited.add(variable)
            result.add(variable)
        }

        for (v in variables) visit(v)
        return result
    }

    /** Clears all dependencies. */
    public fun reset() {
        dependents.clear()
        dependencies.clear()
    }
}
