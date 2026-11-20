package dev.app8.engine.style

import dev.app8.engine.UserInterfaceStyle
import dev.app8.engine.core.model.App
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The app's appearance and a screen's own `userInterfaceStyle` over it. */
class ScreenAppearanceTest {
    @Test
    fun `the app follows the host, then the app default, then the system`() {
        assertEquals(App8Appearance.Dark, appAppearance(UserInterfaceStyle.DARK, App.UserInterfaceStyle.light, systemDark = false))
        assertEquals(App8Appearance.Light, appAppearance(UserInterfaceStyle.LIGHT, App.UserInterfaceStyle.dark, systemDark = true))
        assertEquals(App8Appearance.Dark, appAppearance(UserInterfaceStyle.UNSPECIFIED, App.UserInterfaceStyle.dark, systemDark = false))
        assertEquals(App8Appearance.Light, appAppearance(UserInterfaceStyle.UNSPECIFIED, App.UserInterfaceStyle.light, systemDark = true))
        assertEquals(App8Appearance.Dark, appAppearance(UserInterfaceStyle.UNSPECIFIED, null, systemDark = true))
        assertEquals(App8Appearance.Light, appAppearance(UserInterfaceStyle.UNSPECIFIED, null, systemDark = false))
    }

    @Test
    fun `a screen's own style wins over the app's`() {
        assertEquals(App8Appearance.Dark, screenAppearance(App8Appearance.Light, App.UserInterfaceStyle.dark))
        assertEquals(App8Appearance.Light, screenAppearance(App8Appearance.Dark, App.UserInterfaceStyle.light))
        assertEquals(App8Appearance.Dark, screenAppearance(App8Appearance.Dark, App.UserInterfaceStyle.dark))
    }

    @Test
    fun `a screen without a style follows the app`() {
        assertEquals(App8Appearance.Light, screenAppearance(App8Appearance.Light, null))
        assertEquals(App8Appearance.Dark, screenAppearance(App8Appearance.Dark, null))
        assertNull(appearanceOf(null))
    }

    @Test
    fun `a dark screen gets the dark scheme in a light app`() {
        val dark = systemColorScheme(accent = null, dark = screenAppearance(App8Appearance.Light, App.UserInterfaceStyle.dark).isDark)
        assertEquals(androidx.compose.ui.graphics.Color.Black, dark.surface)
        assertEquals(androidx.compose.ui.graphics.Color.White, dark.onSurface)
    }
}
