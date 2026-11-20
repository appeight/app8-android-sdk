package dev.app8.engine.navigation

import androidx.compose.ui.graphics.Color
import org.junit.Test
import kotlin.test.assertEquals

/** tabBarScreen `selectionIndicatorColor` (tab-bar-screen.md, 0.7.9). */
class SelectionIndicatorColorTest {
    @Test
    fun authoredColourWins() {
        val amber = Color(0x40E0A100)
        assertEquals(amber, selectionIndicatorColorFor(amber, Color.White))
    }

    @Test
    fun defaultIsBlackOnALightBarAndWhiteOnADarkOne() {
        assertEquals(Color.Black.copy(alpha = 0.10f), selectionIndicatorColorFor(null, Color(0xFFF3EDF7)))
        assertEquals(Color.White.copy(alpha = 0.10f), selectionIndicatorColorFor(null, Color(0xFF211F26)))
    }
}
