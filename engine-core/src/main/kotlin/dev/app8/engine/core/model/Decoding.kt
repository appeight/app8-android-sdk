package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hard limits bounding work done on untrusted DSL input (iOS `EngineLimits`).
 * Backstops against stack overflow and memory exhaustion, set far above any real DSL.
 */
public object ModelLimits {
    /** Maximum size of one DSL payload (app.json, a screen, styles.json, …). */
    public const val MAX_DSL_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    /** Maximum component nesting. A deeper component fails to decode (and is dropped from its parent's children). */
    public const val MAX_COMPONENT_DEPTH: Int = 64

    /** Maximum raw JSON nesting; a pure stack-overflow backstop. */
    public const val MAX_JSON_DEPTH: Int = 512

    /** Maximum recursion of template / platform-override deep merges. */
    public const val MAX_DEEP_MERGE_DEPTH: Int = 256
}

/** The platform the DSL is decoded for. Drives per-node `ios` / `android` overrides. */
public enum class Platform(public val key: String) {
    IOS("ios"),
    ANDROID("android"),
}

/** Thrown when a DSL value cannot be decoded. [path] is a JSON path like `content.children[2].style`. */
public open class DslDecodingException(
    public val path: String,
    public val reason: String,
    cause: Throwable? = null,
) : RuntimeException("$reason (at ${path.ifEmpty { "<root>" }})", cause)

/** A component node whose `platforms` list excludes the current platform. Parents drop it silently. */
public class PlatformExcludedException(path: String, platform: Platform) :
    DslDecodingException(path, "node not available on ${platform.key}")

/**
 * Collects per-element decode failures swallowed by lenient containers (iOS `DecodeErrorCollector`).
 * Used by diagnostics: a bad element is skipped, but the reason is still visible here.
 */
public class DecodeErrorCollector {
    public data class Entry(val typeName: String, val jsonPath: String, val error: Throwable)

    private val lock = Any()
    private val _entries = mutableListOf<Entry>()

    public val entries: List<Entry> get() = synchronized(lock) { _entries.toList() }

    internal fun record(typeName: String, jsonPath: String, error: Throwable) {
        synchronized(lock) { _entries.add(Entry(typeName, jsonPath, error)) }
    }
}

/** Options shared by one decode pass. */
public class DecodeOptions(
    public val platform: Platform = Platform.ANDROID,
    /** Resolves `{ "id": … }` animation pointers at decode time (iOS `app8AnimationResolver`). */
    public val animationResolver: ((String) -> Animation.Inline?)? = null,
    /** Resolves `{ "id": … }` transition pointers at decode time (iOS `app8TransitionResolver`). */
    public val transitionResolver: ((String) -> ScreenTransition.Inline?)? = null,
    public val errorCollector: DecodeErrorCollector? = null,
)

/**
 * A position in a JSON tree. Mirrors Swift's `Decoder` semantics:
 * - [req] = `decode(_:forKey:)`: missing key or `null` throws.
 * - [opt] = `decodeIfPresent(_:forKey:)`: missing or `null` gives `null`, a present-but-invalid value throws.
 * - primitives never coerce (a number is not a string, a string is not a bool).
 */
internal class Cursor(
    val element: JsonElement,
    val path: String,
    val options: DecodeOptions,
    val componentDepth: Int = 0,
) {
    val isNull: Boolean get() = element is JsonNull

    fun fail(reason: String): Nothing = throw DslDecodingException(path, reason)

    fun obj(): JsonObject = element as? JsonObject ?: fail("expected an object, found ${describe(element)}")

    fun arr(): JsonArray = element as? JsonArray ?: fail("expected an array, found ${describe(element)}")

    fun isObject(): Boolean = element is JsonObject

    fun has(key: String): Boolean = (element as? JsonObject)?.containsKey(key) == true

    /** Key present with a non-null value. */
    fun hasNonNull(key: String): Boolean {
        val v = (element as? JsonObject)?.get(key) ?: return false
        return v !is JsonNull
    }

    fun child(key: String): Cursor? = obj()[key]?.let { Cursor(it, childPath(key), options, componentDepth) }

    fun with(element: JsonElement, path: String = this.path): Cursor = Cursor(element, path, options, componentDepth)

    fun childPath(key: String): String = if (path.isEmpty()) key else "$path.$key"

    fun indexPath(index: Int): String = "$path[$index]"

    fun string(): String {
        val p = element as? JsonPrimitive
        if (p == null || p is JsonNull || !p.isString) fail("expected a string, found ${describe(element)}")
        return p.content
    }

    fun stringOrNull(): String? {
        val p = element as? JsonPrimitive ?: return null
        return if (p !is JsonNull && p.isString) p.content else null
    }

    fun double(): Double = doubleOrNull() ?: fail("expected a number, found ${describe(element)}")

    fun doubleOrNull(): Double? {
        val p = element as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return p.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun float(): Float = double().toFloat()

    fun int(): Int {
        val d = double()
        if (d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) fail("expected an integer, found ${describe(element)}")
        return d.toInt()
    }

    fun bool(): Boolean {
        val p = element as? JsonPrimitive
        if (p != null && p !is JsonNull && !p.isString) {
            if (p.content == "true") return true
            if (p.content == "false") return false
        }
        fail("expected a boolean, found ${describe(element)}")
    }

    /** `decode(_:forKey:)`. */
    inline fun <T> req(key: String, decode: (Cursor) -> T): T {
        val value = obj()[key] ?: fail("missing key '$key'")
        val c = Cursor(value, childPath(key), options, componentDepth)
        if (value is JsonNull) c.fail("value for '$key' is null")
        return decode(c)
    }

    /** `decodeIfPresent(_:forKey:)`. */
    inline fun <T> opt(key: String, decode: (Cursor) -> T): T? {
        val value = obj()[key] ?: return null
        if (value is JsonNull) return null
        return decode(Cursor(value, childPath(key), options, componentDepth))
    }

    /** `try? …` — any decode failure becomes `null`. */
    inline fun <T> attempt(decode: (Cursor) -> T): T? = try {
        decode(this)
    } catch (e: Exception) {
        null
    }

    fun reqString(key: String): String = req(key) { it.string() }
    fun optString(key: String): String? = opt(key) { it.string() }
    fun optBool(key: String): Boolean? = opt(key) { it.bool() }
    fun optDouble(key: String): Double? = opt(key) { it.double() }
    fun optFloat(key: String): Float? = opt(key) { it.float() }
    fun optInt(key: String): Int? = opt(key) { it.int() }
    fun reqDouble(key: String): Double = req(key) { it.double() }
    fun reqFloat(key: String): Float = req(key) { it.float() }

    /** Strict array: any bad element fails the whole array. */
    inline fun <T> list(decode: (Cursor) -> T): List<T> =
        arr().mapIndexed { i, e -> decode(Cursor(e, indexPath(i), options, componentDepth)) }

    /** Strict `[String: T]` (insertion order kept). */
    inline fun <T> map(decode: (Cursor) -> T): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        for ((k, v) in obj()) out[k] = decode(Cursor(v, childPath(k), options, componentDepth))
        return out
    }

    fun record(typeName: String, error: Throwable) {
        options.errorCollector?.record(typeName, (error as? DslDecodingException)?.path ?: path, error)
    }

    companion object {
        fun describe(e: JsonElement): String = when (e) {
            is JsonNull -> "null"
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonPrimitive -> when {
                e.isString -> "string \"${e.content.take(40)}\""
                e.content == "true" || e.content == "false" -> "boolean ${e.content}"
                else -> "number ${e.content}"
            }
        }
    }
}

// MARK: - Lenient containers (iOS Utils/Codables)

/**
 * `FailableDecodable` over an array: decodes each element, drops the ones that fail
 * (recording them in the [DecodeErrorCollector]). Throws only when the value is not an array.
 */
internal inline fun <T> Cursor.failableList(typeName: String, decode: (Cursor) -> T): List<T> {
    val out = ArrayList<T>()
    arr().forEachIndexed { i, e ->
        val c = Cursor(e, indexPath(i), options, componentDepth)
        try {
            out.add(decode(c))
        } catch (ex: PlatformExcludedException) {
            // Not an error: the node simply does not exist on this platform.
        } catch (ex: Exception) {
            c.record(typeName, ex)
        }
    }
    return out
}

/**
 * `@SafeArrayDecodable`: missing key, `null` or a non-array give `[]`; bad elements are dropped.
 * Never throws.
 */
internal inline fun <T> Cursor.safeList(key: String, typeName: String, decode: (Cursor) -> T): List<T> {
    val c = (element as? JsonObject)?.get(key)?.let { Cursor(it, childPath(key), options, componentDepth) } ?: return emptyList()
    if (c.isNull) return emptyList()
    return try {
        c.failableList(typeName, decode)
    } catch (e: Exception) {
        c.record(typeName, e)
        emptyList()
    }
}

/** `@SafeOptionalArrayDecodable`: missing, `null` or non-array give `null`; bad elements are dropped. Never throws. */
internal inline fun <T> Cursor.safeOptionalList(key: String, typeName: String, decode: (Cursor) -> T): List<T>? {
    val c = (element as? JsonObject)?.get(key)?.let { Cursor(it, childPath(key), options, componentDepth) } ?: return null
    if (c.isNull) return null
    return try {
        c.failableList(typeName, decode)
    } catch (e: Exception) {
        null
    }
}

/** Strict enum: unknown raw value throws. Enum constants are named exactly like their DSL raw values unless [raw] says otherwise. */
internal inline fun <reified E : Enum<E>> Cursor.strictEnum(raw: (E) -> String = { it.name }): E {
    val s = string()
    return enumValues<E>().firstOrNull { raw(it) == s } ?: fail("unknown ${E::class.simpleName} value '$s'")
}

/** `SafeEnumCodable`: any failure (unknown value, wrong JSON type) gives [unknown]. */
internal inline fun <reified E : Enum<E>> Cursor.safeEnum(unknown: E, raw: (E) -> String = { it.name }): E {
    val s = stringOrNull() ?: return unknown
    return enumValues<E>().firstOrNull { raw(it) == s } ?: unknown
}

internal inline fun <reified E : Enum<E>> Cursor.optEnum(key: String, noinline raw: (E) -> String = { it.name }): E? =
    opt(key) { it.strictEnum(raw) }

internal inline fun <reified E : Enum<E>> Cursor.optSafeEnum(key: String, unknown: E, noinline raw: (E) -> String = { it.name }): E? =
    opt(key) { it.safeEnum(unknown, raw) }

/** `[String: T]` keyed by a String-raw enum: unknown keys are dropped, an empty result becomes `null` (iOS `actions`/`analytics`/`triggers`). */
internal inline fun <reified K : Enum<K>, V> Map<String, V>.keyedByEnum(): Map<K, V>? {
    val out = LinkedHashMap<K, V>()
    for ((k, v) in this) {
        val key = enumValues<K>().firstOrNull { it.name == k } ?: continue
        out[key] = v
    }
    return out.ifEmpty { null }
}
