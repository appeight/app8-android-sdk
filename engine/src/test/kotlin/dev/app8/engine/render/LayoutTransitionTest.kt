package dev.app8.engine.render

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.ScrollSpec
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.layout.StackSpec
import dev.app8.engine.core.model.Animation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Animated `isHidden` in a stack (a disclosure list): one animated layout pass from the frames
 * drawn now to the new solve (app8-dsl animations.md "Animated visibility").
 */
class LayoutTransitionTest {
    private val cubic = Animation.Inline(duration = 0.3, timing = Animation.Timing.CubicBezier(0.33, 1.0, 0.68, 1.0))
    private val ms = 1_000_000L
    private val cache = SolveCache()

    private fun box(key: String, h: Double, hidden: Boolean = false, children: List<LayoutNode> = emptyList()) =
        LayoutNode(key = key, hidden = hidden, children = children, intrinsicSize = if (children.isEmpty()) IntrinsicMeasure { Size(Size.NO_INTRINSIC_METRIC, h) } else null,
            stack = if (children.isEmpty()) null else StackSpec())

    /** scroll > list (vertical stack) > [Shipping header, Shipping body, Returns header, Care header]. */
    private fun solve(shippingOpen: Boolean, returnsOpen: Boolean = false): Solved {
        val body = { id: String, open: Boolean -> box("$id.body", 0.0, hidden = !open, children = listOf(box("$id.text", 120.0))) }
        val list = LayoutNode(
            key = "list",
            stack = StackSpec(),
            children = listOf(
                box("shipping.header", 50.0), body("shipping", shippingOpen),
                box("returns.header", 50.0), body("returns", returnsOpen),
                box("care.header", 50.0),
            ),
        )
        val tree = LayoutNode(key = "root", children = listOf(LayoutNode(key = "scroll", scroll = ScrollSpec(), children = listOf(list),
            layout = null)))
        val animated = mapOf("shipping.body" to cubic, "returns.body" to cubic)
        val sig = "s$shippingOpen r$returnsOpen"
        return cache.solve(tree, sig, LayoutBounds(400.0, 800.0), EdgeInsets.ZERO, 1.0) { LayoutStructure.of(tree, animated) }
    }

    private fun transition(reduceMotion: Boolean = false) = LayoutTransition(
        resolve = { a -> (a as Animation.Inline).let { tween(300, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)) } },
        reduceMotion = { reduceMotion },
    )

    @Test
    fun `opening a row grows the body and moves the rows after it on the same curve`() {
        val t = transition()
        val closed = solve(false)
        val open = solve(true)
        assertEquals(0.0, t.displayed(closed).local.getValue("shipping.body").height, "first apply is instant")
        assertFalse(t.running)

        t.displayed(open)
        assertTrue(t.running)
        assertTrue(t.keepsDrawn("shipping.body"))
        t.onFrame(0)
        val start = t.displayed(open)
        assertEquals(0.0, start.local.getValue("shipping.body").height, 0.01, "starts from the frames drawn now")
        assertEquals(50.0, start.local.getValue("returns.header").y, 0.01)
        assertEquals(0f, t.alpha("shipping.body"))

        t.onFrame(100 * ms)
        val mid = t.displayed(open)
        val h = mid.local.getValue("shipping.body").height
        assertTrue(h > 0.0 && h < 120.0, "mid-animation height $h between 0 and full")
        assertTrue(h > 60.0, "a cubic ease-out is past half way after a third of the time: $h")
        // Everything after it moves with the body; the content is laid out at its final size.
        assertEquals(50.0 + h, mid.local.getValue("returns.header").y, 0.01)
        assertEquals(120.0, mid.local.getValue("shipping.text").height, 0.01)
        assertEquals(150.0 + h, mid.contentSizes.getValue("scroll").height, 0.01)
        assertTrue(t.clips("shipping.body"))
        assertEquals(120.0, assertNotNull(t.fullSize("shipping.body")).height, 0.01)
        val a = t.alpha("shipping.body")
        assertTrue(a > 0f && a < 1f)

        t.onFrame(300 * ms)
        assertFalse(t.running)
        val end = t.displayed(open)
        assertEquals(120.0, end.local.getValue("shipping.body").height, 0.01)
        assertEquals(170.0, end.local.getValue("returns.header").y, 0.01)
        assertEquals(1f, t.alpha("shipping.body"))
        assertNull(t.fullSize("shipping.body"))
    }

    @Test
    fun `closing keeps the body drawn while it shrinks, then hides it`() {
        val t = transition()
        t.displayed(solve(true))
        val closed = solve(false)
        t.displayed(closed)
        assertTrue(t.keepsDrawn("shipping.body"), "fading out")
        t.onFrame(0)
        t.onFrame(150 * ms)
        val mid = t.displayed(closed)
        val h = mid.local.getValue("shipping.body").height
        assertTrue(h > 0.0 && h < 120.0)
        // The disappearing content keeps its frames (covered by the moving edge, not squeezed).
        assertEquals(120.0, mid.local.getValue("shipping.text").height, 0.01)
        t.onFrame(400 * ms)
        assertFalse(t.running)
        assertFalse(t.keepsDrawn("shipping.body"), "hidden at the end: not drawn")
        assertEquals(0.0, t.displayed(closed).local.getValue("shipping.body").height)
        assertEquals(0f, t.alpha("shipping.body"))
        assertEquals(150.0, t.displayed(closed).contentSizes.getValue("scroll").height, 0.01)
    }

    @Test
    fun `a change while running continues from the frames drawn now`() {
        val t = transition()
        t.displayed(solve(false))
        val open = solve(true)
        t.displayed(open)
        t.onFrame(0)
        t.onFrame(100 * ms)
        val h = t.displayed(open).local.getValue("shipping.body").height
        val alpha = t.alpha("shipping.body")
        val closed = solve(false)
        t.displayed(closed)
        assertEquals(h, t.displayed(closed).local.getValue("shipping.body").height, 0.01, "no jump on reversal")
        assertEquals(alpha, t.alpha("shipping.body"), 0.001f)
        t.onFrame(1_000 * ms)
        t.onFrame(1_100 * ms)
        t.onFrame(1_400 * ms)
        assertFalse(t.running)
        assertEquals(0.0, t.displayed(closed).local.getValue("shipping.body").height)
    }

    @Test
    fun `two rows opening one after another animate together`() {
        val t = transition()
        t.displayed(solve(false))
        val one = solve(true)
        t.displayed(one)
        t.onFrame(0)
        t.onFrame(100 * ms)
        val both = solve(true, returnsOpen = true)
        val before = t.displayed(one).local.getValue("care.header").y
        t.displayed(both)
        assertEquals(before, t.displayed(both).local.getValue("care.header").y, 0.01)
        t.onFrame(200 * ms)
        t.onFrame(600 * ms)
        assertEquals(340.0, t.displayed(both).local.getValue("care.header").y, 0.01)
    }

    @Test
    fun `reduce motion applies the change at once`() {
        val t = transition(reduceMotion = true)
        t.displayed(solve(false))
        val open = solve(true)
        assertEquals(120.0, t.displayed(open).local.getValue("shipping.body").height, 0.01)
        assertFalse(t.running)
    }
}
