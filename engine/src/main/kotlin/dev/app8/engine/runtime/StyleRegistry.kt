package dev.app8.engine.runtime

import androidx.compose.runtime.Immutable
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.StyleAny
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StylePointer
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.Wrapped

/**
 * The app's styles (styles.json) by id. iOS rewrites pointers in the decoded tree; the Android
 * models are immutable, so pointers are followed at use time through [content] / [resolve].
 */
@Immutable
internal class StyleRegistry(private val styles: Map<String, StyleAny>) {

    val size: Int get() = styles.size

    /** The entity content behind [id], following pointer chains. */
    fun content(id: String): Any? {
        var current: StyleAny? = styles[id]
        var hops = 0
        while (current is StylePointer && hops++ < MAX_HOPS) current = styles[current.id]
        return (current as? StyleEntity<*>)?.content
    }

    /** The entity behind [id], following pointer chains. */
    fun entity(id: String): StyleEntity<*>? {
        var current: StyleAny? = styles[id]
        var hops = 0
        while (current is StylePointer && hops++ < MAX_HOPS) current = styles[current.id]
        return current as? StyleEntity<*>
    }

    /** The entity content of [style] (a pointer is looked up). */
    fun content(style: StyleAny?): Any? = when (style) {
        null -> null
        is StyleEntity<*> -> style.content
        is StylePointer -> content(style.id)
    }

    inline fun <reified T : Any> resolve(wrapped: Wrapped<T>?): T? {
        if (wrapped == null) return null
        wrapped.value?.let { return it }
        val id = wrapped.pointerId ?: (wrapped.base as? StyleEntity<*>)?.id ?: return null
        return content(id) as? T
    }

    /** A themed color with `{ id }` light/dark references resolved. */
    fun color(themed: ThemedColor?, dark: Boolean): ColorHex? {
        if (themed == null) return null
        val light = themed.light ?: themed.lightRef?.let { (content(it) as? ThemedColor)?.let { c -> color(c, false) } }
        val darkHex = themed.dark ?: themed.darkRef?.let { (content(it) as? ThemedColor)?.let { c -> color(c, true) } }
        return (if (dark) darkHex ?: light else light ?: darkHex)
    }

    fun color(wrapped: Wrapped<ThemedColor>?, dark: Boolean): ColorHex? = color(resolve(wrapped), dark)

    /** A registry with [updates] replacing the styles that share their id. */
    fun merging(updates: List<StyleAny>): StyleRegistry =
        StyleRegistry(LinkedHashMap(styles).apply { for (s in updates) put(s.id, s) })

    companion object {
        private const val MAX_HOPS = 16
        val EMPTY = StyleRegistry(emptyMap())
    }
}
