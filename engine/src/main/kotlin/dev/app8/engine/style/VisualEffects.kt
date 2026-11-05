package dev.app8.engine.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.app8.engine.core.model.style.BackgroundBlur
import dev.app8.engine.core.model.style.Glass
import dev.app8.engine.core.model.style.VisualEffect

// Android has no UIVisualEffectView. What iOS does vs what we draw:
//
// - UIBlurEffect blurs whatever is behind the view (the backdrop), boosts its
//   saturation and lays a tinted, partly opaque "material" over it. Compose can
//   only blur content it records itself (RenderEffect on a GraphicsLayer, API 31+).
//   So a screen marks its content as a backdrop source (`app8BackdropSource`) and
//   materials drawn *above that source* sample it: one downsampled GraphicsLayer
//   per material with a blur + saturation RenderEffect, clipped to the corner.
//   A material inside the source subtree (it would sample itself) and any material
//   with no source draws the tint over an opaque-ish fallback, like iOS with
//   "Reduce Transparency" on.
// - The tints and blur radii below are measured approximations of UIKit's private
//   material recipes (Apple doesn't publish them); vibrancy is not reproduced.
// - iOS 26 Liquid Glass (UIGlassEffect) refracts and lenses the backdrop, adds
//   specular highlights that follow device motion and morphs between shapes.
//   None of that is reproducible with RenderEffect cheaply. We draw the same
//   approximation as the web port (shoppu-web `GLASS`): a light blur, strong
//   saturation, a thin white tint, a bright rim on top and a soft lift shadow.
//   `container` (content inside the glass, interactive morph) has no equivalent:
//   children are drawn above the glass, as for non-container glass.

/** How one blur / glass layer is drawn. */
@Immutable
internal data class EffectSpec(
    /** Gaussian sigma of the backdrop blur, in dp. */
    val blurSigmaDp: Float,
    /** Saturation multiplier applied to the blurred backdrop (1 = unchanged). */
    val saturation: Float,
    /** RGB multiplier applied after saturation (1 = unchanged). */
    val brightness: Float,
    /** Tint laid over the blurred backdrop. */
    val tint: Color,
    /** Drawn instead of backdrop + tint when nothing can be sampled. */
    val fallback: Color,
    /** Glass only: specular rim and lift. */
    val glass: Boolean = false,
)

private val LIGHT_BASE = Color(0xFFF7F7F7)
private val DARK_BASE = Color(0xFF1C1C1E)

private fun material(base: Color, alpha: Float, fallbackAlpha: Float = 0.94f, sigma: Float = 20f) =
    EffectSpec(sigma, 1.8f, 1f, base.copy(alpha = alpha), base.copy(alpha = maxOf(alpha, fallbackAlpha)))

/** iOS `UIBlurEffect.Style` → effect. Non-`Dark` system materials follow [appearance], as on iOS. */
internal fun BackgroundBlur.effectSpec(appearance: App8Appearance): EffectSpec {
    val dark = appearance.isDark
    val adaptiveBase = if (dark) DARK_BASE else LIGHT_BASE
    return when (this) {
        BackgroundBlur.extraLight -> material(Color.White, 0.72f)
        BackgroundBlur.light -> material(Color.White, 0.30f)
        BackgroundBlur.dark -> material(Color(0xFF161616), 0.73f)
        BackgroundBlur.regular -> if (dark) material(Color(0xFF161616), 0.73f) else material(Color.White, 0.30f)
        BackgroundBlur.systemUltraThinMaterial -> material(adaptiveBase, if (dark) 0.45f else 0.36f)
        BackgroundBlur.systemThinMaterial -> material(adaptiveBase, if (dark) 0.62f else 0.60f)
        BackgroundBlur.systemMaterial -> material(adaptiveBase, if (dark) 0.78f else 0.76f)
        BackgroundBlur.systemThickMaterial -> material(adaptiveBase, if (dark) 0.90f else 0.88f)
        BackgroundBlur.systemChromeMaterial -> material(if (dark) Color(0xFF262628) else Color(0xFFF2F2F2), 0.80f)
        BackgroundBlur.systemUltraThinMaterialDark -> material(DARK_BASE, 0.45f)
        BackgroundBlur.systemThinMaterialDark -> material(DARK_BASE, 0.62f)
        BackgroundBlur.systemMaterialDark -> material(DARK_BASE, 0.78f)
        BackgroundBlur.systemThickMaterialDark -> material(DARK_BASE, 0.90f)
        BackgroundBlur.systemChromeMaterialDark -> material(Color(0xFF262628), 0.80f)
    }
}

/** Liquid-glass approximation (see the file comment). */
internal fun Glass.effectSpec(appearance: App8Appearance): EffectSpec {
    val dark = appearance.isDark
    return when (this) {
        Glass.normal -> EffectSpec(
            blurSigmaDp = 10f, saturation = 1.9f, brightness = if (dark) 0.94f else 1.06f,
            tint = if (dark) Color(0x5C2A2A2C) else Color(0x57FFFFFF),
            fallback = if (dark) Color(0xEB2A2A2C) else Color(0xEBF7F7F7),
            glass = true,
        )
        Glass.clear -> EffectSpec(
            blurSigmaDp = 4f, saturation = 1.4f, brightness = 1f,
            tint = if (dark) Color(0x24000000) else Color(0x1FFFFFFF),
            fallback = if (dark) Color(0x99202022) else Color(0x99FFFFFF),
            glass = true,
        )
    }
}

/**
 * iOS `MaterialView.makeVisualEffectView`: `blur` wins, then `glass`, and an empty
 * effect is `UIBlurEffect(style: .regular)`. Glass below iOS 26 is
 * `.systemUltraThinMaterial`; we always draw the iOS 26 look (Android has no OS split).
 */
internal fun VisualEffect.effectSpec(appearance: App8Appearance): EffectSpec =
    blur?.effectSpec(appearance) ?: glass?.effectSpec(appearance) ?: BackgroundBlur.regular.effectSpec(appearance)
