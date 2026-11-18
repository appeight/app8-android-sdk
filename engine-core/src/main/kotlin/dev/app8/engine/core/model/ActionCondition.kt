package dev.app8.engine.core.model

import dev.app8.engine.core.vars.Values

/** The `if` action's condition (app8-dsl docs/actions.md, `if`). */
public object ActionCondition {
    /**
     * Whether a resolved condition holds: `true`, a non-zero number, a non-empty string other
     * than `"false"`, a non-empty array or object. `null` and a condition that could not be
     * evaluated (its `{{…}}` text comes back) do not.
     */
    public fun holds(value: Any?): Boolean = when (value) {
        null, Values.Null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.trim().let { it.isNotEmpty() && it != "false" && !it.contains("{{") }
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }
}
