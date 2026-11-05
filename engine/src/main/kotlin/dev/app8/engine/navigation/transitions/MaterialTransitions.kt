package dev.app8.engine.navigation.transitions

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.scene.Scene

/**
 * The `system` screen transition on Android: what Pixel apps (Settings, SettingsLib) do
 * between hierarchical screens, not Navigation 3's 700ms cross-fade default.
 *
 * - Push / pop: Material shared axis X. Both screens slide [SLIDE_DP] along x on the
 *   emphasized curve over [DURATION_MS]; the outgoing screen fades out over the first 35%,
 *   the incoming one fades in over the rest (MDC `MaterialSharedAxis`, M3 motion).
 * - Predictive back: the Material in-app predictive back preview. While the gesture
 *   drags, the top screen shrinks to [PREDICTIVE_SCALE] and drifts toward the swipe edge
 *   by 1/20 of its width; the screen below fades and slides in from the shared axis. The
 *   top screen fades only in the last third, so the preview stays readable.
 *
 * Studio's web renderer copies these numbers (docs/android-chrome-spec.md).
 */
internal object MaterialTransitions {
    /** M3 `motionDurationLong1`, the shared axis duration MDC uses with M3 themes. */
    const val DURATION_MS = 450

    /** Shared axis slide distance (MDC `mtrl_shared_axis_x_slide_distance`). */
    const val SLIDE_DP = 30

    /** Fraction of [DURATION_MS] over which the outgoing screen fades out (fade-through). */
    const val FADE_SPLIT = 0.35f

    /** Top app bar container color change on scroll (Compose TopAppBar: `MotionSchemeKeyTokens.DefaultEffects`, ~effects spring; a short tween here). */
    const val COLOR_MS = 200

    /** Predictive back: the top screen's scale at full gesture progress. */
    const val PREDICTIVE_SCALE = 0.9f

    /** M3 emphasized easing as one cubic (the standard curve, `MotionTokens.EasingStandardCubicBezier`). */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private val fadeOutMs = (DURATION_MS * FADE_SPLIT).toInt()
    private val fadeInMs = DURATION_MS - fadeOutMs

    private fun <T> slide(): FiniteAnimationSpec<T> = tween(DURATION_MS, easing = Emphasized)

    fun <T : Any> push(density: Float): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        val d = (SLIDE_DP * density).toInt()
        (slideInHorizontally(slide<IntOffset>()) { d } + fadeIn(tween(fadeInMs, delayMillis = fadeOutMs, easing = LinearEasing))) togetherWith
            (slideOutHorizontally(slide<IntOffset>()) { -d } + fadeOut(tween(fadeOutMs, easing = LinearEasing)))
    }

    fun <T : Any> pop(density: Float): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        val d = (SLIDE_DP * density).toInt()
        (slideInHorizontally(slide<IntOffset>()) { -d } + fadeIn(tween(fadeInMs, delayMillis = fadeOutMs, easing = LinearEasing))) togetherWith
            (slideOutHorizontally(slide<IntOffset>()) { d } + fadeOut(tween(fadeOutMs, easing = LinearEasing)))
    }

    /** [edge] is `BackEventCompat.EDGE_LEFT` / `EDGE_RIGHT`: the screen drifts away from it. */
    fun <T : Any> predictivePop(density: Float): AnimatedContentTransitionScope<Scene<T>>.(Int) -> ContentTransform = { edge ->
        val d = (SLIDE_DP * density).toInt()
        val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
        val linear = tween<Float>(DURATION_MS, easing = LinearEasing)
        (slideInHorizontally(tween(DURATION_MS, easing = LinearEasing)) { -d * sign } + fadeIn(linear)) togetherWith
            (
                scaleOut(linear, targetScale = PREDICTIVE_SCALE) +
                    slideOutHorizontally(tween(DURATION_MS, easing = LinearEasing)) { w -> sign * w / 20 } +
                    fadeOut(tween(DURATION_MS / 3, delayMillis = DURATION_MS * 2 / 3, easing = LinearEasing))
                )
    }
}
