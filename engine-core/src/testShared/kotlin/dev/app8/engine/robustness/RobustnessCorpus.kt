package dev.app8.engine.robustness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * Inputs and mutations for the robustness (fuzz) tests in `engine-core` and `engine`.
 * Shared as a test source directory (`src/testShared/kotlin`) by both modules.
 *
 * The built-in corpus is `engine-core/src/test/resources/robustness`: app8-dsl doc examples
 * plus hand-written edge cases. When `APP8_CONFORMANCE_FIXTURES` (environment variable or
 * system property) points at a conformance `fixtures` directory, its apps and screens are
 * added; otherwise that part is skipped.
 */
internal object RobustnessCorpus {
    enum class Kind { APP, SCREEN, TEMPLATE, STYLES }

    class Doc(val name: String, val kind: Kind, val json: JsonElement)

    /** Documents that load together (an app with its screens, templates and styles). */
    class Unit(val name: String, val docs: List<Doc>) {
        fun of(kind: Kind) = docs.filter { it.kind == kind }
    }

    const val FIXTURES_ENV = "APP8_CONFORMANCE_FIXTURES"

    fun builtIn(): List<Unit> {
        val dir = findUp("engine-core/src/test/resources/robustness")
            ?: error("robustness corpus not found above ${System.getProperty("user.dir")}")
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        val shared = files.filter { it.name.startsWith("styles-") || it.name.startsWith("template-") }.map(::doc)
        // Each screen is its own unit with the shared styles and templates; each app on its own.
        return files.filter { it.name.startsWith("screen-") || it.name.startsWith("app-") }
            .map { Unit(it.name, listOf(doc(it)) + shared) }
    }

    /** Conformance fixtures, or empty (and a printed note) when [FIXTURES_ENV] is not set. */
    fun conformance(): List<Unit> {
        val path = System.getenv(FIXTURES_ENV)?.takeIf { it.isNotBlank() }
            ?: System.getProperty(FIXTURES_ENV)?.takeIf { it.isNotBlank() }
        if (path == null) {
            println("robustness: $FIXTURES_ENV not set; conformance fixtures skipped")
            return emptyList()
        }
        val root = File(path)
        require(root.isDirectory) { "$FIXTURES_ENV=$path is not a directory" }
        val units = mutableListOf<Unit>()
        for (group in listOf("apps", "screens")) {
            val dirs = File(root, group).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
            for (d in dirs) {
                val docs = d.walkTopDown()
                    .filter { it.isFile && it.extension == "json" && it.name != "metadata.json" && it.name != "steps.json" }
                    .sortedBy { it.path }
                    .mapNotNull { f -> kindOf(f, d)?.let { Doc(f.relativeTo(root).path, it, parse(f)) } }
                    .toList()
                if (docs.isNotEmpty()) units += Unit("$group/${d.name}", docs)
            }
        }
        return units
    }

    private fun kindOf(f: File, unitDir: File): Kind? {
        val rel = f.relativeTo(unitDir).path
        return when {
            rel == "app.json" -> Kind.APP
            rel.startsWith("templates/") || rel.startsWith("components/") -> Kind.TEMPLATE
            rel.startsWith("styles/") -> Kind.STYLES
            rel == "screen.json" || rel.startsWith("screens/") -> Kind.SCREEN
            else -> null
        }
    }

    private fun doc(f: File): Doc {
        val kind = when {
            f.name.startsWith("app-") -> Kind.APP
            f.name.startsWith("styles-") -> Kind.STYLES
            f.name.startsWith("template-") -> Kind.TEMPLATE
            else -> Kind.SCREEN
        }
        return Doc(f.name, kind, parse(f))
    }

    private fun parse(f: File): JsonElement = Json.parseToJsonElement(f.readText())

    private fun findUp(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }
}

/**
 * Seeded structural mutations of DSL JSON: dropped keys, wrong value types, unknown component
 * / action types and enum values, empty arrays, out-of-range numbers, broken expressions,
 * duplicated elements.
 */
internal class JsonMutator(seed: Long) {
    private val random = Random(seed)
    val applied = mutableListOf<String>()

    /** A seeded choice in `0 until n`. */
    fun pick(n: Int): Int = random.nextInt(n)

    fun mutate(root: JsonElement, count: Int): JsonElement {
        var current = root
        repeat(count) { current = mutateOnce(current) }
        return current
    }

    private fun mutateOnce(root: JsonElement): JsonElement {
        val paths = mutableListOf<List<Any>>()
        collect(root, emptyList(), paths)
        if (paths.isEmpty()) return root
        val path = paths[random.nextInt(paths.size)]
        val target = get(root, path)
        val op = random.nextInt(9)
        val replacement: JsonElement? = when (op) {
            // Drop: only meaningful for object members (an array element becomes null instead).
            0 -> null
            1 -> WRONG_TYPES[random.nextInt(WRONG_TYPES.size)]
            2 -> (target as? JsonObject)?.takeIf { it["type"] is JsonPrimitive }
                ?.let { JsonObject(it + ("type" to JsonPrimitive(UNKNOWN_TYPES[random.nextInt(UNKNOWN_TYPES.size)]))) }
                ?: JsonPrimitive("bogusEnumValue")
            3 -> if (target is JsonPrimitive && target.isString) JsonPrimitive("bogusEnumValue") else JsonArray(emptyList())
            4 -> if (target is JsonArray) JsonArray(emptyList()) else JsonObject(emptyMap())
            5 -> JsonPrimitive(OUT_OF_RANGE[random.nextInt(OUT_OF_RANGE.size)])
            6 -> JsonPrimitive(BAD_EXPRESSIONS[random.nextInt(BAD_EXPRESSIONS.size)])
            7 -> (target as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { JsonArray(it + it) } ?: JsonNull
            else -> JsonPrimitive("{{" + INDEX_EXPRESSIONS[random.nextInt(INDEX_EXPRESSIONS.size)] + "}}")
        }
        applied += "op$op@${path.joinToString(".")}"
        return set(root, path, replacement)
    }

    private fun collect(e: JsonElement, path: List<Any>, out: MutableList<List<Any>>) {
        if (path.isNotEmpty()) out += path
        when (e) {
            is JsonObject -> for ((k, v) in e) collect(v, path + k, out)
            is JsonArray -> e.forEachIndexed { i, v -> collect(v, path + i, out) }
            else -> {}
        }
    }

    private fun get(e: JsonElement, path: List<Any>): JsonElement =
        path.fold(e) { acc, k -> if (k is String) (acc as JsonObject).getValue(k) else (acc as JsonArray)[k as Int] }

    /** [value] null drops an object member (or nulls an array element). */
    private fun set(e: JsonElement, path: List<Any>, value: JsonElement?): JsonElement {
        if (path.isEmpty()) return value ?: JsonNull
        val head = path.first()
        val rest = path.drop(1)
        return when (e) {
            is JsonObject -> {
                val key = head as String
                if (rest.isEmpty() && value == null) JsonObject(e - key)
                else JsonObject(e + (key to set(e.getValue(key), rest, value)))
            }
            is JsonArray -> {
                val i = head as Int
                JsonArray(e.mapIndexed { j, v -> if (j == i) set(v, rest, value) else v })
            }
            else -> e
        }
    }

    companion object {
        val WRONG_TYPES: List<JsonElement> = listOf(
            JsonPrimitive("x"), JsonPrimitive(""), JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(3.5),
            JsonPrimitive(true), JsonPrimitive(false), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()),
            JsonArray(listOf(JsonPrimitive(1), JsonNull)), JsonObject(mapOf("id" to JsonPrimitive(42))),
            JsonPrimitive("#ZZZZZZ"), JsonPrimitive("NaN"), JsonPrimitive("-Infinity"),
        )
        val UNKNOWN_TYPES = listOf("noSuchComponent", "noSuchAction", "striplet", "list", "", "screen", "Label")
        val OUT_OF_RANGE: List<Number> = listOf(-1, -1000, 0, 999_999, 2_147_483_648L, -2_147_483_649L, 1e308, -1e308, 0.5, 1e-9)
        val BAD_EXPRESSIONS = listOf(
            "{{", "}}", "{{}}", "{{ ( }}", "{{1/0}}", "{{1 % 0}}", "{{a.b.c[99].d}}", "{{items[-1].name}}",
            "{{undefinedVar + 1}}", "{{'unterminated}}", "{{f(}}", "{{[1,2}}", "{{ {a: }}", "{{0/0}}",
            "{{count(nothing)}}", "{{x ? : y}}", "{{${"(".repeat(200)}1${")".repeat(200)}}}", "text {{ }} {{",
        )
        val INDEX_EXPRESSIONS = listOf("-1", "99999", "0", "1/0", "-0.5", "1e300", "'NaN'", "null", "[]", "{}")
    }
}
