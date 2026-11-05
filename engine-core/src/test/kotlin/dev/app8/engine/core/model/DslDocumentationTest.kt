package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.decodeCorner
import dev.app8.engine.core.model.style.decodeFill
import dev.app8.engine.core.model.style.decodeMaterial
import dev.app8.engine.core.model.style.decodeOutline
import dev.app8.engine.core.model.style.decodeShadowLayer
import dev.app8.engine.core.model.style.decodeTextModel
import dev.app8.engine.core.model.style.decodeTransform
import dev.app8.engine.core.model.style.decodeVisualEffect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every ```json block in app8-dsl `docs/` decodes with the matching model
 * (port of iOS `DSLDocumentationTests` + `DocTestSupport`, same type-inference heuristics).
 *
 * Docs location: system property `app8.dsl.docs`, otherwise the first `app8-dsl/docs` found next to
 * the working directory or one of its ancestors. Skipped (with a message) when not found.
 */
class DslDocumentationTest {

    @Test
    fun expectedDocumentationFilesExist() {
        val docs = docsDirOrSkip()
        val names = docs.listFiles()!!.map { it.name }.toSet()
        val missing = EXPECTED_FILES.filterNot { it in names }
        if (missing.isNotEmpty()) fail("Missing expected documentation files: $missing")
    }

    @Test
    fun documentationExamplesAreValid() {
        val docs = docsDirOrSkip()
        val files = docs.walkTopDown().filter { it.isFile && it.extension == "md" }.sortedBy { it.path }.toList()
        if (files.isEmpty()) fail("No markdown files found in $docs")

        val passed = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<Pair<String, String>>()
        for (file in files) {
            for (example in MarkdownJsonExtractor.extract(file, docs)) {
                when (val r = DslExampleValidator.validate(example)) {
                    is Result.Success -> passed += example.location to r.decodedAs
                    is Result.Skipped -> skipped += example.location to r.reason
                    is Result.Failed -> failures += example.location to r.error
                }
            }
        }

        val report = buildString {
            appendLine("=== Documentation Examples Test ===")
            appendLine("Docs: $docs")
            appendLine("Files scanned: ${files.size}")
            appendLine("Passed: ${passed.size}")
            appendLine("Skipped: ${skipped.size}")
            appendLine("Failed: ${failures.size}")
            appendLine("--- Passed by type ---")
            passed.groupBy { it.second }.toSortedMap().forEach { (t, l) -> appendLine("  $t: ${l.size}") }
            appendLine("--- Skipped by reason ---")
            skipped.groupBy { it.second }.toSortedMap().forEach { (r, l) ->
                appendLine("  $r: ${l.size}")
                l.take(10).forEach { appendLine("    - ${it.first}") }
            }
            if (DslExampleValidator.fullDecodeGaps.isNotEmpty()) {
                appendLine("--- Partial component snippets (structurally valid, full decode rejects them) ---")
                DslExampleValidator.fullDecodeGaps.forEach { appendLine("  $it") }
            }
            if (failures.isNotEmpty()) {
                appendLine("--- Failed ---")
                failures.forEach { appendLine("  ${it.first}: ${it.second}") }
            }
        }
        println(report)
        if (failures.isNotEmpty()) fail(report)
    }

    private fun docsDirOrSkip(): File {
        val dir = resolveDocsDirectory()
        if (dir == null) println("DslDocumentationTest: app8-dsl docs not found (set -Dapp8.dsl.docs=…); skipping")
        assumeTrue("app8-dsl docs not found", dir != null)
        return dir!!
    }

    private fun resolveDocsDirectory(): File? {
        System.getProperty("app8.dsl.docs")?.takeIf { it.isNotBlank() }?.let { p ->
            return File(p).takeIf { it.isDirectory }
        }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app8-dsl/docs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        val EXPECTED_FILES = listOf(
            "actions.md", "collections.md", "components.md", "index.md", "layout.md",
            "navigation.md", "states.md", "styles.md", "templates.md", "variables.md", "platforms.md",
        )
    }
}

// MARK: - Extraction (port of MarkdownJSONExtractor.swift)

internal data class ExtractedExample(val json: String, val location: String, val annotation: Annotation?) {
    sealed interface Annotation {
        data class Type(val name: String) : Annotation
        data class Skip(val reason: String) : Annotation
    }
}

internal object MarkdownJsonExtractor {
    fun extract(file: File, root: File): List<ExtractedExample> {
        val name = file.relativeTo(root).path
        val examples = mutableListOf<ExtractedExample>()
        var pending: ExtractedExample.Annotation? = null
        var inBlock = false
        var start = 0
        val content = mutableListOf<String>()
        file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            parseAnnotation(trimmed)?.let {
                pending = it
                return@forEachIndexed
            }
            if (trimmed.startsWith("```json")) {
                inBlock = true
                start = index + 1
                content.clear()
                return@forEachIndexed
            }
            if (inBlock && trimmed.startsWith("```")) {
                inBlock = false
                examples += ExtractedExample(content.joinToString("\n"), "$name:$start", pending)
                pending = null
                content.clear()
                return@forEachIndexed
            }
            if (inBlock) {
                content += line
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("<!--")) {
                pending = null
            }
        }
        return examples
    }

    private fun parseAnnotation(trimmed: String): ExtractedExample.Annotation? {
        if (!trimmed.startsWith("<!--") || !trimmed.endsWith("-->")) return null
        val body = trimmed.removePrefix("<!--").removeSuffix("-->").trim()
        return when {
            body.startsWith("@dsl-type:") -> ExtractedExample.Annotation.Type(body.removePrefix("@dsl-type:").trim())
            body.startsWith("@dsl-skip:") -> ExtractedExample.Annotation.Skip(body.removePrefix("@dsl-skip:").trim())
            body == "@dsl-skip" -> ExtractedExample.Annotation.Skip("marked to skip")
            else -> null
        }
    }
}

// MARK: - Validation (port of DSLExampleValidator.swift)

internal sealed interface Result {
    data class Success(val decodedAs: String) : Result
    data class Skipped(val reason: String) : Result
    data class Failed(val error: String) : Result
}

internal object DslExampleValidator {

    /** Component types from the iOS validator plus every type the engine decodes. */
    private val componentTypes: Set<String> = setOf(
        "view", "button", "label", "icon", "image", "textField", "textView", "collection", "scroll", "scrollView",
        "stack", "stackView", "spacer", "pager", "screen", "tabBarScreen", "map", "shape",
    ) + ComponentType.Key.entries.map { it.name }

    private val actionTypes: Set<String> = Action.Type.entries.map { it.name }.toSet()

    private val validTriggers = setOf("touchDown", "touchUp", "focus", "blur", "hover", "hoverEnd")

    private val decoder = DslDecoder()

    private var location = ""

    fun validate(example: ExtractedExample): Result {
        location = example.location
        (example.annotation as? ExtractedExample.Annotation.Skip)?.let { return Result.Skipped("@dsl-skip: ${it.reason}") }
        if (example.json.contains("...")) return Result.Skipped("contains placeholder '...'")
        val trimmed = example.json.trim()
        if (trimmed == "{ }" || trimmed == "{}") return Result.Skipped("empty object placeholder")
        if (trimmed.contains("[ ]")) return Result.Skipped("contains empty array placeholder '[ ]'")
        val json = try {
            Json.parseToJsonElement(example.json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Result.Skipped("not a valid JSON object")

        (example.annotation as? ExtractedExample.Annotation.Type)?.let { return validateWithType(it.name, json) }
        return validateWithInferredType(json)
    }

    private fun attempt(name: String, block: () -> Unit): Result = try {
        block()
        Result.Success(name)
    } catch (e: Exception) {
        Result.Failed(e.message ?: e.toString())
    }

    private fun <T> decode(element: JsonElement, f: (Cursor) -> T): T = f(decoder.root(element))

    private fun validateWithType(type: String, json: JsonObject): Result = when (type) {
        "Component", "Component.Any" -> validateComponent(json, location)
        "App" -> attempt("App") { decoder.decodeApp(json) }
        "Layout" -> attempt("Layout") { decode(json, ::decodeLayout) }
        "Fill" -> attempt("Fill") { decode(json, ::decodeFill) }
        "Corner" -> attempt("Corner") { decode(json, ::decodeCorner) }
        "Material" -> attempt("Material") { decode(json, ::decodeMaterial) }
        "Shadow" -> attempt("Shadow") { decode(json) { c -> c.list(::decodeShadowLayer) } }
        "TextModel" -> attempt("TextModel") { decode(json, ::decodeTextModel) }
        "Constraint" -> attempt("Constraint") { decode(json, ::decodeConstraint) }
        else -> Result.Skipped("unknown type annotation '$type'")
    }

    private fun validateWithInferredType(json: JsonObject): Result {
        (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { type ->
            return when (type) {
                in actionTypes -> validateAction(json)
                in componentTypes -> validateComponent(json, location)
                else -> Result.Failed("Unknown type: '$type'")
            }
        }
        if (json["title"] != null && json["navigation"] != null) return attempt("App") { decoder.decodeApp(json) }

        val layoutKeys = setOf("width", "height", "leading", "trailing", "top", "bottom", "constraints", "ignoresSafeArea")
        if (json.keys.any { it in layoutKeys } && json["type"] == null) {
            if (json["layout"] is JsonObject) return Result.Skipped("layout wrapper fragment, not standalone Layout")
            return attempt("Layout") { decode(json, ::decodeLayout) }
        }
        if (json["solid"] != null || json["gradient"] != null) return attempt("Fill") { decode(json, ::decodeFill) }
        if (json["radius"] != null && json["curve"] != null) return attempt("Corner") { decode(json, ::decodeCorner) }
        (json["corner"] as? JsonObject)?.let { return attempt("Corner (from wrapper)") { decode(it, ::decodeCorner) } }
        (json["outline"] as? JsonObject)?.let { return attempt("Outline (from wrapper)") { decode(it, ::decodeOutline) } }
        (json["shadow"] as? JsonArray)?.takeIf { a -> a.all { it is JsonObject } }?.let {
            return attempt("Shadow (from wrapper)") { decode(it) { c -> c.list(::decodeShadowLayer) } }
        }
        (json["material"] as? JsonArray)?.takeIf { a -> a.all { it is JsonObject } }?.let {
            return attempt("Material (from wrapper)") { decode(it, ::decodeMaterial) }
        }
        (json["visualEffect"] as? JsonObject)?.let { return attempt("VisualEffect (from wrapper)") { decode(it, ::decodeVisualEffect) } }
        (json["text"] as? JsonObject)?.takeIf { it["fontSize"] != null || it["fontWeight"] != null }?.let {
            return attempt("TextModel (from wrapper)") { decode(it, ::decodeTextModel) }
        }
        (json["transform"] as? JsonObject)?.let { return attempt("Transform (from wrapper)") { decode(it, ::decodeTransform) } }
        (json["padding"] as? JsonObject)?.takeIf { p -> listOf("top", "left", "bottom", "right").any { p[it] != null } }?.let {
            return attempt("EdgeInsets") { decode(it, ::decodeEdgeInsets) }
        }
        if (json["children"] != null) return Result.Skipped("children fragment without type")
        if (json["properties"] != null) return Result.Skipped("properties fragment without type")
        if (json["style"] != null) return Result.Skipped("style fragment (wrapper)")
        if (json["actions"] != null) return Result.Skipped("actions fragment (partial)")
        if (json["target"] != null && (json["constant"] != null || json["attribute"] != null)) {
            return attempt("Constraint") { decode(json, ::decodeConstraint) }
        }
        if (json.size == 1 && json.keys.first() in setOf("color", "tint", "tintColor")) return Result.Skipped("color value example")
        if (json.size <= 2) return Result.Skipped("simple key-value example, cannot infer type")
        return Result.Skipped("cannot infer DSL type from structure")
    }

    /** Snippets that pass the iOS structural check but are partial (fail a full decode on iOS and Android alike). */
    val fullDecodeGaps = mutableListOf<String>()

    /**
     * iOS validates components structurally (doc snippets are often partial: no `id`, `{ }` templates, …),
     * so that check is the verdict here too. The Kotlin port additionally runs the real decoder (with a
     * placeholder `id` when missing) and reports snippets it rejects as informational gaps.
     */
    private fun validateComponent(json: JsonObject, location: String = ""): Result {
        val type = (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Result.Failed("Component missing 'type' field")
        if (type !in componentTypes) return Result.Failed("Unknown component type: '$type'")
        (json["content"] as? JsonObject)?.let { content ->
            (content["states"] as? JsonObject)?.forEach { (name, value) ->
                if (value !is JsonObject) return Result.Failed("State '$name' is not an object")
            }
            (content["triggers"] as? JsonObject)?.keys?.filterNot { it in validTriggers }?.takeIf { it.isNotEmpty() }?.let {
                return Result.Failed("Unknown triggers: ${it.sorted()}")
            }
        }
        if (ComponentType.Key.entries.none { it.name == type }) return Result.Success("Component (structural, alias '$type')")
        val withId = if (json.containsKey("id")) json else JsonObject(LinkedHashMap(json).apply { put("id", JsonPrimitive("doc-example")) })
        return try {
            decoder.decodeComponent(withId)
            Result.Success("Component ($type)")
        } catch (e: PlatformExcludedException) {
            Result.Success("Component ($type, not on android)")
        } catch (e: Exception) {
            fullDecodeGaps += "$location: '$type': ${e.message}"
            Result.Success("Component (structural only)")
        }
    }

    private fun validateAction(json: JsonObject): Result {
        val type = (json["type"] as JsonPrimitive).content
        val missing: String? = when (type) {
            "navigation" -> if (json["nextScreen"] == null && (json["isBack"] as? JsonPrimitive)?.content != "true") "nextScreen" else null
            "selectTab" -> if (json["tabId"] == null && json["tabIndex"] == null) "tabId' or 'tabIndex" else null
            "updateVariable", "incrementVariable", "toggleArrayValue" -> if (json["variableName"] == null) "variableName" else null
            "updateMultipleVariables" -> if (json["updates"] == null) "updates" else null
            "setState" -> if (json["stateName"] == null) "stateName" else null
            "focus" -> if (json["target"] == null) "target" else null
            "executeFunction" -> if (json["function"] == null) "function" else null
            "completeFlow" -> if (json["destination"] == null) "destination" else null
            else -> null
        }
        if (missing != null) return Result.Failed("$type action missing '$missing'")
        return attempt("Action ($type)") { decode(json, ::decodeAction) }
    }
}
