package dev.app8.engine.runtime

import dev.app8.engine.core.model.DslDecodingException
import dev.app8.engine.core.model.PlatformExcludedException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Decodes a whole document (a screen, `app.json`) so that one bad value does not reject it.
 *
 * Elements of lenient lists (children, material layers, actions, …) already ignore a bad value
 * in the decoder (`decodeIgnoringValues` in engine-core); this does the same for the document's
 * own fields (`navigationBar.title: 5`, a malformed `onEvent`): the value at the failure's path
 * is removed and decoding retried, up to [MAX_DROPS] times; each drop is reported to [warn]
 * (`value.ignored`). The document still fails when its root is unusable (no `type`, not an
 * object). Unknown or mistyped values are ignored, never fatal (DSL rule, same on iOS and web).
 */
internal object DegradingDecode {
    const val MAX_DROPS: Int = 32

    fun <T> decode(element: JsonElement, warn: (DslDecodingException) -> Unit, decode: (JsonElement) -> T): T {
        var current = element
        repeat(MAX_DROPS) {
            try {
                return decode(current)
            } catch (e: PlatformExcludedException) {
                throw e
            } catch (e: DslDecodingException) {
                current = remove(current, e.path) ?: throw e
                warn(e)
            }
        }
        return decode(current)
    }

    /** [root] without the value at [path] (`content.onEvent[1].action`); null when the path is the root or not found. */
    fun remove(root: JsonElement, path: String): JsonElement? {
        val steps = parse(path) ?: return null
        if (steps.isEmpty()) return null
        return removeAt(root, steps)
    }

    private fun removeAt(e: JsonElement, steps: List<Any>): JsonElement? {
        val head = steps.first()
        val last = steps.size == 1
        return when {
            e is JsonObject && head is String -> {
                val child = e[head] ?: return null
                if (last) JsonObject(e - head) else removeAt(child, steps.drop(1))?.let { JsonObject(e + (head to it)) }
            }
            e is JsonArray && head is Int -> {
                val child = e.getOrNull(head) ?: return null
                if (last) JsonArray(e.filterIndexed { i, _ -> i != head })
                else removeAt(child, steps.drop(1))?.let { JsonArray(e.mapIndexed { i, v -> if (i == head) it else v }) }
            }
            else -> null
        }
    }

    /** `a.b[2].c` → `["a", "b", 2, "c"]`; null when malformed. */
    private fun parse(path: String): List<Any>? {
        if (path.isEmpty()) return emptyList()
        val out = mutableListOf<Any>()
        for (segment in path.split('.')) {
            val key = segment.substringBefore('[')
            if (key.isNotEmpty()) out += key
            var rest = segment.substring(key.length)
            while (rest.isNotEmpty()) {
                if (!rest.startsWith("[")) return null
                val close = rest.indexOf(']')
                if (close < 0) return null
                out += rest.substring(1, close).toIntOrNull() ?: return null
                rest = rest.substring(close + 1)
            }
            if (key.isEmpty() && segment.isEmpty()) return null
        }
        return out
    }
}
