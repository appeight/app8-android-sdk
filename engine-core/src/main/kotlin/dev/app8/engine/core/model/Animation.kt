package dev.app8.engine.core.model

/**
 * An animation reference: an inline animation or a pointer into the app-level
 * `animations` registry (iOS `DSL.Model.Animation`).
 */
public sealed interface Animation {

    public data class Inline(
        /** Set when this inline came from a wrapped `{ id, type, content }` form. */
        val id: String? = null,
        val duration: Double = 0.25,
        val delay: Double = 0.0,
        val timing: Timing = Timing.Curve(NamedCurve.easeInOut),
        val options: List<Option> = emptyList(),
    ) : Animation

    public data class Pointer(val id: String) : Animation

    public sealed interface Timing {
        public data class Curve(val curve: NamedCurve) : Timing
        public data class CubicBezier(val x1: Double, val y1: Double, val x2: Double, val y2: Double) : Timing
        public data class SpringTiming(val spring: Spring) : Timing
    }

    @Suppress("EnumEntryName")
    public enum class NamedCurve { linear, easeIn, easeOut, easeInOut }

    public data class Spring(
        val damping: Double,
        val velocity: Double,
        val mass: Double? = null,
        val stiffness: Double? = null,
    ) {
        public companion object {
            /** Used for `curve: "spring"` without parameters. */
            public val DEFAULT: Spring = Spring(damping = 0.85, velocity = 0.0)
        }
    }

    @Suppress("EnumEntryName")
    public enum class Option { beginFromCurrent, allowUserInteraction, repeat, autoreverse }

    public val inlineOrNull: Inline? get() = this as? Inline
    public val pointerId: String? get() = (this as? Pointer)?.id
    public val isPointer: Boolean get() = this is Pointer

    public fun inline(resolveBy: (String) -> Inline?): Inline? = when (this) {
        is Inline -> this
        is Pointer -> resolveBy(id)
    }

    public companion object {
        public val defaultPressFeedback: Inline = Inline(duration = 0.15, timing = Timing.Curve(NamedCurve.easeOut))
        public val legacyTransformVariableUpdate: Inline =
            Inline(duration = 0.3, timing = Timing.SpringTiming(Spring(damping = 0.75, velocity = 0.3)))
        public val legacyAlphaVariableUpdate: Inline = Inline(duration = 0.25, timing = Timing.Curve(NamedCurve.easeOut))
        public val defaultStateTransition: Inline = Inline(duration = 0.2, timing = Timing.Curve(NamedCurve.easeInOut))
    }
}

internal fun decodeAnimation(c: Cursor): Animation {
    // Probe for wrapper keys (`id`, `content`); fall back to the flat inline form.
    val id = if (c.isObject()) c.optString("id") else null
    val hasContent = c.has("content")
    if (id != null && !hasContent) {
        return c.options.animationResolver?.invoke(id) ?: Animation.Pointer(id)
    }
    if (id != null) {
        return c.req("content", ::decodeAnimationInline).copy(id = id)
    }
    return decodeAnimationInline(c)
}

internal fun decodeAnimationInline(c: Cursor): Animation.Inline {
    c.obj()
    val timing: Animation.Timing = run {
        c.opt("spring", ::decodeSpring)?.let { return@run Animation.Timing.SpringTiming(it) }
        val pts = c.opt("cubicBezier") { a -> a.list { it.double() } }
        if (pts != null && pts.size == 4) return@run Animation.Timing.CubicBezier(pts[0], pts[1], pts[2], pts[3])
        val curve = c.optString("curve") ?: return@run Animation.Timing.Curve(Animation.NamedCurve.easeInOut)
        if (curve == "spring") return@run Animation.Timing.SpringTiming(Animation.Spring.DEFAULT)
        Animation.Timing.Curve(Animation.NamedCurve.entries.firstOrNull { it.name == curve } ?: Animation.NamedCurve.easeInOut)
    }
    return Animation.Inline(
        id = null,
        duration = c.optDouble("duration") ?: 0.25,
        delay = c.optDouble("delay") ?: 0.0,
        timing = timing,
        options = c.opt("options") { a -> a.list { it.strictEnum<Animation.Option>() } } ?: emptyList(),
    )
}

private fun decodeSpring(c: Cursor): Animation.Spring {
    c.obj()
    return Animation.Spring(
        damping = c.reqDouble("damping"),
        velocity = c.reqDouble("velocity"),
        mass = c.optDouble("mass"),
        stiffness = c.optDouble("stiffness"),
    )
}

/** A value of type [T] with an optional animation: bare `T` or `{ "value": T, "animation": … }`. */
public data class AnimatedValue<T>(val value: T, val animation: Animation? = null)

internal inline fun <T> decodeAnimatedValue(c: Cursor, decodeValue: (Cursor) -> T): AnimatedValue<T> {
    if (c.isObject() && c.has("value")) {
        return AnimatedValue(c.req("value", decodeValue), c.opt("animation", ::decodeAnimation))
    }
    return AnimatedValue(decodeValue(c))
}

internal fun decodeAnimatedString(c: Cursor): AnimatedValue<String> = decodeAnimatedValue(c) { it.string() }

/** A DSL text value: a plain literal or a `{"$i18n": "key"}` translation key. */
public sealed interface LocalizedString {
    public data class Literal(val text: String) : LocalizedString
    public data class Key(val key: String) : LocalizedString

    /** The literal, or the key itself (used as the visible placeholder for a missing translation). */
    public val rawValue: String
        get() = when (this) {
            is Literal -> text
            is Key -> key
        }
}

internal fun decodeLocalizedString(c: Cursor): LocalizedString {
    c.stringOrNull()?.let { return LocalizedString.Literal(it) }
    // Object form must be `{ "$i18n": "key" }` — anything else throws so typos surface.
    return LocalizedString.Key(c.reqString("\$i18n"))
}
