package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.EngineLimits
import dev.app8.engine.core.expr.SwiftCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * JSON Schema validator — the Draft-07 subset supported on iOS (Swift `SchemaValidator`):
 * `type` (string, number, integer, boolean, array, object, null), `properties`, `items`,
 * `required`, `enum`, `minLength`/`maxLength`, `minimum`/`maximum`, `minItems`/`maxItems`.
 */
public object SchemaValidator {

    /** @throws SchemaValidationException on the first violation. */
    public fun validate(data: Any?, schema: JsonElement) {
        validateValue(data, schema, "$", 0)
    }

    private fun validateValue(value: Any?, schema: JsonElement, path: String, depth: Int) {
        val schemaObj = schema as? JsonObject ?: throw SchemaValidationException.InvalidSchema("Schema must be an object at $path")
        if (depth >= EngineLimits.maxJSONDepth) {
            throw SchemaValidationException.InvalidValue(path, "Data nested too deeply to validate")
        }

        schemaObj["type"]?.let { validateType(value, it, path) }
        schemaObj["enum"]?.let { validateEnum(value, it, path) }

        if (value is String) {
            val count = SwiftCompat.characterCount(value)
            number(schemaObj["minLength"])?.let { min ->
                if (count < min.toInt()) {
                    throw SchemaValidationException.InvalidValue(path, "String length $count is less than minimum ${min.toInt()}")
                }
            }
            number(schemaObj["maxLength"])?.let { max ->
                if (count > max.toInt()) {
                    throw SchemaValidationException.InvalidValue(path, "String length $count exceeds maximum ${max.toInt()}")
                }
            }
        }

        asNumber(value)?.let { num ->
            schemaObj["minimum"]?.let {
                val min = number(it) ?: 0.0
                if (num < min) {
                    throw SchemaValidationException.InvalidValue(path, "Value ${d(num)} is less than minimum ${d(min)}")
                }
            }
            schemaObj["maximum"]?.let {
                val max = number(it) ?: 0.0
                if (num > max) {
                    throw SchemaValidationException.InvalidValue(path, "Value ${d(num)} exceeds maximum ${d(max)}")
                }
            }
        }

        Values.asMap(value)?.let { obj ->
            (schemaObj["required"] as? JsonArray)?.forEach { req ->
                val propName = (req as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                if (obj[propName] == null) throw SchemaValidationException.MissingRequired(path, propName)
            }
            (schemaObj["properties"] as? JsonObject)?.forEach { (propName, propSchema) ->
                val propValue = obj[propName]
                if (propValue != null) validateValue(propValue, propSchema, "$path.$propName", depth + 1)
            }
        }

        Values.asList(value)?.let { arr ->
            number(schemaObj["minItems"])?.let { min ->
                if (arr.size < min.toInt()) {
                    throw SchemaValidationException.InvalidValue(path, "Array has ${arr.size} items, minimum is ${min.toInt()}")
                }
            }
            number(schemaObj["maxItems"])?.let { max ->
                if (arr.size > max.toInt()) {
                    throw SchemaValidationException.InvalidValue(path, "Array has ${arr.size} items, maximum is ${max.toInt()}")
                }
            }
            schemaObj["items"]?.let { items ->
                arr.forEachIndexed { index, item -> validateValue(item, items, "$path[$index]", depth + 1) }
            }
        }
    }

    private fun d(x: Double): String = SwiftCompat.doubleDescription(x)

    private fun validateType(value: Any?, type: JsonElement, path: String) {
        val expected = (type as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val actual = jsonType(value)
        if (expected == "number" && actual == "integer") return
        if (expected != actual) throw SchemaValidationException.TypeMismatch(path, expected, actual)
    }

    private fun validateEnum(value: Any?, enumValue: JsonElement, path: String) {
        val allowed = enumValue as? JsonArray ?: return
        if (allowed.any { valuesEqual(value, it) }) return
        throw SchemaValidationException.InvalidValue(path, "Value not in allowed enum values")
    }

    private fun jsonType(value: Any?): String = when {
        value is String -> "string"
        value is Boolean -> "boolean"
        Values.isInt(value) -> "integer"
        value is Double || value is Float -> "number"
        value is List<*> -> "array"
        value is Map<*, *> -> "object"
        value === Values.Null -> "null"
        value is Number -> "number"
        else -> "unknown"
    }

    /** Swift `asNumber`: booleans count (they bridge to `NSNumber` on iOS). */
    private fun asNumber(value: Any?): Double? = Values.asNSNumber(value)

    /** JSONValue `.number` payload. */
    private fun number(json: JsonElement?): Double? {
        val p = json as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString || p.booleanOrNull != null) return null
        return p.content.toDoubleOrNull()
    }

    private fun valuesEqual(value: Any?, json: JsonElement): Boolean {
        if (json is JsonNull) return value === Values.Null
        val p = json as? JsonPrimitive ?: return false
        if (p.isString) return value is String && value == p.content
        p.booleanOrNull?.let { return value is Boolean && value == it }
        val n = p.content.toDoubleOrNull() ?: return false
        Values.asInt(value)?.let { return it.toDouble() == n }
        Values.asDouble(value)?.let { return it == n }
        return false
    }
}
