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

/** Evaluates a `{{…}}` color in a component's scope: a color, or null to leave the field unset. */
internal fun interface ColorBinder {
    fun bind(expression: String): ThemedColor?
}

/**
 * iOS `resolveStylePointers`: replaces `{ "id": … }` references in a style model with the
 * registry's entities, recursively (color → fill → material), so the style layer
 * (`dev.app8.engine.style`) only ever sees resolved values. Results are memoized per model
 * instance: the decoded models are immutable.
 */
internal class StyleResolver(private val registry: StyleRegistry) {
    private val materials = IdentityHashMap<Material, Material>()
    private val texts = IdentityHashMap<TextModel, TextModel>()

    private val bindable = IdentityHashMap<Material, Boolean>()

    fun material(m: Material): Material = materials.getOrPut(m) { material(m, 0) }

    /** [material] with its `{{…}}` colors bound by [binder] (not memoized: they change). */
    fun material(m: Material, binder: ColorBinder?): Material {
        val resolved = material(m)
        if (binder == null || !bindable.getOrPut(resolved) { hasExpression(resolved, 0) }) return resolved
        return bind(resolved, binder, 0)
    }

    fun text(t: TextModel): TextModel = texts.getOrPut(t) {
        t.copy(color = wrappedColor(t.color), font = wrapped(t.font) ?: t.font)
    }

    fun text(t: TextModel, binder: ColorBinder?): TextModel {
        val resolved = text(t)
        if (binder == null || resolved.color.value?.expression == null) return resolved
        return resolved.copy(color = bind(resolved.color, binder))
    }

    fun color(w: Wrapped<ThemedColor>?): ThemedColor? {
        val c = w?.value ?: w?.pointerId?.let { registry.content(it) as? ThemedColor } ?: return null
        return themed(c, 0)
    }

    fun color(w: Wrapped<ThemedColor>?, binder: ColorBinder?): ThemedColor? {
        val c = color(w) ?: return null
        val expression = c.expression ?: return c
        return binder?.bind(expression)
    }

    // region `{{…}}` colors

    private fun bind(w: Wrapped<ThemedColor>, binder: ColorBinder): Wrapped<ThemedColor> {
        val expression = w.value?.expression ?: return w
        return binder.bind(expression)?.let { Wrapped.of(it) } ?: Wrapped.none()
    }

    private fun bind(f: Fill, binder: ColorBinder): Fill = Fill(
        solid = bind(f.solid, binder),
        gradient = f.gradient.value?.let { g ->
            Wrapped.of(
                g.copy(
                    start = g.start.copy(color = bind(g.start.color, binder)),
                    end = g.end.copy(color = bind(g.end.color, binder)),
                    middlePoints = g.middlePoints.map { it.copy(color = bind(it.color, binder)) },
                ),
            )
        } ?: f.gradient,
    )

    private fun bind(m: Material, binder: ColorBinder, depth: Int): Material {
        if (depth > MAX_DEPTH) return m
        return Material(
            m.layers.map { layer ->
                val entity = layer as? StyleEntity<*> ?: return@map layer
                val content: Any = when (val c = entity.content) {
                    is Fill -> bind(c, binder)
                    is Outline -> c.copy(fill = bind(c.fill, binder))
                    is Shadow -> c.copy(layers = c.layers.map { l -> l.color.expression?.let { e -> l.copy(color = binder.bind(e) ?: CLEAR) } ?: l })
                    is Material -> bind(c, binder, depth + 1)
                    else -> c
                }
                if (content === entity.content) entity else StyleEntity(entity.id, entity.type, content)
            },
        )
    }

    private fun hasExpression(f: Fill): Boolean =
        f.solid.value?.expression != null || f.gradient.value?.let { g ->
            g.start.color.value?.expression != null || g.end.color.value?.expression != null ||
                g.middlePoints.any { it.color.value?.expression != null }
        } == true

    private fun hasExpression(m: Material, depth: Int): Boolean = depth <= MAX_DEPTH && m.layers.any { layer ->
        when (val c = (layer as? StyleEntity<*>)?.content) {
            is Fill -> hasExpression(c)
            is Outline -> hasExpression(c.fill)
            is Shadow -> c.layers.any { it.color.expression != null }
            is Material -> hasExpression(c, depth + 1)
            else -> false
        }
    }

    // endregion

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
        val CLEAR = ThemedColor(ThemedColor.Source.SINGLE_HEX, light = dev.app8.engine.core.model.style.ColorHex.CLEAR)
        @Suppress("unused") private val keep = listOf(StyleType.Key.fill, Font.Weight.regular)
    }
}
