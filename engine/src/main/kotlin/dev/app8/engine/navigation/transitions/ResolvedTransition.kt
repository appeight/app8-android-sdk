package dev.app8.engine.navigation.transitions

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import dev.app8.engine.core.model.Animation
import dev.app8.engine.core.model.App
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.model.ScreenTransition.Edge
import dev.app8.engine.core.model.ScreenTransition.Length
import dev.app8.engine.core.model.ScreenTransition.ModalAlign
import dev.app8.engine.core.model.ScreenTransition.ModalDimension
import dev.app8.engine.core.model.ScreenTransition.ModalInsets
import dev.app8.engine.core.model.ScreenTransition.ModalPresentation
import dev.app8.engine.core.model.ScreenTransition.Participant
import dev.app8.engine.core.model.ScreenTransition.Preset
import dev.app8.engine.core.model.ScreenTransition.TransitionState
import dev.app8.engine.core.model.ScreenTransition.Translation
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.Corner
import kotlin.math.roundToInt


/**
 * A screen transition ready to run (iOS `ScreenTransition.Resolved`, TransitionPresets.swift):
 * presets expanded into begin/end keyframes, explicit `from`/`to`/`animation` overlaid, and the
 * sized-modal presentation, dimming and interactive defaults filled in.
 */
@Immutable
internal data class ResolvedTransition(
    val kind: Kind,
    val mode: ScreenTransition.Mode,
    val edge: Edge,
    val raise: ScreenTransition.Raise,
    val animation: Animation.Inline,
    val from: Participant,
    val to: Participant,
    val reverseFrom: Participant?,
    val reverseTo: Participant?,
    val dimming: Dimming?,
    val interactive: ScreenTransition.InteractiveConfig?,
    val presentation: SizedPresentation?,
) {
    enum class Kind {
        /** UIKit's native transition: on Android the platform default (NavDisplay / sheets). */
        System,

        /** Instantaneous. */
        None,

        /** Screen keyframes. */
        Custom,

        /** Matched elements morph between the screens; the keyframes animate the backdrop. */
        Shared,
    }

    data class Dimming(val color: Color, val opacity: Float, val dismissOnTap: Boolean)

    /** Whether the engine animates this transition (rather than the platform). */
    val isAnimated: Boolean get() = kind == Kind.Custom || kind == Kind.Shared

    /**
     * Keyframes of the screen that is not on top in the forward direction ([outgoing]) or is
     * ([incoming]) for [pop]. Auto reverse plays the forward keyframes backwards: progress
     * runs 1 → 0, so the same pair is returned and the caller reverses the fraction.
     */
    fun participant(incoming: Boolean, pop: Boolean): Participant =
        if (pop) (if (incoming) reverseTo ?: to else reverseFrom ?: from) else if (incoming) to else from

    /** Whether [pop] uses explicit reverse keyframes (played forward) rather than auto reverse. */
    fun explicitReverse(pop: Boolean): Boolean = pop && (reverseFrom != null || reverseTo != null)

    companion object {
        /**
         * Resolves [transition] (an inline, a registry pointer or a bare preset); null for an
         * unknown pointer (the platform transition is used).
         */
        fun resolve(transition: ScreenTransition?, app: App?): ResolvedTransition? {
            val inline = transition?.inline { id -> registry(app, id) } ?: return null
            return resolve(inline, app)
        }

        private fun registry(app: App?, id: String): ScreenTransition.Inline? =
            app?.transitions?.firstNotNullOfOrNull { (it as? ScreenTransition.Inline)?.takeIf { t -> t.id == id } }

        fun resolve(inline: ScreenTransition.Inline, app: App?): ResolvedTransition {
            val preset = inline.preset
            val mode = inline.mode ?: preset?.defaultMode ?: ScreenTransition.Mode.push
            val edge = inline.edge ?: preset?.defaultEdge ?: Edge.trailing
            val raise = inline.raise ?: ScreenTransition.Raise.incoming
            if (preset == Preset.system || preset == Preset.none) {
                return ResolvedTransition(
                    if (preset == Preset.system) Kind.System else Kind.None, mode, edge, raise, INSTANT,
                    IDENTITY_PAIR, IDENTITY_PAIR, null, null, null, null, null,
                )
            }
            val seed = preset?.let { keyframes(it, edge) }
            val from = inline.from ?: seed?.first ?: IDENTITY_PAIR
            val to = inline.to ?: seed?.second ?: IDENTITY_PAIR
            val animation = inline.animation?.inline { id -> app?.animations?.firstNotNullOfOrNull { (it as? Animation.Inline)?.takeIf { a -> a.id == id } } }
                ?: preset?.let(::defaultAnimation) ?: TRANSITION_DEFAULT
            val reverse = inline.reverse as? ScreenTransition.Reverse.Explicit
            val dimmingModel = inline.dimming ?: preset?.let(::defaultDimming)
            val dimming = dimmingModel?.let {
                Dimming(Color((it.color ?: ColorHex.parseOrFallback("#000000")).argb), it.opacity.toFloat(), it.dismissOnTap)
            }
            val interactive = inline.interactive ?: preset?.let(::defaultInteractive)
            val base = preset?.let(::defaultPresentation)
            val model = if (base != null) base.merging(inline.presentation) else inline.presentation
            return ResolvedTransition(
                if (preset == Preset.shared) Kind.Shared else Kind.Custom, mode, edge, raise, animation,
                from, to, reverse?.from, reverse?.to, dimming, interactive, model?.let(::sized),
            )
        }

        private val IDENTITY_PAIR = Participant()
        val INSTANT = Animation.Inline(duration = 0.0, timing = Animation.Timing.Curve(Animation.NamedCurve.linear))
        private val TRANSITION_DEFAULT = Animation.Inline(duration = 0.35, timing = Animation.Timing.Curve(Animation.NamedCurve.easeInOut))

        private val Preset.defaultEdge: Edge get() = if (this == Preset.cover || this == Preset.sheet) Edge.bottom else Edge.trailing

        private fun state(opacity: Double? = null, translate: Translation = Translation.ZERO, scale: Double = 1.0) =
            TransitionState(opacity = opacity, translate = translate, scale = ScreenTransition.Scale(scale, scale))

        private fun offscreen(edge: Edge, fraction: Double): Translation = when (edge) {
            Edge.trailing -> Translation(Length.Fraction(fraction), Length.Points(0.0))
            Edge.leading -> Translation(Length.Fraction(-fraction), Length.Points(0.0))
            Edge.bottom -> Translation(Length.Points(0.0), Length.Fraction(fraction))
            Edge.top -> Translation(Length.Points(0.0), Length.Fraction(-fraction))
        }

        private fun Edge.opposite(): Edge = when (this) {
            Edge.leading -> Edge.trailing
            Edge.trailing -> Edge.leading
            Edge.top -> Edge.bottom
            Edge.bottom -> Edge.top
        }

        /** (from, to) seed keyframes per preset. */
        private fun keyframes(preset: Preset, edge: Edge): Pair<Participant, Participant> {
            val identity = TransitionState.IDENTITY
            return when (preset) {
                Preset.slide -> Participant(identity, state(translate = offscreen(edge.opposite(), 0.3))) to
                    Participant(state(translate = offscreen(edge, 1.0)), identity)
                Preset.fade, Preset.crossDissolve, Preset.shared -> Participant() to Participant(state(opacity = 0.0), state(opacity = 1.0))
                Preset.scale, Preset.zoom, Preset.popup -> Participant() to Participant(state(opacity = 0.0, scale = 0.92), identity)
                Preset.cover, Preset.sheet -> Participant() to Participant(state(translate = offscreen(edge, 1.0)), identity)
                Preset.none, Preset.system -> Participant() to Participant()
            }
        }

        private fun spring(damping: Double, duration: Double) =
            Animation.Inline(duration = duration, timing = Animation.Timing.SpringTiming(Animation.Spring(damping, 0.0)))

        private fun defaultAnimation(preset: Preset): Animation.Inline = when (preset) {
            Preset.slide -> Animation.Inline(duration = 0.35)
            Preset.fade, Preset.crossDissolve -> Animation.Inline(duration = 0.3)
            Preset.scale, Preset.zoom, Preset.popup -> spring(0.82, 0.5)
            Preset.cover, Preset.sheet -> spring(0.9, 0.5)
            Preset.shared -> spring(0.85, 0.55)
            Preset.none, Preset.system -> INSTANT
        }

        private fun defaultDimming(preset: Preset): ScreenTransition.Dimming? =
            if (preset == Preset.popup || preset == Preset.sheet) ScreenTransition.Dimming(opacity = 0.45) else null

        private fun defaultInteractive(preset: Preset): ScreenTransition.InteractiveConfig? =
            if (preset == Preset.sheet) ScreenTransition.InteractiveConfig(true, Edge.bottom, 0.3, 800.0) else null

        private fun defaultPresentation(preset: Preset): ModalPresentation? = when (preset) {
            Preset.popup -> ModalPresentation(
                width = ModalDimension.Fraction(0.86), height = ModalDimension.Ratio(0.62), align = ModalAlign.center,
                margin = ModalInsets.ZERO, corner = Corner(Corner.Radius.Fixed(28.0), Corner.Curve.continuous),
                shadow = null, ignoresSafeArea = false, avoidsKeyboard = true,
            )
            // No shadow: a bottom sheet hugs the screen edge (iOS).
            Preset.sheet -> ModalPresentation(
                width = ModalDimension.Fill, height = ModalDimension.Fraction(0.5), align = ModalAlign.bottom,
                margin = ModalInsets.ZERO, corner = Corner(Corner.Radius.Fixed(24.0), Corner.Curve.continuous),
                shadow = null, ignoresSafeArea = true, avoidsKeyboard = true,
            )
            else -> null
        }

        private fun sized(m: ModalPresentation) = SizedPresentation(
            width = m.width ?: ModalDimension.Fraction(0.86),
            height = m.height ?: ModalDimension.Ratio(0.62),
            align = m.align ?: ModalAlign.center,
            margin = m.margin ?: ModalInsets.ZERO,
            corner = m.corner,
            shadow = m.shadow,
            ignoresSafeArea = m.ignoresSafeArea ?: false,
            avoidsKeyboard = m.avoidsKeyboard ?: true,
            // The popup preset's soft shadow (iOS defaultModalShadow) when none is given.
            defaultShadow = m.shadow == null && m.align != ModalAlign.bottom && m.align != ModalAlign.top,
        )
    }
}

/** Sized-modal container geometry (iOS `ResolvedModalPresentation`). */
@Immutable
internal data class SizedPresentation(
    val width: ModalDimension,
    val height: ModalDimension,
    val align: ModalAlign,
    val margin: ModalInsets,
    val corner: Corner?,
    val shadow: dev.app8.engine.core.model.style.Shadow?,
    val ignoresSafeArea: Boolean,
    val avoidsKeyboard: Boolean,
    val defaultShadow: Boolean,
) {
    /** The container frame (points) in [w]×[h] bounds with the given safe area and keyboard. */
    fun frame(w: Double, h: Double, safeTop: Double, safeBottom: Double, safeLeft: Double, safeRight: Double, keyboard: Double): FrameD {
        var x0 = if (ignoresSafeArea) 0.0 else safeLeft
        var y0 = if (ignoresSafeArea) 0.0 else safeTop
        var x1 = w - if (ignoresSafeArea) 0.0 else safeRight
        var y1 = h - if (ignoresSafeArea) 0.0 else safeBottom
        x0 += margin.leading; x1 -= margin.trailing; y0 += margin.top; y1 -= margin.bottom
        if (keyboard > 0 && avoidsKeyboard) {
            val overlap = (y1 - (h - keyboard)).coerceAtLeast(0.0)
            y1 -= overlap
        }
        val aw = (x1 - x0).coerceAtLeast(0.0)
        val ah = (y1 - y0).coerceAtLeast(0.0)
        val fw = resolve(width, aw, null).coerceIn(0.0, aw)
        val fh = resolve(height, ah, fw).coerceIn(0.0, ah)
        val x = when (align) {
            ModalAlign.leading -> x0
            ModalAlign.trailing -> x1 - fw
            else -> x0 + (aw - fw) / 2
        }
        val y = when (align) {
            ModalAlign.top -> y0
            ModalAlign.bottom -> y1 - fh
            else -> y0 + (ah - fh) / 2
        }
        return FrameD(x, y, fw, fh)
    }

    private fun resolve(d: ModalDimension, available: Double, cross: Double?): Double = when (d) {
        is ModalDimension.Points -> d.value
        is ModalDimension.Fraction -> d.value * available
        ModalDimension.Fill -> available
        is ModalDimension.Ratio -> (cross ?: available) * d.value
    }
}

internal data class FrameD(val x: Double, val y: Double, val width: Double, val height: Double)

/** Opacity / translate / scale / rotate of [p] at [fraction] (0 = begin, 1 = end) on a w×h px container. */
internal fun GraphicsLayerScope.applyKeyframes(p: Participant, fraction: Float, w: Float, h: Float) {
    val a = p.begin
    val b = p.end
    fun lerp(x: Double, y: Double) = (x + (y - x) * fraction).toFloat()
    alpha = lerp(a.opacity ?: 1.0, b.opacity ?: 1.0).coerceIn(0f, 1f)
    translationX = lerp(a.translate.x.resolved(w.toDouble()), b.translate.x.resolved(w.toDouble()))
    translationY = lerp(a.translate.y.resolved(h.toDouble()), b.translate.y.resolved(h.toDouble()))
    scaleX = lerp(a.scale.x, b.scale.x)
    scaleY = lerp(a.scale.y, b.scale.y)
    rotationZ = lerp(a.rotate, b.rotate)
    val anchor = if (fraction < 0.5f) a.anchor else b.anchor
    transformOrigin = TransformOrigin(anchor.x.toFloat(), anchor.y.toFloat())
}

/**
 * A transition animation as a Compose spec. Springs follow UIKit's
 * `animate(withDuration:usingSpringWithDamping:)`: the damping ratio as given, the stiffness
 * chosen so the spring settles in about `duration`.
 */
internal fun Animation.Inline.toTransitionSpec(): FiniteAnimationSpec<Float> {
    val ms = (duration * 1000).roundToInt()
    if (ms <= 0) return snap()
    val delayMs = (delay * 1000).roundToInt()
    return when (val t = timing) {
        is Animation.Timing.SpringTiming -> {
            val damping = t.spring.damping.coerceIn(0.05, 1.5)
            // 2% settling time ≈ 4 / (ζ·ω) → ω = 4 / (ζ·duration).
            val omega = 4.0 / (damping.coerceAtMost(1.0) * duration)
            val stiffness = t.spring.stiffness ?: (omega * omega)
            spring(dampingRatio = damping.toFloat(), stiffness = stiffness.toFloat().coerceAtLeast(1f), visibilityThreshold = 0.001f)
        }
        is Animation.Timing.CubicBezier -> tween(ms, delayMs, CubicBezierEasing(t.x1.toFloat(), t.y1.toFloat(), t.x2.toFloat(), t.y2.toFloat()))
        is Animation.Timing.Curve -> tween(
            ms, delayMs,
            when (t.curve) {
                Animation.NamedCurve.linear -> LinearEasing
                Animation.NamedCurve.easeIn -> FastOutLinearInEasing
                Animation.NamedCurve.easeOut -> LinearOutSlowInEasing
                Animation.NamedCurve.easeInOut -> FastOutSlowInEasing
            },
        )
    }
}
