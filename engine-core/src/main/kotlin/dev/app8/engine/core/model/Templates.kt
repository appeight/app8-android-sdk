package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A reusable component definition (`components/<id>.json`), referenced by instances via `templateId`. */
public data class ComponentTemplate(
    val id: String,
    /** Parent template (inheritance). */
    val templateId: String? = null,
    val type: ComponentType,
    /** Raw `content`, merged with instances by [TemplatePreprocessor]. */
    val rawContent: JsonElement? = null,
) {
    public val isBaseTemplate: Boolean get() = templateId == null
}

internal fun decodeComponentTemplate(c: Cursor): ComponentTemplate {
    val node = PlatformOverrides.apply(c.obj(), c.options.platform)
        ?: throw PlatformExcludedException(c.path, c.options.platform)
    val t = c.with(node)
    return ComponentTemplate(
        id = t.reqString("id"),
        templateId = t.optString("templateId"),
        type = t.req("type") { ComponentType.of(it.string()) },
        rawContent = t.opt("content") { it.element },
    )
}

/** Resolves template inheritance into flat content per template id (iOS `TemplateResolver`). */
public class TemplateResolver(private val templates: List<ComponentTemplate>) {
    private val types: Map<String, ComponentType> = templates.associate { it.id to it.type }
    private val resolved = LinkedHashMap<String, JsonElement>()

    public val resolvedTemplates: Map<String, JsonElement> get() = resolved

    init {
        var madeProgress = true
        while (madeProgress) {
            madeProgress = false
            for (template in templates) {
                if (resolved.containsKey(template.id)) continue
                val value = resolveTemplate(template) ?: continue
                resolved[template.id] = value
                madeProgress = true
            }
        }
    }

    private fun resolveTemplate(template: ComponentTemplate): JsonElement? {
        val parentId = template.templateId ?: return template.rawContent ?: JsonObject(emptyMap())
        val parent = resolved[parentId] as? JsonObject ?: return null
        val raw = template.rawContent ?: return parent
        return JsonObject(DeepMerge.merge(parent, raw))
    }

    public fun templateType(templateId: String): ComponentType? = types[templateId]

    public fun hasTemplate(templateId: String): Boolean = resolved.containsKey(templateId)
}

/** Expands `templateId` references in a component tree (iOS `TemplatePreprocessor`). */
public class TemplatePreprocessor(private val resolver: TemplateResolver) {

    public fun process(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> processObject(value)
        is JsonArray -> JsonArray(value.map { process(it) })
        else -> value
    }

    private fun processObject(obj: JsonObject): JsonElement {
        val processed = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) processed[k] = process(v)
        val templateId = (processed["templateId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val templateContent = templateId?.let { resolver.resolvedTemplates[it] } ?: return JsonObject(processed)

        if (processed["type"] == null) resolver.templateType(templateId)?.let { processed["type"] = JsonPrimitive(it.raw) }

        if (templateContent is JsonObject) {
            val instanceContent = processed["content"] as? JsonObject ?: JsonObject(emptyMap())
            val merged = LinkedHashMap(DeepMerge.merge(templateContent, instanceContent))
            val instanceVariables = processed["variables"] as? JsonObject
            if (instanceVariables != null) {
                // Instance variables replace template variables whole (not deep-merged).
                val vars = LinkedHashMap<String, JsonElement>((merged["variables"] as? JsonObject) ?: JsonObject(emptyMap()))
                vars.putAll(normalizeVariables(instanceVariables))
                merged["variables"] = JsonObject(vars)
            }
            processed["content"] = JsonObject(merged)
        }
        processed.remove("templateId")
        processed.remove("variables")
        return JsonObject(processed)
    }

    private fun normalizeVariables(variables: JsonObject): Map<String, JsonElement> = variables.mapValues { (_, value) ->
        if (value is JsonObject && value["type"] != null) return@mapValues value
        val type = when {
            value is JsonNull -> "string"
            value is JsonPrimitive && value.isString -> "string"
            value is JsonPrimitive && (value.content == "true" || value.content == "false") -> "boolean"
            value is JsonPrimitive -> "number"
            value is JsonArray -> "array"
            else -> "object"
        }
        // Expression strings become `computed` so they evaluate against the parent scope.
        val isExpression = value is JsonPrimitive && value.isString && value.content.contains("{{")
        JsonObject(mapOf("type" to JsonPrimitive(type), (if (isExpression) "computed" else "initialValue") to value))
    }
}

/**
 * Template deep merge (iOS `deepMerge`): objects merge recursively, `children` arrays merge by
 * element `id` (matches deep-merged, others appended), other arrays and scalars are replaced.
 */
public object DeepMerge {

    public fun merge(base: JsonObject, override: JsonElement, depth: Int = 0): Map<String, JsonElement> {
        if (override !is JsonObject) return base
        if (depth >= ModelLimits.MAX_DEEP_MERGE_DEPTH) return base
        val result = LinkedHashMap<String, JsonElement>(base)
        for ((key, overrideValue) in override) {
            val baseValue = result[key]
            result[key] = when {
                baseValue is JsonObject && overrideValue is JsonObject -> JsonObject(merge(baseValue, overrideValue, depth + 1))
                baseValue is JsonArray && overrideValue is JsonArray && key == "children" ->
                    JsonArray(mergeChildren(baseValue, overrideValue, depth + 1))
                else -> overrideValue
            }
        }
        return result
    }

    public fun merge(base: JsonElement, override: JsonElement, depth: Int = 0): JsonElement =
        if (base is JsonObject) JsonObject(merge(base, override, depth)) else override

    private fun mergeChildren(base: JsonArray, override: JsonArray, depth: Int): List<JsonElement> {
        val result = base.toMutableList()
        for (element in override) {
            val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null) {
                result.add(element)
                continue
            }
            val index = result.indexOfFirst { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == id }
            if (index >= 0) result[index] = merge(result[index], element, depth + 1) else result.add(element)
        }
        return result
    }
}
