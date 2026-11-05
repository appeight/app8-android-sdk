package dev.app8.engine.render.style

import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.Font
import dev.app8.engine.core.model.style.Gradient
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.Outline
import dev.app8.engine.core.model.style.Shadow
import dev.app8.engine.core.model.style.StyleAny
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StylePointer
import dev.app8.engine.core.model.style.StyleType
import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.Wrapped
import dev.app8.engine.runtime.StyleRegistry
import java.util.IdentityHashMap

/**
 * iOS `resolveStylePointers`: replaces `{ "id": … }` references in a style model with the
 * registry's entities, recursively (color → fill → material), so the style layer
 * (`dev.app8.engine.style`) only ever sees resolved values. Results are memoized per model
 * instance: the decoded models are immutable.
 */
internal class StyleResolver(private val registry: StyleRegistry) {
    private val materials = IdentityHashMap<Material, Material>()
    private val texts = IdentityHashMap<TextModel, TextModel>()

    fun material(m: Material): Material = materials.getOrPut(m) { material(m, 0) }

    fun text(t: TextModel): TextModel = texts.getOrPut(t) {
        t.copy(color = wrappedColor(t.color), font = wrapped(t.font) ?: t.font)
    }

    fun color(w: Wrapped<ThemedColor>?): ThemedColor? {
        val c = w?.value ?: w?.pointerId?.let { registry.content(it) as? ThemedColor } ?: return null
        return themed(c, 0)
    }

    fun wrappedColor(w: Wrapped<ThemedColor>): Wrapped<ThemedColor> = color(w)?.let { Wrapped.of(it) } ?: w

    private fun material(m: Material, depth: Int): Material =
        if (depth > MAX_DEPTH) m else Material(m.layers.mapNotNull { layer(it, depth) })

    private fun layer(style: StyleAny, depth: Int): StyleAny? {
        val entity: StyleEntity<*> = when (style) {
            is StyleEntity<*> -> style
            is StylePointer -> registry.entity(style.id) ?: return null
        }
        val content: Any = when (val c = entity.content) {
            is Fill -> fill(c)
            is Outline -> c.copy(fill = fill(c.fill))
            is Shadow -> c.copy(layers = c.layers.map { it.copy(color = themed(it.color, 0)) })
            is Material -> material(c, depth + 1)
            is Corner -> c
            else -> c
        }
        // A pointer without a type takes the entity's type; the style layer switches on it.
        val type = if (style is StylePointer && style.typeSpecified) style.type else entity.type
        return if (content === entity.content && type == entity.type) entity else StyleEntity(entity.id, type, content)
    }

    private fun fill(f: Fill): Fill {
        val gradient = (f.gradient.value ?: f.gradient.pointerId?.let { registry.content(it) as? Gradient })?.let { g ->
            Wrapped.of(
                g.copy(
                    start = g.start.copy(color = wrappedColor(g.start.color)),
                    end = g.end.copy(color = wrappedColor(g.end.color)),
                    middlePoints = g.middlePoints.map { it.copy(color = wrappedColor(it.color)) },
                ),
            )
        } ?: f.gradient
        return Fill(solid = wrappedColor(f.solid), gradient = gradient)
    }

    private fun themed(c: ThemedColor, depth: Int): ThemedColor {
        if (c.light != null && (c.dark != null || c.darkRef == null)) return c
        if (depth > MAX_DEPTH) return c
        fun ref(id: String?): ThemedColor? = id?.let { registry.content(it) as? ThemedColor }?.let { themed(it, depth + 1) }
        val lightRef = ref(c.lightRef)
        val darkRef = ref(c.darkRef)
        return c.copy(
            light = c.light ?: lightRef?.light ?: lightRef?.dark,
            dark = c.dark ?: darkRef?.dark ?: darkRef?.light,
        )
    }

    private inline fun <reified T : Any> wrapped(w: Wrapped<T>): Wrapped<T>? =
        w.value?.let { null } ?: w.pointerId?.let { registry.content(it) as? T }?.let { Wrapped.of(it) }

    private companion object {
        const val MAX_DEPTH = 8
        @Suppress("unused") private val keep = listOf(StyleType.Key.fill, Font.Weight.regular)
    }
}
