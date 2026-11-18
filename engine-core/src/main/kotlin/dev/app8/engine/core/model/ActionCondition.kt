package dev.app8.engine.core.model

import dev.app8.engine.core.vars.Values

/**
 * The DSL's one boolean coercion (app8-dsl docs/variables.md, "Boolean coercion"), for every
 * boolean: `if` conditions, `isHidden`, `isEnabled`, toggles.
 */
public object BoolCoercion {
    /**
     * `true`, non-zero numbers, `"true"` / `"1"`, non-empty lists and maps; any other string, `0`
     * and JSON null are false. `null`: missing, or an expression that did not resolve (its `{{…}}`
     * text): the property keeps its default.
     */
    public fun coerce(value: Any?): Boolean? = when (value) {
        null -> null
        Values.Null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> if (value.contains("{{")) null else value.lowercase() == "true" || value == "1"
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> false
    }
}

/** The `if` action's condition (actions.md, `if`): the boolean coercion; unevaluable runs `else`. */
public object ActionCondition {
    public fun holds(value: Any?): Boolean = BoolCoercion.coerce(value) ?: false
}
