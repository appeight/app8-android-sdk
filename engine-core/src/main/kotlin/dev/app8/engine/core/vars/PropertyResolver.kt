package dev.app8.engine.core.vars

import dev.app8.engine.core.expr.ExpressionEvaluator
import dev.app8.engine.core.expr.ExpressionParser
import dev.app8.engine.core.expr.SwiftCompat
import java.util.Locale

/**
 * Source of translations and the active locale (the subset of iOS `TranslationStore` that
 * [PropertyResolver] uses). [lookup] returns `null` on a miss.
 */
public interface TranslationProvider {
    public val activeLocale: Locale
    public fun lookup(key: String): String?
}

/**
 * Minimal stand-in for the DSL `LocalizedString` (`"text"` or `{"$i18n": "key"}`) so this layer
 * does not depend on the DSL model package.
 */
public sealed interface LocalizedText {
    public data class Literal(val value: String) : LocalizedText
    public data class Key(val key: String) : LocalizedText
}

/**
 * Resolves `{{expression}}` placeholders and localized-text lookups (Swift `PropertyResolver`).
 * With a [translationProvider], its active locale also drives the evaluator's number/date formats.
 * Not thread-safe; confine to the main thread.
 */
public class PropertyResolver(private val translationProvider: TranslationProvider? = null) {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator()

    /** True if [string] contains both `{{` and `}}`. */
    public fun containsExpression(string: String): Boolean = string.contains("{{") && string.contains("}}")

    /**
     * Resolves [value]. A single whole-string expression (`"{{age}}"`, surrounding spaces allowed)
     * returns the raw result — [Values.Null] when it evaluates to nothing; otherwise each
     * placeholder is interpolated as text, and placeholders that fail are kept verbatim.
     * @throws Exception from parsing/evaluating a whole-string expression.
     */
    public fun resolve(value: String, context: VariableContext): Any {
        syncEvaluatorLocale()
        val trimmed = SwiftCompat.trimWhitespaces(value)
        if (trimmed.length >= 4 && trimmed.startsWith("{{") && trimmed.endsWith("}}")) {
            val inner = trimmed.substring(2, trimmed.length - 2)
            if (!inner.contains("{{") && !inner.contains("}}")) {
                val node = parser.parse(value)
                return evaluator.evaluate(node, context) ?: Values.Null
            }
        }
        return resolveInterpolated(value, context)
    }

    /** Resolves and always returns a string (for text properties). */
    public fun resolveToString(value: String, context: VariableContext): String =
        Values.stringify(resolve(value, context))

    /** i18n lookup (for [LocalizedText.Key]) followed by `{{var}}` interpolation; a miss resolves to the key. */
    public fun resolveLocalizedToString(value: LocalizedText, context: VariableContext): String = when (value) {
        is LocalizedText.Literal -> resolveToString(value.value, context)
        is LocalizedText.Key -> resolveToString(translationProvider?.lookup(value.key) ?: value.key, context)
    }

    /** Resolves strings containing expressions, recursing into lists and maps. */
    public fun resolveAny(value: Any, context: VariableContext): Any {
        if (value is String) return if (containsExpression(value)) resolve(value, context) else value
        Values.asList(value)?.let { list -> return list.map { if (it == null) null else resolveAny(it, context) } }
        Values.asMap(value)?.let { map ->
            return map.mapValuesTo(LinkedHashMap()) { (_, v) -> if (v == null) null else resolveAny(v, context) }
        }
        return value
    }

    private fun syncEvaluatorLocale() {
        val provider = translationProvider ?: return
        evaluator.locale = provider.activeLocale
        evaluator.translationLookup = { key -> provider.lookup(key) }
    }

    private fun resolveInterpolated(value: String, context: VariableContext): String {
        val result = StringBuilder()
        var remaining = value
        while (true) {
            val start = remaining.indexOf("{{")
            if (start < 0) break
            result.append(remaining, 0, start)
            val afterStart = remaining.substring(start + 2)
            val end = afterStart.indexOf("}}")
            if (end < 0) {
                // No closing }} — keep the rest literally.
                result.append(remaining, start, remaining.length)
                remaining = ""
                break
            }
            val fullExpression = "{{" + afterStart.substring(0, end) + "}}"
            try {
                val node = parser.parse(fullExpression)
                result.append(Values.stringify(evaluator.evaluate(node, context)))
            } catch (_: Exception) {
                result.append(fullExpression)
            }
            remaining = afterStart.substring(end + 2)
        }
        result.append(remaining)
        return result.toString()
    }
}
