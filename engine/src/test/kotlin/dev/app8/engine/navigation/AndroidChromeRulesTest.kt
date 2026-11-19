package dev.app8.engine.navigation

import androidx.compose.ui.graphics.Color
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import dev.app8.engine.style.contrastRatio
import dev.app8.engine.style.readableOn
import dev.app8.engine.style.systemColorScheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android-only chrome rules (docs/android-chrome-spec.md): tab colors and the empty root bar. */
class AndroidChromeRulesTest {
    private val light = systemColorScheme(null, dark = false)
    private val dark = systemColorScheme(null, dark = true)

    @Test
    fun onSurfaceVariantReadsOnTheTabBar() {
        assertTrue(contrastRatio(light.onSurfaceVariant, light.surfaceContainer) >= 4.5)
        assertTrue(contrastRatio(dark.onSurfaceVariant, dark.surfaceContainer) >= 4.5)
    }

    @Test
    fun tooLightUnselectedTabColorFallsBack() {
        val bar = light.surfaceContainer
        // An iOS system gray (2.3–3:1 on the bar) is replaced by onSurfaceVariant.
        val gray = Color(0xFF8E8E93)
        assertTrue(contrastRatio(gray, bar) < 4.5)
        assertEquals(light.onSurfaceVariant, readableOn(gray, bar, light.onSurfaceVariant))
        // A dark authored color stays exactly as authored.
        val ink = Color(0xFF3A3A3A)
        assertEquals(ink, readableOn(ink, bar, light.onSurfaceVariant))
        // Translucent colors count as composited over the bar.
        assertEquals(light.onSurfaceVariant, readableOn(Color.Black.copy(alpha = 0.3f), bar, light.onSurfaceVariant))
        assertEquals(light.onSurfaceVariant, readableOn(null, bar, light.onSurfaceVariant))
    }

    private fun bar(json: String): Boolean {
        val screen = """{"type":"screen","id":"s","content":{"navigationBar":$json,"children":[]}}"""
        val s = session(runtime(mapOf("s" to screen)))
        return isEmptyBar(s.content!!.navigationBar!!, s)
    }

    @Test
    fun aBarWithNothingInItIsEmpty() {
        assertTrue(bar("""{}"""))
        assertTrue(bar("""{"title":"","topEdgeEffect":"automatic","tintColor":"#111111"}"""))
        assertFalse(bar("""{"title":"Shop"}"""))
        assertFalse(bar("""{"rightActions":[{"icon":"heart","action":{"type":"haptic"}}]}"""))
        assertFalse(bar("""{"search":{"placeholder":"Search"}}"""))
    }
}
