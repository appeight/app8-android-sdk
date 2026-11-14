package dev.app8.engine.core.vars

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder

/**
 * A JSON value carried through the DSL as a canonical [Values] value (Swift `AnyCodableValue`).
 * JSON `null` decodes to [Values.Null]; note that an optional *field* holding `null` decodes to an
 * absent value, like Swift's `decodeIfPresent`.
 */
@Serializable(with = AnyCodableValueSerializer::class)
public class AnyCodableValue(value: Any) {
    /** The canonical value (never Kotlin `null`). */
    public val value: Any = Values.normalize(value) ?: Values.Null

    override fun equals(other: Any?): Boolean = other is AnyCodableValue && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "AnyCodableValue(${Values.describe(value)})"

    public companion object {
        public fun fromJson(element: JsonElement): AnyCodableValue = AnyCodableValue(Values.fromJson(element))
    }
}

/** JSON-only serializer for [AnyCodableValue]. */
public object AnyCodableValueSerializer : KSerializer<AnyCodableValue> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): AnyCodableValue {
        val json = decoder as? JsonDecoder ?: throw SerializationException("AnyCodableValue supports JSON only")
        return AnyCodableValue.fromJson(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: AnyCodableValue) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("AnyCodableValue supports JSON only")
        val element = try {
            Values.toJson(value.value)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message, e)
        }
        json.encodeJsonElement(element)
    }
}

private fun wrap(value: Any?): AnyCodableValue? = when (value) {
    null -> null
    is AnyCodableValue -> value
    else -> AnyCodableValue(value)
}

/** Preview hint for standalone screen rendering — never used in production navigation flows. */
@Serializable
public class VariablePreview(
    /** Datasource path to pick an item from (e.g. "datasources/listings"). */
    public val source: String? = null,
    /** Index into the datasource data array (default: 0). */
    public val index: Int? = null,
    /** Literal sample value to inject when no datasource is available. */
    public val value: AnyCodableValue? = null,
)

/**
 * Definition of a variable from the JSON DSL (Swift `VariableDefinition`).
 *
 * The programmatic constructor takes the initial value as a plain canonical value (like Swift's
 * `initialValue: Any?`); JSON decoding goes through [VariableDefinition.serializer].
 */
@Serializable(with = VariableDefinitionSerializer::class)
public class VariableDefinition(
    public val type: VariableType,
    initialValue: Any? = null,
    public val computed: String? = null,
    /** External datasource reference (e.g. "datasources/listings"). */
    public val source: String? = null,
    /** When true, the engine subscribes to the datasource stream for live updates. */
    public val streaming: Boolean? = null,
    /** Datasource path whose item schema this variable conforms to. */
    public val schema: String? = null,
    /** Sample value hint used only during standalone screen rendering. */
    public val preview: VariablePreview? = null,
    /** App scope only: keep the value across launches (`persist: true`). */
    public val persist: Boolean? = null,
) {
    public val initialValue: AnyCodableValue? = wrap(initialValue)

    /** The raw initial value, or `null` when absent. */
    public val rawInitialValue: Any? get() = initialValue?.value

    /** True if this variable references an external datasource. */
    public val hasExternalSource: Boolean get() = source != null

    /** True if live streaming is enabled. */
    public val isStreaming: Boolean get() = streaming == true

    /** Copy with a resolved initial value and no `source` (used after datasource loading). */
    public fun withResolvedValue(value: Any): VariableDefinition =
        VariableDefinition(type, value, computed, null, streaming, schema, preview, persist)
}

@Serializable
private class VariableDefinitionSurrogate(
    val type: VariableType,
    val computed: String? = null,
    val initialValue: AnyCodableValue? = null,
    val source: String? = null,
    val streaming: Boolean? = null,
    val schema: String? = null,
    val preview: VariablePreview? = null,
    val persist: Boolean? = null,
)

/** JSON serializer for [VariableDefinition]; absent / `null` optional fields are omitted on encode. */
public object VariableDefinitionSerializer : KSerializer<VariableDefinition> {
    override val descriptor: SerialDescriptor = VariableDefinitionSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): VariableDefinition {
        val s = decoder.decodeSerializableValue(VariableDefinitionSurrogate.serializer())
        return VariableDefinition(s.type, s.initialValue, s.computed, s.source, s.streaming, s.schema, s.preview, s.persist)
    }

    override fun serialize(encoder: Encoder, value: VariableDefinition) {
        encoder.encodeSerializableValue(
            VariableDefinitionSurrogate.serializer(),
            VariableDefinitionSurrogate(
                value.type, value.computed, value.initialValue, value.source, value.streaming, value.schema, value.preview, value.persist,
            ),
        )
    }
}

/** Definition for an input parameter that a screen can receive (Swift `InputParameterDefinition`). */
@Serializable
public class InputParameterDefinition(
    public val type: VariableType,
    /** Whether this parameter is required (default `true`). */
    public val required: Boolean = true,
    /** Default value if not provided (only valid when [required] is false). */
    public val defaultValue: AnyCodableValue? = null,
)
