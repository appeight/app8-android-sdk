package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-node platform overrides (app8-dsl `docs/platforms.md`, DSL 1.1).
 *
 * - A node whose `platforms` array does not list [platform] does not exist on it: returns `null`.
 * - The node's `<platform>` object is merged over the node (objects merge key by key, arrays and
 *   scalars replace); both `ios` and `android` keys are then dropped.
 * - The same merge is applied to the node's `content` object, where overrides are usually written.
 */
public object PlatformOverrides {

    public fun apply(node: JsonObject, platform: Platform): JsonObject? {
        if (!isIncluded(node, platform)) return null
        val merged = mergeOwnOverride(node, platform)
        val content = merged["content"] as? JsonObject ?: return merged
        if (!content.containsKey(Platform.IOS.key) && !content.containsKey(Platform.ANDROID.key)) return merged
        return JsonObject(LinkedHashMap(merged).apply { put("content", mergeOwnOverride(content, platform)) })
    }

    /** True unless the node has a `platforms` array without [platform]. */
    public fun isIncluded(node: JsonObject, platform: Platform): Boolean {
        val list = node["platforms"] as? JsonArray ?: return true
        return list.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == platform.key }
    }

    /** Objects merge recursively, everything else in [override] replaces. */
    public fun merge(base: JsonObject, override: JsonObject, depth: Int = 0): JsonObject {
        if (depth >= ModelLimits.MAX_DEEP_MERGE_DEPTH) return base
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in override) {
            val existing = out[key]
            out[key] = if (existing is JsonObject && value is JsonObject) merge(existing, value, depth + 1) else value
        }
        return JsonObject(out)
    }

    private fun mergeOwnOverride(node: JsonObject, platform: Platform): JsonObject {
        if (!node.containsKey(Platform.IOS.key) && !node.containsKey(Platform.ANDROID.key)) return node
        val override = node[platform.key] as? JsonObject
        val stripped = JsonObject(node.filterKeys { it != Platform.IOS.key && it != Platform.ANDROID.key })
        return if (override != null) merge(stripped, override) else stripped
    }
}
