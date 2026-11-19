package dev.app8.engine.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * Android system motion for chrome the engine owns (not DSL-authored transitions): tab
 * switch, full-screen cover, sheet close and the predictive back preview. Numbers from
 * compose-material3 `MotionTokens` and the M3 motion and predictive back guidelines; the web
 * renderer's Android preview copies them (docs/android-chrome-spec.md, "Motion").
 */
internal object MotionSpecs {
    /** `MotionTokens.EasingEmphasizedDecelerateCubicBezier`: elements entering. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** `MotionTokens.EasingEmphasizedAccelerateCubicBezier`: elements leaving. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    // Springs for engine chrome: compose-material3 `MotionScheme.standard()` (its accessor is
    // internal in the stable artifact, so the token values are copied here).

    /** Standard scheme `defaultSpatialSpec`: damping 0.9, stiffness 700. */
    const val SPATIAL_DAMPING = 0.9f
    const val SPATIAL_STIFFNESS = 700f

    /** Standard scheme `defaultEffectsSpec`: damping 1.0, stiffness 1600. */
    const val EFFECTS_DAMPING = 1f
    const val EFFECTS_STIFFNESS = 1600f

    fun <T> spatialSpring(): FiniteAnimationSpec<T> = androidx.compose.animation.core.spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    fun <T> effectsSpring(): FiniteAnimationSpec<T> = androidx.compose.animation.core.spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    // Tab switch: M3 fade through (MDC `MaterialFadeThrough`, 300ms total).

    /** The outgoing tab fades out over this long (35% of the 300ms fade through, rounded to the M3 90ms). */
    const val TAB_FADE_OUT_MS = 90

    /** The incoming tab fades in and scales up over this long, after [TAB_FADE_OUT_MS]. */
    const val TAB_FADE_IN_MS = 210

    /** The incoming tab's starting scale. */
    const val TAB_SCALE_FROM = 0.92f

    /** M3 fade through between two tabs' content. */
    fun tabFadeThrough(): ContentTransform =
        (
            fadeIn(tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_OUT_MS, easing = EmphasizedDecelerate)) +
                scaleIn(tween(TAB_FADE_IN_MS, delayMillis = TAB_FADE_OUT_MS, easing = EmphasizedDecelerate), initialScale = TAB_SCALE_FROM)
            ) togetherWith fadeOut(tween(TAB_FADE_OUT_MS, easing = LinearEasing))

    // Full-screen cover (`fullScreen` / `overFullScreen` / `crossDissolve` without a custom transition).

    /** Cover enter: M3 `DurationMedium4`, page-level, emphasized decelerate. */
    const val COVER_ENTER_MS = 400

    /** Cover exit: M3 `DurationMedium1`, emphasized accelerate (exits are shorter). */
    const val COVER_EXIT_MS = 250

    fun coverEnter(): EnterTransition =
        slideInVertically(tween<IntOffset>(COVER_ENTER_MS, easing = EmphasizedDecelerate)) { it } +
            fadeIn(tween(COVER_ENTER_MS / 2, easing = LinearEasing))

    fun coverExit(): ExitTransition =
        slideOutVertically(tween<IntOffset>(COVER_EXIT_MS, easing = EmphasizedAccelerate)) { it } +
            fadeOut(tween(COVER_EXIT_MS, easing = EmphasizedAccelerate))

    fun coverFadeIn(): FiniteAnimationSpec<Float> = tween(COVER_ENTER_MS / 2, easing = LinearEasing)

    fun coverFadeOut(): FiniteAnimationSpec<Float> = tween(COVER_EXIT_MS, easing = EmphasizedAccelerate)

    /** Sheet scrim fade in, with the sheet (M3 `DurationMedium2`, emphasized decelerate). */
    const val SHEET_SCRIM_IN_MS = 300

    /** Sheet scrim fade out as the sheet heads for hidden (emphasized accelerate, 200–250ms band). */
    const val SHEET_SCRIM_OUT_MS = 220

    // Predictive back preview (M3 "Predictive back", compose-material3 `PredictiveBack`).

    /** Material's predictive back easing over the gesture progress (`PredictiveBack.transform`). */
    val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

    /** Scale of the leaving screen at full progress. */
    const val BACK_SCALE = 0.9f

    /** The drift is width / [BACK_DRIFT_DIVISOR] − [BACK_DRIFT_MARGIN_DP]. */
    const val BACK_DRIFT_DIVISOR = 20f
    const val BACK_DRIFT_MARGIN_DP = 8f

    /** Corner radius at full progress when the display reports no rounded corners. */
    const val BACK_CORNER_FALLBACK_DP = 28f

    /** Eased progress for the preview transform from the raw gesture progress. */
    fun backEased(progress: Float): Float = PredictiveBackEasing.transform(progress.coerceIn(0f, 1f))

    /** The leaving screen's scale at [eased] progress. */
    fun backScale(eased: Float): Float = 1f - (1f - BACK_SCALE) * eased

    /** Horizontal drift (px, toward the inside, away from the swipe edge) at [eased] progress. */
    fun backDriftPx(eased: Float, widthPx: Float, density: Float): Float =
        ((widthPx / BACK_DRIFT_DIVISOR - BACK_DRIFT_MARGIN_DP * density).coerceAtLeast(0f)) * eased

    /** Corner radius (px) at [eased] progress, growing toward the display's corner radius. */
    fun backCornerPx(eased: Float, displayCornerPx: Float): Float = displayCornerPx * eased
}
