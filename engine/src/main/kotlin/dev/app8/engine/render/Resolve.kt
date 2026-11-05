package dev.app8.engine.render

import dev.app8.engine.core.model.LocalizedString
import dev.app8.engine.core.vars.LocalizedText
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import kotlinx.serialization.json.JsonElement

// Property resolution against a node's scope. In composition / layout use the tracked
// context (default) so reads become snapshot dependencies; actions pass `untracked`.

internal fun RenderNode.resolve(raw: String, ctx: VariableContext = scope.tracked): Any? = try {
    val v = runtime.propertyResolver.resolve(raw, ctx)
    if (v === Values.Null) null else v
} catch (e: Exception) {
    runtime.diagnostics.warn("expression", "'$raw' in $key: ${e.message}")
    null
}

internal fun RenderNode.string(raw: String?, ctx: VariableContext = scope.tracked): String? {
    raw ?: return null
    if (!runtime.propertyResolver.containsExpression(raw)) return raw
    return try {
        runtime.propertyResolver.resolveToString(raw, ctx)
    } catch (e: Exception) {
        runtime.diagnostics.warn("expression", "'$raw' in $key: ${e.message}")
        ""
    }
}

internal fun RenderNode.localized(text: LocalizedString, ctx: VariableContext = scope.tracked): String = try {
    runtime.propertyResolver.resolveLocalizedToString(
        when (text) {
            is LocalizedString.Literal -> LocalizedText.Literal(text.text)
            is LocalizedString.Key -> LocalizedText.Key(text.key)
        },
        ctx,
    )
} catch (e: Exception) {
    text.rawValue
}

/** iOS `resolvePropertyToBool`: booleans, `"true"`/`"false"`, numbers ≠ 0. */
internal fun RenderNode.bool(raw: String?, ctx: VariableContext = scope.tracked): Boolean? {
    raw ?: return null
    val v: Any? = if (runtime.propertyResolver.containsExpression(raw)) resolve(raw, ctx) else raw
    return Coerce.bool(v)
}

internal fun RenderNode.number(raw: String?, ctx: VariableContext = scope.tracked): Double? {
    raw ?: return null
    val v: Any? = if (runtime.propertyResolver.containsExpression(raw)) resolve(raw, ctx) else raw
    return Coerce.double(v)
}

/** A JSON value from an action/payload with `{{…}}` strings resolved (iOS `resolvePayload`). */

internal object Coerce {
    fun bool(v: Any?): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.trim().lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0", "" -> false
            else -> null
        }
        else -> null
    }

    fun double(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }
}
