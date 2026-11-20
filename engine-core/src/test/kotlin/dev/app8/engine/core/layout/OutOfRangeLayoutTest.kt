package dev.app8.engine.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An out-of-range DSL number in layout is ignored like a broken constraint; the rest lays out. */
class OutOfRangeLayoutTest {
    @Test
    fun `huge stack spacing and lengths do not fail the solve`() {
        val stack = LayoutNode(
            "root.s", stack = StackSpec(StackAxis.VERTICAL, 1e308),
            layout = Layout(leading = Dimension.Fixed(0.0), trailing = Dimension.Fixed(0.0), top = Dimension.Fixed(0.0)),
            children = listOf(
                LayoutNode("root.s.a", layout = Layout(height = Dimension.Fixed(40.0))),
                LayoutNode("root.s.b", layout = Layout(height = Dimension.Fixed(-1e300))),
            ),
        )
        val r = solveLayout(LayoutNode("root", children = listOf(stack)), LayoutBounds(400.0, 800.0))
        assertEquals(400.0, r.frames.getValue("root.s").width)
        assertEquals(40.0, r.frames.getValue("root.s.a").height)
        assertTrue(r.conflicts.isNotEmpty())
        assertTrue(r.frames.values.all { it.x.isFinite() && it.y.isFinite() && it.width.isFinite() && it.height.isFinite() })
    }
}
