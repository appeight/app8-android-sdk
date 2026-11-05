package dev.app8.engine.style

import androidx.compose.ui.graphics.Color
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.Wrapped

// Color resolution. Hex parsing lives in engine-core (`ColorHex.parse`, equal to
// iOS `Style.Color.Hex`: #RGB, #RGBA, #RRGGBB, #RRGGBBAA, alpha last). DSL colors
// are sRGB, like `UIColor(red:green:blue:alpha:)`.

/** sRGB Compose color with the exact components iOS builds its `UIColor` from. */
internal fun ColorHex.toColor(): Color = Color(red = r.coerceIn(0f, 1f), green = g.coerceIn(0f, 1f), blue = b.coerceIn(0f, 1f), alpha = a.coerceIn(0f, 1f))

/** iOS `Themed.ui` resolved for [appearance]: dark falls back to light, nothing resolves to clear. */
internal fun ThemedColor.toColor(appearance: App8Appearance): Color = resolve(appearance.isDark).toColor()

/** A wrapped (inline or entity) color; `null` when absent or still an unresolved pointer. */
internal fun Wrapped<ThemedColor>.colorOrNull(appearance: App8Appearance): Color? = value?.toColor(appearance)

/**
 * iOS named system colors (`UIColor.systemBlue`, `.label`, …) with the sRGB values
 * UIKit resolves them to in light and dark (iOS 17/18, default contrast). Used by
 * system chrome and components that default to a UIKit color, so Android draws the
 * same brand-neutral defaults. Not accepted in DSL color strings (iOS doesn't either).
 */
internal object IosSystemColors {
    internal data class Pair(val light: Color, val dark: Color) {
        fun resolve(appearance: App8Appearance): Color = if (appearance.isDark) dark else light
    }

    private fun rgb(v: Long, alpha: Float = 1f) = Color(
        red = ((v shr 16) and 0xFF) / 255f,
        green = ((v shr 8) and 0xFF) / 255f,
        blue = (v and 0xFF) / 255f,
        alpha = alpha,
    )

    private fun p(light: Long, dark: Long, lightAlpha: Float = 1f, darkAlpha: Float = lightAlpha) =
        Pair(rgb(light, lightAlpha), rgb(dark, darkAlpha))

    val all: Map<String, Pair> = mapOf(
        "systemRed" to p(0xFF3B30, 0xFF453A),
        "systemOrange" to p(0xFF9500, 0xFF9F0A),
        "systemYellow" to p(0xFFCC00, 0xFFD60A),
        "systemGreen" to p(0x34C759, 0x30D158),
        "systemMint" to p(0x00C7BE, 0x66D4CF),
        "systemTeal" to p(0x30B0C7, 0x40C8E0),
        "systemCyan" to p(0x32ADE6, 0x64D2FF),
        "systemBlue" to p(0x007AFF, 0x0A84FF),
        "systemIndigo" to p(0x5856D6, 0x5E5CE6),
        "systemPurple" to p(0xAF52DE, 0xBF5AF2),
        "systemPink" to p(0xFF2D55, 0xFF375F),
        "systemBrown" to p(0xA2845E, 0xAC8E68),
        "systemGray" to p(0x8E8E93, 0x8E8E93),
        "systemGray2" to p(0xAEAEB2, 0x636366),
        "systemGray3" to p(0xC7C7CC, 0x48484A),
        "systemGray4" to p(0xD1D1D6, 0x3A3A3C),
        "systemGray5" to p(0xE5E5EA, 0x2C2C2E),
        "systemGray6" to p(0xF2F2F7, 0x1C1C1E),
        "label" to p(0x000000, 0xFFFFFF),
        "secondaryLabel" to p(0x3C3C43, 0xEBEBF5, 0.6f, 0.6f),
        "tertiaryLabel" to p(0x3C3C43, 0xEBEBF5, 0.3f, 0.3f),
        "quaternaryLabel" to p(0x3C3C43, 0xEBEBF5, 0.18f, 0.16f),
        "placeholderText" to p(0x3C3C43, 0xEBEBF5, 0.3f, 0.3f),
        "link" to p(0x007AFF, 0x0984FF),
        "systemFill" to p(0x787880, 0x787880, 0.2f, 0.36f),
        "secondarySystemFill" to p(0x787880, 0x787880, 0.16f, 0.32f),
        "tertiarySystemFill" to p(0x767680, 0x767680, 0.12f, 0.24f),
        "quaternarySystemFill" to p(0x747480, 0x767680, 0.08f, 0.18f),
        "systemBackground" to p(0xFFFFFF, 0x000000),
        "secondarySystemBackground" to p(0xF2F2F7, 0x1C1C1E),
        "tertiarySystemBackground" to p(0xFFFFFF, 0x2C2C2E),
        "systemGroupedBackground" to p(0xF2F2F7, 0x000000),
        "secondarySystemGroupedBackground" to p(0xFFFFFF, 0x1C1C1E),
        "tertiarySystemGroupedBackground" to p(0xF2F2F7, 0x2C2C2E),
        "separator" to p(0x3C3C43, 0x545458, 0.29f, 0.6f),
        "opaqueSeparator" to p(0xC6C6C8, 0x38383A),
        "white" to p(0xFFFFFF, 0xFFFFFF),
        "black" to p(0x000000, 0x000000),
        "clear" to p(0x000000, 0x000000, 0f, 0f),
    )

    fun named(name: String, appearance: App8Appearance): Color? = all[name]?.resolve(appearance)

    fun of(name: String, appearance: App8Appearance): Color = requireNotNull(named(name, appearance)) { "unknown iOS color $name" }
}
