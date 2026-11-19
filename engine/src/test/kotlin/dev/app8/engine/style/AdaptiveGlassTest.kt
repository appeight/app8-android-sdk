package dev.app8.engine.style

import dev.app8.engine.core.model.style.Glass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** styles.md, glassAppearance: glass follows the content behind it (auto) or is pinned. */
class AdaptiveGlassTest {
    @Test fun hysteresis() {
        val mid = (GLASS_DARK_BELOW + GLASS_LIGHT_ABOVE) / 2
        assertTrue(nextGlassDark(null, 0.02f))
        assertFalse(nextGlassDark(null, 0.8f))
        assertTrue(nextGlassDark(true, mid))
        assertFalse(nextGlassDark(false, mid))
        assertFalse(nextGlassDark(true, GLASS_LIGHT_ABOVE + 0.01f))
        assertTrue(nextGlassDark(false, GLASS_DARK_BELOW - 0.01f))
    }

    @Test fun luminance() {
        assertEquals(0f, relativeLuminance(0, 0, 0), 1e-6f)
        assertEquals(1f, relativeLuminance(255, 255, 255), 1e-4f)
    }

    @Test fun glassRedrawsLightOrDark() {
        val light = Glass.normal.effectSpec(App8Appearance.Light)
        val dark = Glass.normal.effectSpec(App8Appearance.Dark)
        assertEquals(light.tint, light.glassAt(0f).tint)
        assertEquals(dark.tint, dark.glassAt(1f).tint)
        assertEquals(dark.tint, light.glassAt(1f).tint)
    }
}
