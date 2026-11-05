package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.SwiftCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical representation of dynamic (DSL) values on Android — the Kotlin counterpart of the
 * `Any` values the iOS engine passes around.
 *
 * | Swift (iOS)            | Kotlin (Android)        | Notes                                              |
 * |------------------------|-------------------------|----------------------------------------------------|
 * | `nil`                  | `null`                  | absent / undefined (missing member, no match, …)   |
 * | `NSNull()`             | [Values.Null]           | JSON `null` and the expression literal `null`      |
 * | `Bool`                 | [Boolean]               |                                                    |
 * | `Int`                  | [Long]                  | Swift `Int` is 64-bit                              |
 * | `Double`               | [Double]                |                                                    |
 * | `String`               | [String]                |                                                    |
 * | `[Any]`                | [List]`<Any?>`          |                                                    |
 * | `[String: Any]`        | [Map]`<String, Any?>`   | insertion-ordered (Swift dictionaries are unordered)|
 *
 * **Integers and doubles are distinct**, exactly like on iOS: JSON numbers with an integral
 * value decode to [Long] (`1.0` → `1L`), others to [Double]; arithmetic yields [Long] whenever
 * the result is whole, except `/` which always yields [Double]. This is observable —
 * `toString(4 / 2)` is `"2.0"`, `1 === 1.0` is `false` — so the distinction is kept.
 *
 * **`null` vs [Values.Null]** mirrors Swift's `nil` vs `NSNull`: `x === null` is `true` only for
 * an explicit null, a nil local value falls through to the parent scope while an explicit null
 * shadows it, etc. Use [isNullish] when either should count as "no value".
 *
 * Inputs are accepted leniently: Kotlin [Int]/[Short]/[Byte] behave as integers and [Float] as a
 * double everywhere the engine inspects a value, so host code may pass `5` instead of `5L`.
 * [normalize] converts such values to the canonical form.
 */
public object Values {

    /** Explicit JSON `null` — the Kotlin stand-in for Swift's `NSNull()`. */
    public object Null {
        override fun toString(): String = "null"
    }

    /** True for both `null` (absent) and [Null] (explicit JSON null). */
    public fun isNullish(value: Any?): Boolean = value == null || value === Null

    /**
     * Converts a JSON tree to a canonical value (same rules as Swift's `AnyCodableValue` decoding:
     * booleans first, then integral numbers as [Long], then [Double], strings, arrays, objects,
     * and JSON `null` as [Null]). Never returns Kotlin `null`.
     */
    public fun fromJson(element: JsonElement): Any = when (element) {
        is JsonNull -> Null
        is JsonPrimitive -> primitiveFromJson(element)
        is JsonArray -> element.map { fromJson(it) }
        is JsonObject -> LinkedHashMap<String, Any?>(element.size).also { map ->
            for ((k, v) in element) map[k] = fromJson(v)
        }
    }

    /**
     * Converts a canonical value to JSON. `null` and [Null] both encode as JSON `null`.
     * @throws IllegalArgumentException for values that are not JSON-representable
     *   (Swift throws `EncodingError.invalidValue`).
     */
    public fun toJson(value: Any?): JsonElement = when {
        value == null || value === Null -> JsonNull
        value is Boolean -> JsonPrimitive(value)
        value is String -> JsonPrimitive(value)
        isInt(value) -> JsonPrimitive((value as Number).toLong())
        value is Double -> JsonPrimitive(value)
        value is Float -> JsonPrimitive(value.toDouble())
        value is List<*> -> JsonArray(value.map { toJson(it) })
        value is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        else -> throw IllegalArgumentException("Cannot encode value of type ${value::class.simpleName}")
    }

    /**
     * Deep-converts lenient host values to the canonical form: [Int]/[Short]/[Byte] → [Long],
     * [Float] → [Double], any [Collection]/[Array] → [List], any [Map] → `Map<String, Any?>`.
     */
    public fun normalize(value: Any?): Any? = if (isCanonical(value)) value else convert(value)

    private fun isCanonical(value: Any?): Boolean = when (value) {
        null, Null, is Boolean, is String, is Long, is Double -> true
        is List<*> -> value.all { isCanonical(it) }
        is Map<*, *> -> value.all { (k, v) -> k is String && isCanonical(v) }
        else -> value !is Number && value !is Collection<*> && value !is Array<*>
    }

    private fun convert(value: Any?): Any? = when (value) {
        null, Null, is Boolean, is String, is Long, is Double -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Float -> value.toDouble()
        is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { m ->
            for ((k, v) in value) m[k.toString()] = normalize(v)
        }
        is Collection<*> -> value.map { normalize(it) }
        is Array<*> -> value.map { normalize(it) }
        else -> value
    }

    // region internal classification (mirrors Swift dynamic casts)

    /** Swift `value as? Int`. */
    internal fun isInt(value: Any?): Boolean = value is Long || value is Int || value is Short || value is Byte

    internal fun asInt(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> null
    }

    /** Swift `value as? Double`. */
    internal fun asDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        else -> null
    }

    /**
     * Swift `(value as? NSNumber)?.doubleValue`. On Darwin a `Bool` bridges to `NSNumber`,
     * so booleans count as 1/0 here.
     */
    internal fun asNSNumber(value: Any?): Double? = when (value) {
        is Boolean -> if (value) 1.0 else 0.0
        is Number -> value.toDouble()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    internal fun asList(value: Any?): List<Any?>? = value as? List<Any?>

    @Suppress("UNCHECKED_CAST")
    internal fun asMap(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    /** Whole doubles become [Long] (Swift `Int(result)`), others stay [Double]. */
    internal fun wholeOrDouble(d: Double): Any =
        if (d % 1.0 == 0.0 && d >= -9.223372036854776E18 && d < 9.223372036854776E18) d.toLong() else d

    /**
     * Shared `stringify` of `ExpressionEvaluator` / `PropertyResolver`: nil and NSNull → "",
     * whole doubles drop ".0", collections use Swift's `description` format.
     */
    internal fun stringify(value: Any?): String {
        if (value == null || value === Null) return ""
        if (value is String) return value
        if (isInt(value)) return (value as Number).toLong().toString()
        val d = asDouble(value)
        if (d != null) {
            return if (d % 1.0 == 0.0 && d >= -9.223372036854776E18 && d < 9.223372036854776E18) {
                d.toLong().toString()
            } else {
                SwiftCompat.doubleDescription(d)
            }
        }
        if (value is Boolean) return if (value) "true" else "false"
        return describe(value)
    }

    /** Swift `String(describing:)` for a non-optional value. */
    internal fun describe(value: Any?): String = describe(value, nested = false)

    /** Swift type name used in `typeMismatch` messages (`String(describing: type(of: v))`). */
    internal fun swiftTypeName(value: Any?): String = when {
        value == null -> "Optional<Any>"
        value === Null -> "NSNull"
        value is Boolean -> "Bool"
        value is String -> "String"
        isInt(value) -> "Int"
        value is Double || value is Float -> "Double"
        value is List<*> -> "Array<Any>"
        value is Map<*, *> -> "Dictionary<String, Any>"
        else -> value::class.simpleName ?: "Any"
    }

    private fun describe(value: Any?, nested: Boolean): String = when {
        value == null -> "nil"
        value === Null -> "<null>"
        value is String -> if (nested) debugQuote(value) else value
        value is Boolean -> value.toString()
        isInt(value) -> (value as Number).toLong().toString()
        value is Double -> SwiftCompat.doubleDescription(value)
        value is Float -> SwiftCompat.doubleDescription(value.toDouble())
        value is List<*> -> value.joinToString(", ", "[", "]") { describe(it, nested = true) }
        value is Map<*, *> -> if (value.isEmpty()) "[:]" else value.entries.joinToString(", ", "[", "]") {
            describe(it.key, nested = true) + ": " + describe(it.value, nested = true)
        }
        else -> value.toString()
    }

    private fun debugQuote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                '\u0000' -> sb.append("\\0")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun primitiveFromJson(p: JsonPrimitive): Any {
        if (p.isString) return p.content
        val content = p.content
        if (content == "true") return true
        if (content == "false") return false
        content.toLongOrNull()?.let { return it }
        val d = content.toDoubleOrNull() ?: return content
        // Swift's JSONDecoder decodes `Int` first: any integral number that fits (1.0, 1e2, -0.0) is an Int.
        return wholeOrDouble(d)
    }

    // endregion
}
