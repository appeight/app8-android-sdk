package dev.app8.engine.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

/** Same expectations as Studio's `packages/app-dsl/src/android/colors.test.ts`. */
class SystemColorSchemeTest {

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    @Test
    fun `white background gives neutral off-white containers, not lavender`() {
        val s = systemColorScheme(accent = null, dark = false)
        assertEquals("#FFFFFF", hex(s.surface))
        assertEquals("#F7F7F7", hex(s.surfaceContainerLow))
        assertEquals("#F2F2F2", hex(s.surfaceContainer))
        assertEquals("#EBEBEB", hex(s.surfaceContainerHigh))
        assertEquals("#E3E3E3", hex(s.surfaceContainerHighest))
        assertEquals("#000000", hex(s.onSurface))
        assertEquals("#666666", hex(s.onSurfaceVariant))
        // No accent: a neutral primary, no purple.
        assertEquals("#262626", hex(s.primary))
        assertEquals("#FFFFFF", hex(s.onPrimary))
    }

    @Test
    fun `dark scheme steps up from black`() {
        val s = systemColorScheme(accent = null, dark = true)
        assertEquals("#000000", hex(s.surface))
        assertEquals("#1C1C1C", hex(s.surfaceContainer))
        assertEquals("#262626", hex(s.surfaceContainerHigh))
        assertEquals("#FFFFFF", hex(s.onSurface))
        assertEquals("#D9D9D9", hex(s.primary))
        assertEquals("#000000", hex(s.onPrimary))
    }

    @Test
    fun `brand accent drives primary, secondary and the indicator container`() {
        val s = systemColorScheme(accent = Color(0xFF214E38), dark = false)
        assertEquals("#214E38", hex(s.primary))
        assertEquals("#214E38", hex(s.secondary))
        assertEquals("#FFFFFF", hex(s.onPrimary))
        assertEquals("#E0E6E3", hex(s.secondaryContainer))
        // Surfaces stay neutral whatever the accent.
        assertEquals("#F2F2F2", hex(s.surfaceContainer))
    }
}
