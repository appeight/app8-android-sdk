package dev.app8.engine.style

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.roundToInt

/**
 * The Material 3 color scheme system chrome (top app bar search field, navigation bar,
 * sheets, dialogs, pickers, ripple) draws with, built from the app's own DSL colors and
 * never Material's purple baseline (app8-dsl platforms.md, "Android system colors").
 * The Studio web renderer (`shoppu-web` `packages/app-dsl/src/android/colors.ts`)
 * implements the same function; keep both identical.
 *
 * - [accent]: the brand accent (component tint, else the tab bar tint), null for none:
 *   primary then falls back to a neutral near-black (near-white in dark).
 * - [background]: the opaque screen / app background; every surface role is a small
 *   neutral step from it toward the label color (white gives #F2F2F2, #EBEBEB…).
 */
internal fun systemColorScheme(accent: Color?, dark: Boolean, background: Color = if (dark) Color.Black else Color.White): ColorScheme {
    val bg = background.copy(alpha = 1f)
    val label = if (dark) Color.White else Color.Black
    fun step(t: Float) = mix(bg, label, t)
    val steps = if (dark) floatArrayOf(0f, 0.07f, 0.11f, 0.15f, 0.19f) else floatArrayOf(0f, 0.03f, 0.05f, 0.08f, 0.11f)
    val primary = accent?.copy(alpha = 1f) ?: step(0.85f)
    val onPrimary = onColor(primary)
    val primaryContainer = mix(bg, primary, 0.14f)
    val error = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val highest = step(steps[4])
    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primary,
        inversePrimary = primary,
        secondary = primary,
        onSecondary = onPrimary,
        secondaryContainer = primaryContainer,
        onSecondaryContainer = primary,
        tertiary = primary,
        onTertiary = onPrimary,
        tertiaryContainer = primaryContainer,
        onTertiaryContainer = primary,
        background = bg,
        onBackground = label,
        surface = bg,
        onSurface = label,
        surfaceVariant = highest,
        onSurfaceVariant = step(0.6f),
        surfaceTint = Color.Transparent,
        inverseSurface = mix(label, bg, 0.19f),
        inverseOnSurface = step(0.05f),
        error = error,
        onError = Color.White,
        errorContainer = mix(bg, error, 0.14f),
        onErrorContainer = error,
        outline = step(0.45f),
        outlineVariant = step(0.18f),
        scrim = Color.Black,
        surfaceBright = if (dark) step(0.23f) else bg,
        surfaceDim = if (dark) bg else step(0.13f),
        surfaceContainer = step(steps[2]),
        surfaceContainerHigh = step(steps[3]),
        surfaceContainerHighest = highest,
        surfaceContainerLow = step(steps[1]),
        surfaceContainerLowest = step(steps[0]),
    )
}

/** sRGB channel mix rounded to 8 bits, so Android and Studio produce the same hex. */
internal fun mix(a: Color, b: Color, t: Float): Color {
    val k = t.toDouble()
    fun ch(x: Float, y: Float) = (Math.round(x * 255.0) * (1 - k) + Math.round(y * 255.0) * k).roundToInt().coerceIn(0, 255) / 255f
    return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
}

/** Black or white, whichever reads on [c]. */
internal fun onColor(c: Color): Color = if (c.luminance() > 0.5f) Color.Black else Color.White

/**
 * Re-themes [content] when a component sets its own accent (a tab bar or navigation bar
 * `tintColor`); with null (or `App8Config.dynamicColor`) the enclosing theme stays.
 */
@Composable
internal fun App8ChromeTheme(accent: Color?, dark: Boolean, content: @Composable () -> Unit) {
    if (accent == null || dev.app8.engine.render.LocalRenderEnv.current.runtime.dynamicColor) return content()
    MaterialTheme(colorScheme = systemColorScheme(accent, dark), content = content)
}
