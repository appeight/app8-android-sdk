package dev.app8.engine.render

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Solved frames and DSL insets become Compose pixels and padding without throwing. */
class PixelSafetyTest {
    @Test
    fun `an out-of-range frame is clamped to representable constraints`() {
        // What Compose itself does with such a frame (why fixedConstraints exists).
        assertFailsWith<IllegalArgumentException> { Constraints.fixed(1179, 2_999_997) }
        val tall = fixedConstraints(1179, 2_999_997)
        assertEquals(1179, tall.maxWidth)
        assertTrue(tall.maxHeight in 200_000..2_999_997, "${tall.maxHeight}")
        val wide = fixedConstraints(2_999_997, 100)
        assertEquals(100, wide.maxHeight)
        assertTrue(wide.maxWidth >= 200_000)
        assertEquals(Constraints.fixed(300, 600), fixedConstraints(300, 600))
    }

    @Test
    fun `NaN, infinite and negative lengths become valid pixel sizes`() {
        assertEquals(0, px(Double.NaN, 3f))
        assertEquals(0, pxSize(Double.NaN, 3f))
        assertEquals(0, pxSize(-12.0, 3f))
        assertEquals(MAX_LAYOUT_PX, pxSize(Double.POSITIVE_INFINITY, 3f))
        assertEquals(MAX_LAYOUT_PX, pxSize(1e308, 3f))
        assertEquals(36, pxSize(12.0, 3f))
    }

    @Test
    fun `negative insets pad nothing instead of throwing`() {
        assertFailsWith<IllegalArgumentException> { PaddingValues(top = (-8.0).dp) }
        PaddingValues(top = (-8.0).padDp, bottom = Double.NaN.padDp)
        assertEquals(0.dp, (-8.0).padDp)
        assertEquals(8.dp, 8.0.padDp)
    }
}
