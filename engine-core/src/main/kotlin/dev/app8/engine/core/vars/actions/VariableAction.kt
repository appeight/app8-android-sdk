package dev.app8.engine.core.vars.actions

import dev.app8.engine.core.vars.AnyCodableValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** The variable action types (the variable subset of iOS `DSL.Model.Action.Type`). */
public enum class VariableActionType(public val rawValue: String) {
    UPDATE_VARIABLE("updateVariable"),
    INCREMENT_VARIABLE("incrementVariable"),
    TOGGLE_ARRAY_VALUE("toggleArrayValue"),
    APPEND_TO_ARRAY("appendToArray"),
    INCREMENT_ARRAY_ITEM("incrementArrayItem"),
    UPDATE_MULTIPLE_VARIABLES("updateMultipleVariables"),
    RESET_VARIABLES("resetVariables"),
    ;

    public companion object {
        public fun fromRawValue(raw: String): VariableActionType? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The fields of a DSL action that [VariableActionHandler] reads — a minimal stand-in for the
 * iOS `DSL.Model.Action` until the DSL model port lands. [type] is the raw action type string;
 * non-variable types are ignored by the handler.
 */
@Serializable
public data class VariableAction(
    val type: String,
    /** Variable name for single-variable actions. */
    val variableName: String? = null,
    /** Value (strings may contain `{{expressions}}`); JSON `null` decodes as absent. */
    val value: AnyCodableValue? = null,
    /** Increment/decrement amount. */
    val by: Double? = null,
    /** toggleArrayValue / incrementArrayItem: key to match object elements by (e.g. "id"). */
    val matchBy: String? = null,
    /** incrementArrayItem: numeric field to adjust (default "quantity"). */
    val field: String? = null,
    /** updateMultipleVariables: name → value. */
    val updates: Map<String, AnyCodableValue>? = null,
) {
    public val actionType: VariableActionType? get() = VariableActionType.fromRawValue(type)

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Decodes the variable-related fields of a DSL action object, ignoring all others. */
        public fun fromJson(element: JsonElement): VariableAction = json.decodeFromJsonElement(serializer(), element)

        public fun fromJson(text: String): VariableAction = fromJson(json.parseToJsonElement(text))
    }
}
