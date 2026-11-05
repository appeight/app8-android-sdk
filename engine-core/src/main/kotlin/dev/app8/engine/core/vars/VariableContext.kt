package dev.app8.engine.core.vars

/**
 * Variable lookup for expression evaluation (Swift `VariableContext`): a store plus immutable
 * overlays (loop variables such as `item`). `$parent` resolves to the parent scope's values.
 */
public class VariableContext private constructor(
    private val store: VariableStoreProtocol,
    private val overlays: Map<String, Any?>,
) {
    public constructor(store: VariableStoreProtocol) : this(store, emptyMap())

    /** New context with [name] bound to [value], shadowing any store variable. */
    public fun overlaying(name: String, value: Any?): VariableContext =
        VariableContext(store, LinkedHashMap(overlays).apply { put(name, value) })

    /** Value of [name]: overlay first, then `$parent` (parent scope as a map), then the store. */
    public fun getValue(name: String): Any? {
        if (overlays.containsKey(name)) return overlays[name]
        if (name == "\$parent") return store.getParentStore()?.getAllValues()
        return store.getValue(name)
    }

    /** True when [name] is defined (even if its value is `null`). */
    public fun variableExists(name: String): Boolean = overlays.containsKey(name) || store.hasVariable(name)

    public fun getAllValues(): Map<String, Any?> = store.getAllValues()
}
