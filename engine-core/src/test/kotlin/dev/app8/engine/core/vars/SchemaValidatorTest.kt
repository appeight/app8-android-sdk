package dev.app8.engine.core.vars

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Port of iOS `SchemaValidatorTests`. */
class SchemaValidatorTest {
    private fun schema(json: String): JsonElement = Json.parseToJsonElement(json)
    private fun ok(data: Any?, schema: JsonElement) = SchemaValidator.validate(data, schema)
    private fun bad(data: Any?, schema: JsonElement): SchemaValidationException =
        assertFailsWith<SchemaValidationException> { SchemaValidator.validate(data, schema) }

    @Test
    fun validateStringType() {
        val s = schema("""{"type": "string"}""")
        ok("hello", s)
        bad(123, s)
    }

    @Test
    fun validateNumberType() {
        val s = schema("""{"type": "number"}""")
        ok(42, s)
        ok(3.14, s)
        bad("123", s)
    }

    @Test
    fun validateBooleanType() {
        val s = schema("""{"type": "boolean"}""")
        ok(true, s)
        ok(false, s)
        bad("true", s)
        bad(1, s)
    }

    @Test
    fun validateArrayType() {
        val s = schema("""{"type": "array"}""")
        ok(listOf(1, 2, 3), s)
        ok(emptyList<Any>(), s)
        bad("not an array", s)
    }

    @Test
    fun validateObjectType() {
        val s = schema("""{"type": "object"}""")
        ok(mapOf("key" to "value"), s)
        ok(emptyMap<String, Any>(), s)
        bad(listOf(1, 2, 3), s)
    }

    @Test
    fun validateStringMinLength() {
        val s = schema("""{"type": "string", "minLength": 3}""")
        ok("hello", s)
        ok("abc", s)
        bad("ab", s)
    }

    @Test
    fun validateStringMaxLength() {
        val s = schema("""{"type": "string", "maxLength": 5}""")
        ok("hello", s)
        ok("hi", s)
        bad("hello world", s)
    }

    @Test
    fun validateNumberMinimum() {
        val s = schema("""{"type": "number", "minimum": 0}""")
        ok(0, s)
        ok(100, s)
        bad(-1, s)
    }

    @Test
    fun validateNumberMaximum() {
        val s = schema("""{"type": "number", "maximum": 100}""")
        ok(100, s)
        ok(50, s)
        bad(101, s)
    }

    @Test
    fun validateArrayMinItems() {
        val s = schema("""{"type": "array", "minItems": 2}""")
        ok(listOf(1, 2), s)
        ok(listOf(1, 2, 3), s)
        bad(listOf(1), s)
        bad(emptyList<Any>(), s)
    }

    @Test
    fun validateArrayMaxItems() {
        val s = schema("""{"type": "array", "maxItems": 3}""")
        ok(listOf(1, 2, 3), s)
        ok(listOf(1), s)
        bad(listOf(1, 2, 3, 4), s)
    }

    @Test
    fun validateArrayItems() {
        val s = schema("""{"type": "array", "items": {"type": "string"}}""")
        ok(listOf("a", "b", "c"), s)
        bad(listOf("a", 1, "c"), s)
    }

    @Test
    fun validateObjectProperties() {
        val s = schema("""{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "number"}}}""")
        ok(mapOf("name" to "Alice", "age" to 30), s)
        bad(mapOf("name" to "Alice", "age" to "thirty"), s)
    }

    @Test
    fun validateObjectRequired() {
        val s = schema(
            """{"type": "object", "properties": {"id": {"type": "string"}, "name": {"type": "string"}}, "required": ["id"]}""",
        )
        ok(mapOf("id" to "123", "name" to "Alice"), s)
        ok(mapOf("id" to "123"), s)
        val e = bad(mapOf("name" to "Alice"), s)
        assertEquals("id", (e as SchemaValidationException.MissingRequired).property)
    }

    @Test
    fun validateEnum() {
        val s = schema("""{"type": "string", "enum": ["red", "green", "blue"]}""")
        ok("red", s)
        ok("green", s)
        ok("blue", s)
        bad("yellow", s)
    }

    @Test
    fun validateEnumNumbers() {
        val s = schema("""{"type": "number", "enum": [1, 2, 3]}""")
        ok(1, s)
        ok(2, s)
        bad(4, s)
    }

    @Test
    fun validateNestedObjects() {
        val s = schema(
            """{"type": "object", "properties": {"user": {"type": "object",
               "properties": {"name": {"type": "string"}, "email": {"type": "string"}}, "required": ["name"]}}}""",
        )
        ok(mapOf("user" to mapOf("name" to "Alice", "email" to "alice@example.com")), s)
        bad(mapOf("user" to mapOf("email" to "alice@example.com")), s)
    }

    @Test
    fun validateNestedArrayOfObjects() {
        val s = schema(
            """{"type": "array", "items": {"type": "object",
               "properties": {"id": {"type": "string"}, "title": {"type": "string"}}, "required": ["id"]}}""",
        )
        ok(listOf(mapOf("id" to "1", "title" to "First"), mapOf("id" to "2", "title" to "Second")), s)
        val e = bad(listOf(mapOf("id" to "1", "title" to "First"), mapOf("title" to "Second")), s) as SchemaValidationException.MissingRequired
        assertEquals("$[1]", e.path)
        assertEquals("id", e.property)
    }

    @Test
    fun errorPathsAreCorrect() {
        val s = schema(
            """{"type": "object", "properties": {"items": {"type": "array",
               "items": {"type": "object", "properties": {"name": {"type": "string"}}}}}}""",
        )
        val e = bad(mapOf("items" to listOf(mapOf("name" to "Valid"), mapOf("name" to 123))), s)
        assertEquals("$.items[1].name", (e as SchemaValidationException.TypeMismatch).path)
    }

    @Test
    fun validateWithNoTypeSpecified() {
        val s = schema("{}")
        ok("string", s)
        ok(123, s)
        ok(listOf(1, 2, 3), s)
    }

    @Test
    fun validateEmptyData() {
        ok(emptyList<Any>(), schema("""{"type": "array"}"""))
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun error_messages_match_ios() {
        assertEquals("Type mismatch at '$': expected string, got integer", bad(1, schema("""{"type": "string"}""")).message)
        assertEquals(
            "Invalid value at '$': Value -1.0 is less than minimum 0.0",
            bad(-1, schema("""{"minimum": 0}""")).message,
        )
        assertEquals("Invalid schema: Schema must be an object at $", bad(1, schema("[]")).message)
        assertEquals("Missing required property 'id' at '$'", bad(emptyMap<String, Any>(), schema("""{"required": ["id"]}""")).message)
    }

    @Test
    fun json_null_is_type_null_and_matches_enum_null() {
        ok(Values.Null, schema("""{"type": "null", "enum": [null]}"""))
        bad("x", schema("""{"type": "null"}"""))
    }

    @Test
    fun string_length_counts_characters_not_utf16_units() {
        ok("👍🏽", schema("""{"maxLength": 1}"""))
    }

    @Test
    fun booleans_are_checked_against_numeric_bounds_like_ios() {
        bad(true, schema("""{"maximum": 0}"""))
    }

    // endregion
}
