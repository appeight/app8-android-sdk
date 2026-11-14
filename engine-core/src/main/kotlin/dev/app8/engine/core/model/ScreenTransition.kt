package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Shadow
import dev.app8.engine.core.model.style.decodeColorHex
import dev.app8.engine.core.model.style.decodeCorner
import dev.app8.engine.core.model.style.decodeCornerRadius
import dev.app8.engine.core.model.style.decodeShadow

/**
 * Screen-to-screen transition: inline or a pointer into the app-level `transitions` registry
 * (iOS `DSL.Model.ScreenTransition`). A bare string is a preset shorthand.
 */
public sealed interface ScreenTransition {

    public data class Inline(
        val id: String? = null,
        val mode: Mode? = null,
        val preset: Preset? = null,
        val edge: Edge? = null,
        val animation: Animation? = null,
        val from: Participant? = null,
        val to: Participant? = null,
        val reverse: Reverse = Reverse.Auto,
        val raise: Raise? = null,
        val dimming: Dimming? = null,
        val interactive: InteractiveConfig? = null,
        val presentation: ModalPresentation? = null,
        /** Native zoom: `content.transition.key` of the source element on the presenting screen (expression). */
        val sourceKey: String? = null,
        /** Native zoom: element on the incoming screen the source lines up with (expression). */
        val alignmentKey: String? = null,
        /** Native zoom: interactive dismissal (default `any`). */
        val dismissGesture: DismissGesture? = null,
    ) : ScreenTransition {
        /** `zoom` with a `sourceKey`: the native zoom from a source element. */
        public val isNativeZoom: Boolean get() = preset == Preset.zoom && !sourceKey.isNullOrEmpty()

        /** Replaces a pointer `animation` with its resolved inline form. */
        public fun normalizeAnimation(resolveBy: (String) -> Animation.Inline?): Inline {
            val a = animation ?: return this
            if (!a.isPointer) return this
            val inline = a.inline(resolveBy) ?: return this
            return copy(animation = inline)
        }
    }

    public data class Pointer(val id: String) : ScreenTransition

    public val inlineOrNull: Inline? get() = this as? Inline
    public val pointerId: String? get() = (this as? Pointer)?.id
    public val isPointer: Boolean get() = this is Pointer

    public fun inline(resolveBy: (String) -> Inline?): Inline? = when (this) {
        is Inline -> this
        is Pointer -> resolveBy(id)
    }

    @Suppress("EnumEntryName")
    public enum class Mode { push, modal }

    @Suppress("EnumEntryName")
    public enum class DismissGesture { any, down, none }

    @Suppress("EnumEntryName")
    public enum class Preset {
        slide, fade, crossDissolve, scale, zoom, cover, popup, sheet, shared, none, system;

        public val defaultMode: Mode
            get() = when (this) {
                scale, zoom, cover, popup, sheet -> Mode.modal
                else -> Mode.push
            }
    }

    @Suppress("EnumEntryName")
    public enum class Edge { leading, trailing, top, bottom }

    @Suppress("EnumEntryName")
    public enum class Raise { incoming, outgoing }

    public data class Participant(val begin: TransitionState = TransitionState.IDENTITY, val end: TransitionState = TransitionState.IDENTITY)

    public data class TransitionState(
        val opacity: Double? = null,
        val translate: Translation = Translation.ZERO,
        val scale: Scale = Scale.IDENTITY,
        val rotate: Double = 0.0,
        val anchor: Anchor = Anchor.CENTER,
    ) {
        public companion object {
            public val IDENTITY: TransitionState = TransitionState()
        }
    }

    public data class Translation(val x: Length = Length.Points(0.0), val y: Length = Length.Points(0.0)) {
        public companion object {
            public val ZERO: Translation = Translation()
        }
    }

    public sealed interface Length {
        public data class Points(val value: Double) : Length
        public data class Fraction(val value: Double) : Length

        public fun resolved(against: Double): Double = when (this) {
            is Points -> value
            is Fraction -> value * against
        }
    }

    public data class Scale(val x: Double = 1.0, val y: Double = 1.0) {
        public companion object {
            public val IDENTITY: Scale = Scale()
        }
    }

    public data class Anchor(val x: Double = 0.5, val y: Double = 0.5) {
        public companion object {
            public val CENTER: Anchor = Anchor()
        }
    }

    public sealed interface Reverse {
        public data object Auto : Reverse
        public data class Explicit(val from: Participant?, val to: Participant?) : Reverse
    }

    public data class Dimming(val color: ColorHex? = null, val opacity: Double = 0.4, val dismissOnTap: Boolean = true)

    public data class InteractiveConfig(
        val enabled: Boolean = true,
        val edge: Edge? = null,
        val threshold: Double = 0.3,
        val velocity: Double = 800.0,
    )

    /** Per-component participation in a `shared` transition, matched across screens by [key]. */
    public data class ElementTransition(
        val key: String,
        val morph: Morph = Morph.frameFade,
        val fallback: Fallback = Fallback.fade,
        val stagger: Double? = null,
        val animation: Animation? = null,
    )

    @Suppress("EnumEntryName")
    public enum class Morph { frame, fade, frameFade }

    @Suppress("EnumEntryName")
    public enum class Fallback { fade, slideTop, slideBottom, none }

    /** Sized-modal geometry and chrome. */
    public data class ModalPresentation(
        val width: ModalDimension? = null,
        val height: ModalDimension? = null,
        val align: ModalAlign? = null,
        val margin: ModalInsets? = null,
        val corner: Corner? = null,
        val shadow: Shadow? = null,
        val ignoresSafeArea: Boolean? = null,
        val avoidsKeyboard: Boolean? = null,
    ) {
        public fun merging(override: ModalPresentation?): ModalPresentation {
            if (override == null) return this
            return ModalPresentation(
                width = override.width ?: width,
                height = override.height ?: height,
                align = override.align ?: align,
                margin = override.margin ?: margin,
                corner = override.corner ?: corner,
                shadow = override.shadow ?: shadow,
                ignoresSafeArea = override.ignoresSafeArea ?: ignoresSafeArea,
                avoidsKeyboard = override.avoidsKeyboard ?: avoidsKeyboard,
            )
        }
    }

    public sealed interface ModalDimension {
        public data class Points(val value: Double) : ModalDimension
        public data class Fraction(val value: Double) : ModalDimension
        public data class Ratio(val value: Double) : ModalDimension
        public data object Fill : ModalDimension
    }

    @Suppress("EnumEntryName")
    public enum class ModalAlign { center, top, bottom, leading, trailing }

    public data class ModalInsets(val top: Double = 0.0, val bottom: Double = 0.0, val leading: Double = 0.0, val trailing: Double = 0.0) {
        public companion object {
            public val ZERO: ModalInsets = ModalInsets()
            public fun uniform(value: Double): ModalInsets = ModalInsets(value, value, value, value)
        }
    }
}

/**
 * A component's `content.transition`: on a screen root it is the screen's default [ScreenTransition];
 * on a child it is an [ScreenTransition.ElementTransition] (an object with `"key"`).
 */
public sealed interface ComponentTransition {
    public data class Screen(val transition: ScreenTransition) : ComponentTransition
    public data class Element(val element: ScreenTransition.ElementTransition) : ComponentTransition

    public val screenOrNull: ScreenTransition? get() = (this as? Screen)?.transition
    public val elementOrNull: ScreenTransition.ElementTransition? get() = (this as? Element)?.element
}

internal fun decodeComponentTransition(c: Cursor): ComponentTransition {
    if (c.hasNonNull("key")) return ComponentTransition.Element(decodeElementTransition(c))
    return ComponentTransition.Screen(decodeScreenTransition(c))
}

internal fun decodeScreenTransition(c: Cursor): ScreenTransition {
    c.stringOrNull()?.let { s ->
        return ScreenTransition.Inline(preset = ScreenTransition.Preset.entries.firstOrNull { it.name == s } ?: ScreenTransition.Preset.system)
    }
    val id = if (c.isObject()) c.optString("id") else null
    val hasContent = c.hasNonNull("content")
    if (id != null && !hasContent) {
        return c.options.transitionResolver?.invoke(id) ?: ScreenTransition.Pointer(id)
    }
    if (id != null) return c.req("content", ::decodeScreenTransitionInline).copy(id = id)
    return decodeScreenTransitionInline(c)
}

internal fun decodeScreenTransitionInline(c: Cursor): ScreenTransition.Inline {
    c.obj()
    return ScreenTransition.Inline(
        id = null,
        mode = c.optSafeEnum("mode", ScreenTransition.Mode.push),
        preset = c.optSafeEnum("preset", ScreenTransition.Preset.system),
        edge = c.optSafeEnum("edge", ScreenTransition.Edge.trailing),
        animation = c.opt("animation", ::decodeAnimation),
        from = c.opt("from", ::decodeParticipant),
        to = c.opt("to", ::decodeParticipant),
        reverse = c.opt("reverse", ::decodeReverse) ?: ScreenTransition.Reverse.Auto,
        raise = c.optSafeEnum("raise", ScreenTransition.Raise.incoming),
        dimming = c.opt("dimming", ::decodeDimming),
        interactive = c.opt("interactive", ::decodeInteractive),
        presentation = c.opt("presentation", ::decodeModalPresentation),
        sourceKey = c.optString("sourceKey"),
        alignmentKey = c.optString("alignmentKey"),
        dismissGesture = c.optSafeEnum("dismissGesture", ScreenTransition.DismissGesture.any),
    )
}

private fun decodeParticipant(c: Cursor): ScreenTransition.Participant {
    c.obj()
    return ScreenTransition.Participant(
        begin = c.opt("begin", ::decodeTransitionState) ?: ScreenTransition.TransitionState.IDENTITY,
        end = c.opt("end", ::decodeTransitionState) ?: ScreenTransition.TransitionState.IDENTITY,
    )
}

private fun decodeTransitionState(c: Cursor): ScreenTransition.TransitionState {
    c.obj()
    return ScreenTransition.TransitionState(
        opacity = c.optDouble("opacity"),
        translate = c.opt("translate", ::decodeTranslation) ?: ScreenTransition.Translation.ZERO,
        scale = c.opt("scale", ::decodeScale) ?: ScreenTransition.Scale.IDENTITY,
        rotate = c.optDouble("rotate") ?: 0.0,
        anchor = c.opt("anchor", ::decodeAnchor) ?: ScreenTransition.Anchor.CENTER,
    )
}

private fun decodeTranslation(c: Cursor): ScreenTransition.Translation {
    c.obj()
    return ScreenTransition.Translation(
        x = c.opt("x", ::decodeLength) ?: ScreenTransition.Length.Points(0.0),
        y = c.opt("y", ::decodeLength) ?: ScreenTransition.Length.Points(0.0),
    )
}

internal fun decodeLength(c: Cursor): ScreenTransition.Length {
    c.doubleOrNull()?.let { return ScreenTransition.Length.Points(it) }
    val s = c.string()
    val trimmed = s.trim(' ')
    if (trimmed.endsWith("%")) trimmed.dropLast(1).toDoubleOrNull()?.let { return ScreenTransition.Length.Fraction(it / 100) }
    trimmed.toDoubleOrNull()?.let { return ScreenTransition.Length.Points(it) }
    c.fail("invalid transition length: $s")
}

private fun decodeScale(c: Cursor): ScreenTransition.Scale {
    c.doubleOrNull()?.let { return ScreenTransition.Scale(it, it) }
    c.obj()
    return ScreenTransition.Scale(c.optDouble("x") ?: 1.0, c.optDouble("y") ?: 1.0)
}

private fun decodeAnchor(c: Cursor): ScreenTransition.Anchor {
    c.obj()
    return ScreenTransition.Anchor(c.optDouble("x") ?: 0.5, c.optDouble("y") ?: 0.5)
}

private fun decodeReverse(c: Cursor): ScreenTransition.Reverse {
    // Any string means "auto".
    if (c.stringOrNull() != null) return ScreenTransition.Reverse.Auto
    c.obj()
    return ScreenTransition.Reverse.Explicit(c.opt("from", ::decodeParticipant), c.opt("to", ::decodeParticipant))
}

private fun decodeDimming(c: Cursor): ScreenTransition.Dimming {
    c.obj()
    return ScreenTransition.Dimming(
        color = c.opt("color", ::decodeColorHex),
        opacity = c.optDouble("opacity") ?: 0.4,
        dismissOnTap = c.optBool("dismissOnTap") ?: true,
    )
}

private fun decodeInteractive(c: Cursor): ScreenTransition.InteractiveConfig {
    c.obj()
    return ScreenTransition.InteractiveConfig(
        enabled = c.optBool("enabled") ?: true,
        edge = c.optSafeEnum("edge", ScreenTransition.Edge.trailing),
        threshold = c.optDouble("threshold") ?: 0.3,
        velocity = c.optDouble("velocity") ?: 800.0,
    )
}

internal fun decodeElementTransition(c: Cursor): ScreenTransition.ElementTransition {
    c.obj()
    return ScreenTransition.ElementTransition(
        key = c.reqString("key"),
        morph = c.optSafeEnum("morph", ScreenTransition.Morph.frameFade) ?: ScreenTransition.Morph.frameFade,
        fallback = c.optSafeEnum("fallback", ScreenTransition.Fallback.fade) ?: ScreenTransition.Fallback.fade,
        stagger = c.optDouble("stagger"),
        animation = c.opt("animation", ::decodeAnimation),
    )
}

internal fun decodeModalPresentation(c: Cursor): ScreenTransition.ModalPresentation {
    c.obj()
    return ScreenTransition.ModalPresentation(
        width = c.opt("width", ::decodeModalDimension),
        height = c.opt("height", ::decodeModalDimension),
        align = c.optSafeEnum("align", ScreenTransition.ModalAlign.center),
        margin = c.opt("margin", ::decodeModalInsets),
        corner = decodeModalCorner(c),
        shadow = c.opt("shadow", ::decodeShadow),
        ignoresSafeArea = c.optBool("ignoresSafeArea"),
        avoidsKeyboard = c.optBool("avoidsKeyboard"),
    )
}

/** Full `{ radius, curve }` corner, or a bare radius (`28` / `"50%"` / `{ type, value }`) with a continuous curve. */
private fun decodeModalCorner(c: Cursor): Corner? {
    val corner = c.child("corner") ?: return null
    if (corner.isNull) return null
    corner.attempt(::decodeCorner)?.let { return it }
    corner.attempt(::decodeCornerRadius)?.let { return Corner(it, Corner.Curve.continuous) }
    val radius = corner.req("radius", ::decodeCornerRadius)
    val curve = corner.optSafeEnum("curve", Corner.Curve.circular) ?: Corner.Curve.continuous
    return Corner(radius, curve)
}

internal fun decodeModalDimension(c: Cursor): ScreenTransition.ModalDimension {
    c.doubleOrNull()?.let { return ScreenTransition.ModalDimension.Points(it) }
    c.stringOrNull()?.let { s ->
        val trimmed = s.trim(' ').lowercase()
        if (trimmed == "fill") return ScreenTransition.ModalDimension.Fill
        if (trimmed.endsWith("%")) trimmed.dropLast(1).toDoubleOrNull()?.let { return ScreenTransition.ModalDimension.Fraction(it / 100) }
        trimmed.toDoubleOrNull()?.let { return ScreenTransition.ModalDimension.Points(it) }
    }
    c.obj()
    c.optDouble("ratio")?.let { return ScreenTransition.ModalDimension.Ratio(it) }
    c.optDouble("points")?.let { return ScreenTransition.ModalDimension.Points(it) }
    c.optDouble("fraction")?.let { return ScreenTransition.ModalDimension.Fraction(it) }
    c.fail("invalid modal dimension")
}

private fun decodeModalInsets(c: Cursor): ScreenTransition.ModalInsets {
    c.doubleOrNull()?.let { return ScreenTransition.ModalInsets.uniform(it) }
    c.obj()
    return ScreenTransition.ModalInsets(
        top = c.optDouble("top") ?: 0.0,
        bottom = c.optDouble("bottom") ?: 0.0,
        leading = c.optDouble("leading") ?: 0.0,
        trailing = c.optDouble("trailing") ?: 0.0,
    )
}
