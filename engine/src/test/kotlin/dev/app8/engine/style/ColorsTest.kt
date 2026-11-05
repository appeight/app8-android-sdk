package dev.app8.engine.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.ThemedColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorsTest {

    private fun argb(hex: String) = ColorHex.parse(hex).toColor().toArgb()

    @Test
    fun `hex forms map to the components iOS builds UIColor from`() {
        assertEquals(0xFFFF0000.toInt(), argb("#F00"))
        assertEquals(0xFFFF5500.toInt(), argb("FF5500"))
        // RRGGBBAA: alpha last, like CSS and iOS.
        assertEquals(0x80FF5500.toInt(), argb("#FF550080"))
        // RGBA short form.
        assertEquals(0x88112233.toInt(), argb("#1238"))
        assertEquals(0xFFABCDEF.toInt(), argb("  #abcdef \n"))
    }

    @Test
    fun `short hex expands per nibble as iOS divides by 15`() {
        val c = ColorHex.parse("#8AF").toColor()
        assertEquals(8 / 15f, c.red, 1e-6f)
        assertEquals(10 / 15f, c.green, 1e-6f)
        assertEquals(1f, c.blue, 1e-6f)
    }

    @Test
    fun `invalid hex is clear, as iOS Hex(_ fallback)`() {
        assertEquals(Color.Transparent.toArgb(), ColorHex.parseOrFallback("#12345").toColor().toArgb())
        assertEquals(Color.Transparent.toArgb(), ColorHex.parseOrFallback("#GG0000").toColor().toArgb())
        // iOS quirk kept: Scanner reads the leading hex run of "BLUE" ("B") and the length 4 picks RGBA.
        assertEquals(0xBB000000.toInt(), ColorHex.parseOrFallback("blue").toColor().toArgb())
    }

    @Test
    fun `themed color follows the engine appearance and dark falls back to light`() {
        val themed = ThemedColor(ThemedColor.Source.THEMED, light = ColorHex.parse("#000000"), dark = ColorHex.parse("#FFFFFF"))
        assertEquals(Color.Black.toArgb(), themed.toColor(App8Appearance.Light).toArgb())
        assertEquals(Color.White.toArgb(), themed.toColor(App8Appearance.Dark).toArgb())
        val single = ThemedColor(ThemedColor.Source.SINGLE_HEX, light = ColorHex.parse("#123456"))
        assertEquals(0xFF123456.toInt(), single.toColor(App8Appearance.Dark).toArgb())
        val unresolved = ThemedColor(ThemedColor.Source.THEMED, lightRef = "a", darkRef = "b")
        assertEquals(Color.Transparent.toArgb(), unresolved.toColor(App8Appearance.Light).toArgb())
    }

    @Test
    fun `iOS system colors resolve to UIKit sRGB values`() {
        assertEquals(0xFF007AFF.toInt(), IosSystemColors.of("systemBlue", App8Appearance.Light).toArgb())
        assertEquals(0xFF0A84FF.toInt(), IosSystemColors.of("systemBlue", App8Appearance.Dark).toArgb())
        assertEquals(0xFFFF3B30.toInt(), IosSystemColors.of("systemRed", App8Appearance.Light).toArgb())
        assertEquals(0xFF1C1C1E.toInt(), IosSystemColors.of("systemGray6", App8Appearance.Dark).toArgb())
        val secondary = IosSystemColors.of("secondaryLabel", App8Appearance.Light)
        assertEquals(0.6f, secondary.alpha, 0.01f)
        assertNull(IosSystemColors.named("notAColor", App8Appearance.Light))
    }
}
