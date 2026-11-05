package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.TRAILING
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of shoppu-web packages/app-dsl/src/layout/solver.test.ts. */
class WebParityTest {
    private fun textLabel(key: String, width: Double, id: String? = null, layout: Layout? = null) =
        LayoutNode(key = key, id = id, layout = layout, intrinsicSize = text(width), hugging = LABEL_HUGGING)

    @Test
    fun emptyLayoutFillsTheSuperview() {
        val r = solveLayout(screen(LayoutNode("a")), PHONE, SAFE)
        assertEquals(frame(0, 0, 402, 874), r["a"])
    }

    @Test
    fun rawUIKitEdgeConstantsAndSafeAreaTargets() {
        val title = textLabel(
            "t", 120.0, id = "cartTitle",
            layout = Layout(
                constraints = listOf(
                    c(TOP, "safeArea", constant = 16),
                    c(LEADING, "superview", constant = 16),
                    c(TRAILING, "superview", constant = -16),
                ),
            ),
        )
        val bar = LayoutNode(
            "b", id = "progress",
            layout = Layout(
                height = fixed(8),
                constraints = listOf(
                    c(TOP, "cartTitle", attribute = BOTTOM, constant = 14),
                    c(LEADING, "superview", constant = 16),
                    c(TRAILING, "superview", constant = -16),
                ),
            ),
        )
        val r = solveLayout(screen(title, bar), PHONE, SAFE)
        assertEquals(frame(16, 78, 370, 20), r["t"])
        assertEquals(frame(16, 112, 370, 8), r["b"])
    }

    @Test
    fun containerSizedByItsChildrenPinnedAboveTheBottomSafeArea() {
        val footer = LayoutNode(
            "f", id = "cartFooter",
            layout = Layout(
                constraints = listOf(
                    c(LEADING, "superview"),
                    c(TRAILING, "superview"),
                    c(BOTTOM, "safeArea", constant = -8),
                ),
            ),
            children = listOf(
                textLabel(
                    "f.l", 60.0, id = "subtotal",
                    layout = Layout(constraints = listOf(c(TOP, "superview", constant = 14), c(LEADING, "superview", constant = 16))),
                ),
                LayoutNode(
                    "f.b", id = "checkout",
                    layout = Layout(
                        height = fixed(52),
                        constraints = listOf(
                            c(TOP, "subtotal", attribute = BOTTOM, constant = 16),
                            c(LEADING, "superview", constant = 16),
                            c(TRAILING, "superview", constant = -16),
                            c(BOTTOM, "superview"),
                        ),
                    ),
                ),
            ),
        )
        val r = solveLayout(screen(footer), PHONE, SAFE)
        val f = r["f"]!!
        assertEquals(14.0 + 20 + 16 + 52, f.height)
        assertEquals(874.0 - 34 - 8, f.maxY)
        assertEquals(frame(16, f.y + 50, 370, 52), r["f.b"])
    }

    @Test
    fun multiLineLabelWrapsAtItsSolvedWidth() {
        val lbl = textLabel("l", 900.0, layout = Layout(leading = fixed(16), trailing = fixed(-16), top = fixed(0)))
        val r = solveLayout(screen(lbl), PHONE, SAFE)
        assertEquals(370.0, r["l"]!!.width)
        assertEquals(ceil(900.0 / 370) * 20, r["l"]!!.height)
    }

    @Test
    fun verticalStackSpacingFillAlignmentHuggingContent() {
        val stack = LayoutNode(
            "s",
            stack = StackSpec(StackAxis.VERTICAL, 10.0, StackAlignment.FILL, StackDistribution.FILL),
            layout = Layout(leading = fixed(20), trailing = fixed(-20), top = fixed(100)),
            children = listOf(
                textLabel("s.a", 50.0),
                LayoutNode("s.b", layout = Layout(height = fixed(44))),
                LayoutNode("s.c", hidden = true, layout = Layout(height = fixed(300))),
            ),
        )
        val r = solveLayout(screen(stack), PHONE, SAFE)
        assertEquals(frame(20, 100, 362, 74), r["s"])
        assertEquals(frame(20, 130, 362, 44), r["s.b"])
    }

    @Test
    fun ignoresSafeAreaLiftsATopShorthandByTheSafeAreaInset() {
        val hero = LayoutNode(
            "hero",
            layout = Layout(top = fixed(0), leading = fixed(0), trailing = fixed(0), height = fixed(300), ignoresSafeArea = true),
        )
        val r = solveLayout(screen(hero), PHONE, SAFE)
        assertEquals(-62.0, r["hero"]!!.y)
    }
}
