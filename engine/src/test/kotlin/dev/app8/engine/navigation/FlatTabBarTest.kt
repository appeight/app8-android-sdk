package dev.app8.engine.navigation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.TabBarStyle
import org.junit.Test
import kotlin.test.assertEquals

/** Engine-drawn tab bars (tab-bar-screen.md, "Flat bars", 0.7.11). */
class FlatTabBarTest {
    @Test
    fun floatingFlatBarSafeAreaReachesItsTopEdge() {
        val floating = TabBarStyle(variant = TabBarStyle.Variant.text, background = "#000000", height = 32.0)
        assertEquals(32.0 + 8.0 + 24.0, tabBarSafeBottom(floating, 32.0, 24.0))
        val edge = floating.copy(shape = TabBarStyle.Shape.edge)
        assertEquals(32.0 + 24.0, tabBarSafeBottom(edge, 32.0, 24.0))
        val glassText = TabBarStyle(variant = TabBarStyle.Variant.text)
        assertEquals(56.0 + 24.0, tabBarSafeBottom(glassText, 56.0, 24.0))
        assertEquals(64.0 + 24.0, tabBarSafeBottom(null, 64.0, 24.0))
    }

    @Test
    fun cornersDefaultToACapsuleAndZeroIsARectangle() {
        val style = TabBarStyle(background = "solid")
        assertEquals(RoundedCornerShape(16.dp), tabBarShape(style, 32.0))
        assertEquals(RectangleShape, tabBarShape(style.copy(cornerRadius = 0.0), 32.0))
        assertEquals(RoundedCornerShape(6.dp), tabBarShape(style.copy(cornerRadius = 6.0), 32.0))
    }
}
