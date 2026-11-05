package dev.app8.engine.core.vars

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * A parsed external datasource file (Swift `DatasourceDefinition`). Two formats:
 * wrapped `{ "schema": {...}, "data": ... }` or the raw data (`[...]` / `{...}`).
 */
public class DatasourceDefinition(
    /** Optional JSON Schema (validated by [DatasourceResolver] when present). */
    public val schema: JsonElement? = null,
    data: Any,
) {
    public val data: AnyCodableValue = data as? AnyCodableValue ?: AnyCodableValue(data)

    /** The raw data value. */
    public val rawData: Any get() = data.value

    /** Wrapped-format JSON (`schema` omitted when absent). */
    public fun toJson(): JsonElement = buildJsonObject {
        schema?.let { put("schema", it) }
        put("data", Values.toJson(rawData))
    }

    public companion object {
        private val json = Json

        /** Decodes a datasource; a root object with a `data` key is the wrapped format. */
        public fun fromJson(element: JsonElement): DatasourceDefinition {
            if (element is JsonObject && element.containsKey("data")) {
                val schema = element["schema"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                return DatasourceDefinition(schema, AnyCodableValue.fromJson(element.getValue("data")))
            }
            return DatasourceDefinition(null, AnyCodableValue.fromJson(element))
        }

        /** @throws kotlinx.serialization.SerializationException on malformed JSON. */
        public fun decode(bytes: ByteArray): DatasourceDefinition =
            fromJson(json.parseToJsonElement(bytes.decodeToString()))
    }
}

/** Loads datasource files (the datasource part of iOS `A8.DataSourceProtocol`). */
public fun interface DatasourceLoader {
    /** Raw JSON bytes of [datasourceId] for [screenId]; throw [DatasourceException.NotFound] if missing. */
    public suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray
}

/** Resolves external datasource references of screen variables (Swift `DatasourceResolver`). */
public object DatasourceResolver {

    /**
     * Loads every variable with a `source` in parallel, checks its type and optional schema, and
     * returns the definitions with the data injected as `initialValue` (and `source` cleared).
     * The first failure cancels the rest and is rethrown.
     */
    public suspend fun resolveDatasources(
        screenId: String,
        variables: Map<String, VariableDefinition>,
        dataSource: DatasourceLoader,
    ): Map<String, VariableDefinition> {
        val withSource = variables.filter { it.value.source != null }
        if (withSource.isEmpty()) return variables
        val resolved = LinkedHashMap(variables)
        coroutineScope {
            withSource.map { (name, definition) ->
                val source = definition.source!!
                async {
                    val bytes = dataSource.getDatasource(screenId, source)
                    val datasource = DatasourceDefinition.decode(bytes)
                    val actualType = VariableType.inferType(datasource.rawData)
                    if (!definition.type.validate(datasource.rawData)) {
                        throw DatasourceException.TypeMismatch(source, definition.type, actualType.rawValue)
                    }
                    datasource.schema?.let { SchemaValidator.validate(datasource.rawData, it) }
                    name to definition.withResolvedValue(datasource.rawData)
                }
            }.awaitAll().forEach { (name, def) -> resolved[name] = def }
        }
        return resolved
    }

    /** True if any variable references an external datasource. */
    public fun hasExternalDatasources(variables: Map<String, VariableDefinition>?): Boolean =
        variables?.values?.any { it.hasExternalSource } ?: false
}
