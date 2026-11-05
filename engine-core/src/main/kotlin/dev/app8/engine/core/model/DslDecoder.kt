package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.StyleAny
import dev.app8.engine.core.model.style.decodeStyleAny
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

/**
 * Entry point for decoding App8 DSL documents.
 *
 * Decoding mirrors the iOS engine's lenient `Decodable` models: a bad element inside a lenient
 * container (children, constraints, material layers, …) is dropped and recorded in
 * [DecodeOptions.errorCollector]; only structurally required values throw [DslDecodingException].
 */
public class DslDecoder(public val options: DecodeOptions = DecodeOptions()) {

    public constructor(platform: Platform) : this(DecodeOptions(platform = platform))

    /** Parses JSON text, enforcing [ModelLimits.MAX_DSL_PAYLOAD_BYTES] and [ModelLimits.MAX_JSON_DEPTH]. */
    public fun parse(json: String): JsonElement {
        val bytes = utf8Length(json)
        if (bytes > ModelLimits.MAX_DSL_PAYLOAD_BYTES) {
            throw DslDecodingException("", "payload of $bytes bytes exceeds ${ModelLimits.MAX_DSL_PAYLOAD_BYTES}")
        }
        checkDepth(json)
        return try {
            Json.parseToJsonElement(json)
        } catch (e: SerializationException) {
            throw DslDecodingException("", "invalid JSON: ${e.message}", e)
        }
    }

    public fun parse(bytes: ByteArray): JsonElement {
        if (bytes.size > ModelLimits.MAX_DSL_PAYLOAD_BYTES) {
            throw DslDecodingException("", "payload of ${bytes.size} bytes exceeds ${ModelLimits.MAX_DSL_PAYLOAD_BYTES}")
        }
        return parse(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    /** `app.json`. */
    public fun decodeApp(json: String): App = decodeApp(parse(json))
    public fun decodeApp(element: JsonElement): App = decodeApp(root(element))

    /** One component tree: a screen (`screen` / `tabBarScreen`) or any component. */
    public fun decodeComponent(json: String): Component = decodeComponent(parse(json))
    public fun decodeComponent(element: JsonElement): Component = decodeComponent(root(element))

    /** `styles.json` (an array). Bad entries are dropped like iOS `[FailableDecodable<Style.Any>]`. */
    public fun decodeStyles(json: String): List<StyleAny> = decodeStyles(parse(json))
    public fun decodeStyles(element: JsonElement): List<StyleAny> = root(element).failableList("Style.Any", ::decodeStyleAny)

    /** One `components/<id>.json` template. */
    public fun decodeTemplate(json: String): ComponentTemplate = decodeTemplate(parse(json))
    public fun decodeTemplate(element: JsonElement): ComponentTemplate = decodeComponentTemplate(root(element))

    /**
     * Decodes a screen after expanding `templateId` references with [templates]
     * (iOS loads screens through `TemplatePreprocessor` first).
     */
    public fun decodeScreen(json: String, templates: TemplateResolver? = null): Component {
        val element = parse(json)
        return decodeComponent(templates?.let { TemplatePreprocessor(it).process(element) } ?: element)
    }

    internal fun root(element: JsonElement): Cursor = Cursor(element, "", options)

    internal fun <T> decode(json: String, decode: (Cursor) -> T): T = decode(root(parse(json)))

    private companion object {
        fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                n += when {
                    ch.code < 0x80 -> 1
                    ch.code < 0x800 -> 2
                    Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        /** Rejects nesting deeper than [ModelLimits.MAX_JSON_DEPTH] before building the tree. */
        fun checkDepth(s: String) {
            var depth = 0
            var inString = false
            var escaped = false
            for (ch in s) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > ModelLimits.MAX_JSON_DEPTH) {
                        throw DslDecodingException("", "JSON nested too deeply (> ${ModelLimits.MAX_JSON_DEPTH})")
                    }
                    ']', '}' -> depth--
                }
            }
        }
    }
}

/** kotlinx.serialization bridge (decode only) so the DSL models can be used with `Json.decodeFromString`. */
public abstract class DslSerializer<T> internal constructor(
    private val name: String,
    private val decode: (DslDecoder, JsonElement) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = SerialDescriptor(name, JsonElement.serializer().descriptor)

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("$name can only be decoded from JSON")
        return decode(DslDecoder(), json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: T): Nothing =
        throw SerializationException("$name is decode-only")
}

public object ComponentSerializer : DslSerializer<Component>("dev.app8.Component", { d, e -> d.decodeComponent(e) })
public object AppSerializer : DslSerializer<App>("dev.app8.App", { d, e -> d.decodeApp(e) })
public object StylesSerializer : DslSerializer<List<StyleAny>>("dev.app8.Styles", { d, e -> d.decodeStyles(e) })
