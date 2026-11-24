package dev.app8.engine.render.components

import androidx.compose.ui.graphics.Color
import dev.app8.engine.core.model.PageControlProperties.Style
import org.junit.Test
import kotlin.test.assertEquals

/** `pageControl` `style` (0.7.16): the capsule and default dots follow the look; explicit tints win. */
class PageControlLookTest {
    private val darkCapsule = Color(0f, 0f, 0f, 0.38f)
    private val lightCapsule = Color(1f, 1f, 1f, 0.72f)

    @Test
    fun automaticOnALightScreenIsLight() {
        val look = PageControlKind.look(Style.automatic, screenDark = false)
        assertEquals(lightCapsule, look.capsule)
        assertEquals(Color.Black, look.current)
        assertEquals(Color.Black.copy(alpha = 0.5f), look.other)
    }

    @Test
    fun automaticOnADarkScreenIsDark() {
        val look = PageControlKind.look(Style.automatic, screenDark = true)
        assertEquals(darkCapsule, look.capsule)
        assertEquals(Color.White, look.current)
        assertEquals(Color.White.copy(alpha = 0.5f), look.other)
    }

    @Test
    fun explicitStylesIgnoreTheScreen() {
        assertEquals(lightCapsule, PageControlKind.look(Style.light, screenDark = true).capsule)
        assertEquals(Color.Black, PageControlKind.look(Style.light, screenDark = true).current)
        assertEquals(darkCapsule, PageControlKind.look(Style.dark, screenDark = false).capsule)
        assertEquals(Color.White, PageControlKind.look(Style.dark, screenDark = false).current)
    }

    @Test
    fun explicitTintsWinOverTheStyle() {
        val red = Color(1f, 0f, 0f, 1f)
        val grey = Color(0.5f, 0.5f, 0.5f, 1f)
        val look = PageControlKind.look(Style.light, screenDark = false, currentTint = red, otherTint = grey)
        assertEquals(lightCapsule, look.capsule)
        assertEquals(red, look.current)
        assertEquals(grey, look.other)
    }
}
