package dev.app8.engine.core.vars

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Variable type of the DSL variable system (Swift `VariableType`). */
@Serializable
public enum class VariableType(public val rawValue: String) {
    @SerialName("boolean") BOOLEAN("boolean"),
    @SerialName("string") STRING("string"),
    @SerialName("number") NUMBER("number"),
    @SerialName("array") ARRAY("array"),
    @SerialName("object") OBJECT("object");

    /**
     * True when [value] matches this type; `null` always validates. As on iOS (where `Bool`
     * bridges to `NSNumber`), a boolean also validates as [NUMBER]; [Values.Null] matches no type.
     */
    public fun validate(value: Any?): Boolean {
        if (value == null) return true
        return when (this) {
            BOOLEAN -> value is Boolean
            STRING -> value is String
            NUMBER -> value is Number || value is Boolean
            ARRAY -> value is List<*>
            OBJECT -> value is Map<*, *>
        }
    }

    /** Default value for a variable of this type without `initialValue`. */
    public fun defaultValue(): Any = when (this) {
        BOOLEAN -> false
        STRING -> ""
        NUMBER -> 0L
        ARRAY -> emptyList<Any?>()
        OBJECT -> emptyMap<String, Any?>()
    }

    /** Swift prints the case name (`expected number`). */
    override fun toString(): String = rawValue

    public companion object {
        public fun fromRawValue(raw: String): VariableType? = entries.firstOrNull { it.rawValue == raw }

        /** Infers the type of a runtime value; unknown values (and `null`) infer as [STRING]. */
        public fun inferType(value: Any?): VariableType = when (value) {
            null -> STRING
            is Boolean -> BOOLEAN
            is String -> STRING
            is Number -> NUMBER
            is List<*> -> ARRAY
            is Map<*, *> -> OBJECT
            else -> STRING
        }
    }
}
