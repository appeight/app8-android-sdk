package dev.app8.engine.core.vars

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MockDataSource : DatasourceLoader {
    val datasources = HashMap<String, ByteArray>()
    var shouldThrowError = false

    override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray {
        if (shouldThrowError) throw DatasourceException.NotFound(screenId, datasourceId)
        return datasources[datasourceId] ?: throw DatasourceException.NotFound(screenId, datasourceId)
    }
}

/** Port of iOS `DatasourceResolverTests`. */
class DatasourceResolverTest {
    private val mock = MockDataSource()

    private fun resolve(variables: Map<String, VariableDefinition>) = runBlocking {
        DatasourceResolver.resolveDatasources("test-screen", variables, mock)
    }

    @Test
    fun resolveDatasourcesWithNoSources() {
        val resolved = resolve(
            mapOf(
                "name" to VariableDefinition(VariableType.STRING, "Alice"),
                "age" to VariableDefinition(VariableType.NUMBER, 30),
            ),
        )
        assertEquals(2, resolved.size)
        assertEquals("Alice", resolved["name"]?.rawInitialValue)
        assertEquals(30L, resolved["age"]?.rawInitialValue)
        assertNull(resolved["name"]?.source)
        assertNull(resolved["age"]?.source)
    }

    @Test
    fun resolveDatasourcesLoadsExternalData() {
        mock.datasources["datasources/items"] =
            """{"data": [{"id": "1", "name": "Item 1"}, {"id": "2", "name": "Item 2"}]}""".encodeToByteArray()
        val resolved = resolve(mapOf("items" to VariableDefinition(VariableType.ARRAY, source = "datasources/items")))
        assertEquals(1, resolved.size)
        assertNull(resolved["items"]?.source, "Source should be cleared after resolution")
        val items = resolved["items"]?.rawInitialValue as List<*>
        assertEquals(2, items.size)
        assertEquals("1", (items[0] as Map<*, *>)["id"])
        assertEquals("Item 1", (items[0] as Map<*, *>)["name"])
    }

    @Test
    fun resolveDatasourcesWithRawArrayFormat() {
        mock.datasources["datasources/posts"] =
            """[{"id": "1", "title": "First"}, {"id": "2", "title": "Second"}]""".encodeToByteArray()
        val resolved = resolve(mapOf("posts" to VariableDefinition(VariableType.ARRAY, source = "datasources/posts")))
        assertEquals(2, (resolved["posts"]?.rawInitialValue as List<*>).size)
    }

    @Test
    fun resolveDatasourcesValidatesType() {
        mock.datasources["datasources/items"] = """{"data": {"key": "value"}}""".encodeToByteArray()
        val e = assertFailsWith<DatasourceException.TypeMismatch> {
            resolve(mapOf("items" to VariableDefinition(VariableType.ARRAY, source = "datasources/items")))
        }
        assertEquals("datasources/items", e.datasourceId)
        assertEquals(VariableType.ARRAY, e.expected)
    }

    @Test
    fun resolveDatasourcesHandlesNotFound() {
        val e = assertFailsWith<DatasourceException.NotFound> {
            resolve(mapOf("missing" to VariableDefinition(VariableType.ARRAY, source = "datasources/nonexistent")))
        }
        assertEquals("test-screen", e.screenId)
        assertEquals("datasources/nonexistent", e.datasourceId)
    }

    @Test
    fun resolveDatasourcesParallelLoading() {
        mock.datasources["datasources/items"] = """{"data": [{"id": "1"}]}""".encodeToByteArray()
        mock.datasources["datasources/users"] = """{"data": [{"id": "u1"}]}""".encodeToByteArray()
        mock.datasources["datasources/posts"] = """{"data": [{"id": "p1"}]}""".encodeToByteArray()
        val resolved = resolve(
            mapOf(
                "items" to VariableDefinition(VariableType.ARRAY, source = "datasources/items"),
                "users" to VariableDefinition(VariableType.ARRAY, source = "datasources/users"),
                "posts" to VariableDefinition(VariableType.ARRAY, source = "datasources/posts"),
            ),
        )
        assertEquals(3, resolved.size)
        assertNotNull(resolved["items"]?.rawInitialValue)
        assertNotNull(resolved["users"]?.rawInitialValue)
        assertNotNull(resolved["posts"]?.rawInitialValue)
    }

    @Test
    fun hasExternalDatasources() {
        assertFalse(DatasourceResolver.hasExternalDatasources(mapOf("name" to VariableDefinition(VariableType.STRING, "Test"))))
        assertTrue(
            DatasourceResolver.hasExternalDatasources(mapOf("items" to VariableDefinition(VariableType.ARRAY, source = "datasources/items"))),
        )
        assertFalse(DatasourceResolver.hasExternalDatasources(null))
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun loads_run_concurrently() = runBlocking {
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        var maxInFlight = 0
        val loader = DatasourceLoader { _, id ->
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            delay(20)
            inFlight.decrementAndGet()
            """{"data": ["$id"]}""".encodeToByteArray()
        }
        val vars = (1..3).associate { "v$it" to VariableDefinition(VariableType.ARRAY, source = "ds/$it") }
        val resolved = DatasourceResolver.resolveDatasources("s", vars, loader)
        assertEquals(listOf("ds/2"), resolved["v2"]?.rawInitialValue)
        assertEquals(3, maxInFlight)
    }

    @Test
    fun schema_in_wrapped_datasource_is_enforced() {
        mock.datasources["ds"] = """{"schema": {"type": "array", "items": {"type": "string"}}, "data": ["a", 1]}""".encodeToByteArray()
        val e = assertFailsWith<SchemaValidationException.TypeMismatch> {
            resolve(mapOf("v" to VariableDefinition(VariableType.ARRAY, source = "ds")))
        }
        assertEquals("$[1]", e.path)
    }

    @Test
    fun resolved_definition_keeps_other_fields() {
        mock.datasources["ds"] = "[1]".encodeToByteArray()
        val def = VariableDefinition(VariableType.ARRAY, source = "ds", streaming = true, schema = "datasources/x")
        val resolved = resolve(mapOf("v" to def)).getValue("v")
        assertTrue(resolved.isStreaming)
        assertEquals("datasources/x", resolved.schema)
        assertFalse(resolved.hasExternalSource)
    }

    // endregion
}

/** Port of iOS `DatasourceDefinitionTests`. */
class DatasourceDefinitionTest {
    private fun decode(json: String) = DatasourceDefinition.decode(json.encodeToByteArray())

    @Test
    fun decodeWrappedFormatWithArray() {
        val d = decode(
            """{"schema": {"type": "array", "items": {"type": "object"}},
               "data": [{"id": "1", "name": "First"}, {"id": "2", "name": "Second"}]}""",
        )
        assertNotNull(d.schema)
        val data = d.rawData as List<*>
        assertEquals(2, data.size)
        assertEquals("1", (data[0] as Map<*, *>)["id"])
    }

    @Test
    fun decodeWrappedFormatWithObject() {
        val d = decode("""{"schema": {"type": "object"}, "data": {"key": "value", "count": 42}}""")
        assertNotNull(d.schema)
        val data = d.rawData as Map<*, *>
        assertEquals("value", data["key"])
        assertEquals(42L, data["count"])
    }

    @Test
    fun decodeWrappedFormatWithoutSchema() {
        val d = decode("""{"data": [1, 2, 3]}""")
        assertNull(d.schema)
        assertEquals(listOf(1L, 2L, 3L), d.rawData)
    }

    @Test
    fun decodeRawArrayFormat() {
        val d = decode("""[{"id": "1", "title": "First"}, {"id": "2", "title": "Second"}]""")
        assertNull(d.schema, "Raw format should have no schema")
        assertEquals(2, (d.rawData as List<*>).size)
    }

    @Test
    fun decodeRawObjectFormat() {
        val d = decode("""{"name": "Test", "value": 123}""")
        assertNull(d.schema, "Raw format should have no schema")
        val data = d.rawData as Map<*, *>
        assertEquals("Test", data["name"])
        assertEquals(123L, data["value"])
    }

    @Test
    fun decodeRawPrimitiveArray() {
        val d = decode("[1, 2, 3, 4, 5]")
        assertNull(d.schema)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), d.rawData)
    }

    @Test
    fun decodeRawStringArray() {
        val d = decode("""["apple", "banana", "cherry"]""")
        assertNull(d.schema)
        assertEquals(listOf("apple", "banana", "cherry"), d.rawData)
    }

    @Test
    fun schemaIsPreserved() {
        val d = decode(
            """{"schema": {"type": "array", "items": {"type": "object", "properties": {"id": {"type": "string"},
               "name": {"type": "string"}}, "required": ["id", "name"]}}, "data": []}""",
        )
        val schema = assertIs<JsonObject>(d.schema)
        assertNotNull(schema["type"])
        assertNotNull(schema["items"])
    }

    @Test
    fun encode() {
        val d = decode("""{"data": [{"id": "1"}]}""")
        val decoded = DatasourceDefinition.fromJson(d.toJson())
        assertEquals((d.rawData as List<*>).size, (decoded.rawData as List<*>).size)
    }

    @Test
    fun emptyArray() {
        assertEquals(0, (decode("[]").rawData as List<*>).size)
    }

    @Test
    fun emptyObject() {
        assertEquals(0, (decode("{}").rawData as Map<*, *>).size)
    }

    @Test
    fun nestedData() {
        val d = decode("""{"data": {"users": [{"id": "1", "profile": {"name": "Alice", "age": 30}}]}}""")
        val users = (d.rawData as Map<*, *>)["users"] as List<*>
        assertEquals(1, users.size)
        assertEquals("Alice", ((users[0] as Map<*, *>)["profile"] as Map<*, *>)["name"])
    }

    // Additional: `"data": null` is the wrapped format with explicit null data, as on iOS.
    @Test
    fun wrappedNullDataIsExplicitNull() {
        assertSame(Values.Null, decode("""{"data": null}""").rawData)
        assertEquals("""{"data":null}""", Json.encodeToString(JsonObject.serializer(), decode("""{"data": null}""").toJson() as JsonObject))
    }
}
