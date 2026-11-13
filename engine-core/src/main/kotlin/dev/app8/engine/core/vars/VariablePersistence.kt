package dev.app8.engine.core.vars

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Storage behind persisted app variables (`persist: true`, app8-dsl `docs/variables.md`).
 * Values are JSON strings keyed by variable name; the platform layer maps names to its own keys
 * (`var.<name>` in the DataStore file `app8_<appId>` on Android).
 *
 * [stored] is read synchronously while variables are defined, so implementations load their
 * snapshot before the first render. [save] and [remove] are called on every change and may
 * debounce; a later call for the same name supersedes an earlier pending one.
 */
public interface VariablePersistence {
    /** The stored JSON for [name], or null. */
    public fun stored(name: String): String?

    public fun save(name: String, json: String)

    public fun remove(name: String)
}

/** In-memory [VariablePersistence] (tests, previews). */
public class InMemoryVariablePersistence(initial: Map<String, String> = emptyMap()) : VariablePersistence {
    public val values: MutableMap<String, String> = LinkedHashMap(initial)
    override fun stored(name: String): String? = values[name]
    override fun save(name: String, json: String) {
        values[name] = json
    }
    override fun remove(name: String) {
        values.remove(name)
    }
}

internal object PersistCodec {
    private val json = Json

    fun encode(value: Any?): String? = try {
        json.encodeToString(JsonElement.serializer(), Values.toJson(value))
    } catch (e: IllegalArgumentException) {
        null
    }

    fun decode(raw: String): Any? = try {
        Values.normalize(Values.fromJson(json.parseToJsonElement(raw)))
    } catch (e: Exception) {
        null
    }
}
