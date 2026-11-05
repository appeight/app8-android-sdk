package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.CENTER_X
import dev.app8.engine.core.layout.LayoutAttribute.HEIGHT
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.WIDTH
import dev.app8.engine.core.layout.LayoutRelation.GREATER_OR_EQUAL
import kotlin.test.Test
import kotlin.test.assertEquals

/** UIStackView semantics: every distribution × alignment on both axes, plus hiding and sizing. */
class StackViewLayoutTest {

    private val mainSizes = listOf(40.0, 60.0, 80.0)
    private val crossSizes = listOf(20.0, 30.0, 40.0)

    /** Expected (position, size) along the axis in a 300pt stack with spacing 10. */
    private fun expectedMain(d: StackDistribution): List<Pair<Double, Double>> {
        fun packed(sizes: List<Double>): List<Pair<Double, Double>> {
            var p = 0.0
            return sizes.map { s -> (p to s).also { p += s + 10 } }
        }
        return when (d) {
            // Equal hugging: the first arranged subview takes the slack.
            StackDistribution.FILL -> packed(listOf(140.0, 60.0, 80.0))
            StackDistribution.FILL_EQUALLY -> packed(List(3) { 280.0 / 3 })
            StackDistribution.FILL_PROPORTIONALLY -> packed(mainSizes.map { it / 180 * 280 })
            StackDistribution.EQUAL_SPACING -> listOf(0.0 to 40.0, 100.0 to 60.0, 220.0 to 80.0)
            // Centers at 20, 140, 260.
            StackDistribution.EQUAL_CENTERING -> listOf(0.0 to 40.0, 110.0 to 60.0, 220.0 to 80.0)
        }
    }

    /** Expected (position, size) across the axis in a 100pt stack. */
    private fun expectedCross(a: StackAlignment, size: Double): Pair<Double, Double> = when (a) {
        StackAlignment.FILL -> 0.0 to 100.0
        StackAlignment.LEADING -> 0.0 to size
        StackAlignment.CENTER -> (50 - size / 2) to size
        StackAlignment.TRAILING -> (100 - size) to size
    }

    private fun matrixStack(axis: StackAxis, d: StackDistribution, a: StackAlignment): LayoutNode {
        val vertical = axis == StackAxis.VERTICAL
        val items = mainSizes.indices.map { i ->
            val size = if (vertical) Size(crossSizes[i], mainSizes[i]) else Size(mainSizes[i], crossSizes[i])
            LayoutNode("i$i", intrinsicSize = { size }, hugging = LABEL_HUGGING)
        }
        return LayoutNode(
            "stack",
            stack = StackSpec(axis, 10.0, a, d),
            layout = Layout(
                leading = fixed(10), top = fixed(20),
                width = fixed(if (vertical) 100 else 300), height = fixed(if (vertical) 300 else 100),
            ),
            children = items,
        )
    }

    @Test
    fun everyDistributionAndAlignmentOnBothAxes() {
        for (axis in StackAxis.entries) for (d in StackDistribution.entries) for (a in StackAlignment.entries) {
            val r = solveLayout(screen(matrixStack(axis, d, a)), PHONE)
            val main = expectedMain(d)
            for (i in 0..2) {
                val (mp, ms) = main[i]
                val (cp, cs) = expectedCross(a, crossSizes[i])
                val expected = if (axis == StackAxis.HORIZONTAL) {
                    frame(10 + mp, 20 + cp, ms, cs)
                } else {
                    frame(10 + cp, 20 + mp, cs, ms)
                }
                assertFrame(expected, r["i$i"], "$axis $d $a item $i")
            }
        }
    }

    @Test
    fun alignmentParsingFollowsTheIOSViewModel() {
        assertEquals(StackAlignment.LEADING, StackAlignment.parse("top"))
        assertEquals(StackAlignment.TRAILING, StackAlignment.parse("bottom"))
        assertEquals(StackAlignment.CENTER, StackAlignment.parse("center"))
        assertEquals(StackAlignment.FILL, StackAlignment.parse("firstBaseline"))
        assertEquals(StackAlignment.FILL, StackAlignment.parse(null))
        assertEquals(StackDistribution.EQUAL_CENTERING, StackDistribution.parse("equalCentering"))
        assertEquals(StackDistribution.FILL, StackDistribution.parse("bogus"))
    }

    @Test
    fun contentSizedStackHugsItsArrangedSubviews() {
        for (a in StackAlignment.entries) {
            val stack = LayoutNode(
                "s", stack = StackSpec(StackAxis.HORIZONTAL, 10.0, a),
                layout = Layout(leading = fixed(0), top = fixed(0)),
                children = mainSizes.indices.map { label("i$it", mainSizes[it], crossSizes[it]) },
            )
            val r = solveLayout(screen(stack), PHONE)
            assertEquals(frame(0, 0, 200, 40), r["s"], "$a")
            // The 60×30 item in a 40pt-tall stack.
            val expected = when (a) {
                StackAlignment.FILL -> frame(50, 0, 60, 40)
                StackAlignment.LEADING -> frame(50, 0, 60, 30)
                StackAlignment.CENTER -> frame(50, 5, 60, 30)
                StackAlignment.TRAILING -> frame(50, 10, 60, 30)
            }
            assertFrame(expected, r["i1"], "$a")
        }
    }

    @Test
    fun hiddenArrangedSubviewsCollapseWithTheirSpacing() {
        for (d in listOf(StackDistribution.FILL, StackDistribution.EQUAL_SPACING, StackDistribution.FILL_PROPORTIONALLY)) {
            val stack = LayoutNode(
                "s", stack = StackSpec(StackAxis.HORIZONTAL, 10.0, StackAlignment.CENTER, d),
                layout = Layout(leading = fixed(0), top = fixed(0)),
                children = listOf(
                    label("a", 40.0),
                    label("b", 60.0, hidden = true, layout = Layout(width = fixed(60))),
                    label("c", 80.0),
                ),
            )
            val r = solveLayout(screen(stack), PHONE)
            assertEquals(130.0, r["s"]!!.width, "$d")
            assertEquals(50.0, r["c"]!!.x, "$d")
            assertEquals(0.0, r["b"]!!.width, "$d")
        }
    }

    @Test
    fun hiddenViewsOutsideStacksKeepTheirFrame() {
        val v = LayoutNode("v", hidden = true, layout = Layout(leading = fixed(1), top = fixed(2), width = fixed(3), height = fixed(4)))
        assertEquals(frame(1, 2, 3, 4), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun emptyStackCollapses() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.VERTICAL),
            layout = Layout(leading = fixed(0), trailing = fixed(0), top = fixed(10)),
            children = listOf(label("gone", 50.0, hidden = true)),
        )
        assertEquals(frame(0, 10, 402, 0), solveLayout(screen(stack), PHONE)["s"])
    }

    @Test
    fun fillStretchesTheLowestHugging() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.HORIZONTAL),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(300)),
            children = listOf(
                label("a", 100.0),
                label("b", 100.0, layout = Layout(contentHuggingPriority = AxisPriority(h = LayoutPriority.DEFAULT_LOW))),
            ),
        )
        val r = solveLayout(screen(stack), PHONE)
        assertEquals(frame(0, 0, 100, 20), r["a"])
        assertEquals(frame(100, 0, 200, 20), r["b"])
    }

    @Test
    fun fillShrinksTheLowestCompressionResistance() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.HORIZONTAL, 10.0),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(150)),
            children = listOf(
                label("a", 100.0, layout = Layout(contentCompressionResistancePriority = AxisPriority(h = 749f))),
                label("b", 100.0),
            ),
        )
        val r = solveLayout(screen(stack), PHONE)
        assertEquals(40.0, r["a"]!!.width)
        assertEquals(frame(50, 0, 100, 20), r["b"])
    }

    @Test
    fun viewWithoutIntrinsicSizeTakesTheSlack() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.VERTICAL),
            layout = Layout(leading = fixed(0), trailing = fixed(0), top = fixed(0), bottom = fixed(0)),
            children = listOf(label("title", 100.0), LayoutNode("flex"), label("footer", 100.0)),
        )
        val r = solveLayout(screen(stack), PHONE)
        assertEquals(frame(0, 20, 402, 834), r["flex"])
        assertEquals(frame(0, 854, 402, 20), r["footer"])
    }

    @Test
    fun arrangedSubviewsOnlyApplySizeConstraints() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.VERTICAL, alignment = StackAlignment.LEADING),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(200)),
            children = listOf(
                LayoutNode(
                    "a",
                    layout = Layout(
                        width = Dimension.Fraction(0.5), height = fixed(30), top = fixed(500), leading = fixed(70),
                        constraints = listOf(c(TOP, "superview", constant = 400), c(CENTER_X, "superview")),
                    ),
                ),
            ),
        )
        assertEquals(frame(0, 0, 100, 30), solveLayout(screen(stack), PHONE)["a"])
    }

    @Test
    fun flexibleSpacersInLockstep() {
        // docs/layout.md: spacers with a floor, a low-priority preference and sibling equality.
        fun spacer(key: String, vararg extra: LayoutConstraint) = LayoutNode(
            key, id = key,
            layout = Layout(constraints = listOf(c(HEIGHT, constant = 16, op = GREATER_OR_EQUAL), c(HEIGHT, constant = 50, priority = LayoutPriority.DEFAULT_LOW)) + extra),
        )
        fun solve(height: Double) = solveLayout(
            screen(
                LayoutNode(
                    "s", stack = StackSpec(StackAxis.VERTICAL),
                    layout = Layout(leading = fixed(0), trailing = fixed(0), top = fixed(0), height = fixed(height)),
                    children = listOf(
                        label("header", 100.0, height = 100.0, layout = Layout(contentHuggingPriority = AxisPriority(v = LayoutPriority.DEFAULT_HIGH))),
                        spacer("spA"),
                        label("body", 100.0, height = 200.0, layout = Layout(contentHuggingPriority = AxisPriority(v = LayoutPriority.DEFAULT_HIGH))),
                        spacer("spB", c(HEIGHT, "spA", attribute = HEIGHT)),
                    ),
                ),
            ),
            PHONE,
        )
        solve(400.0).let {
            assertEquals(50.0, it["spA"]!!.height)
            assertEquals(50.0, it["spB"]!!.height)
        }
        solve(600.0).let {
            // Slack goes to the spacers (lowest priority), in lockstep.
            assertEquals(150.0, it["spA"]!!.height)
            assertEquals(150.0, it["spB"]!!.height)
            assertEquals(250.0, it["body"]!!.y)
        }
        solve(320.0).let {
            assertEquals(16.0, it["spA"]!!.height)
            assertEquals(16.0, it["spB"]!!.height)
            // Then compression resistance gives; equal priorities shrink the first view first.
            assertEquals(88.0, it["header"]!!.height)
            assertEquals(200.0, it["body"]!!.height)
        }
    }

    @Test
    fun siblingWidthEqualityInsideAStack() {
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.HORIZONTAL, 8.0, StackAlignment.CENTER, StackDistribution.EQUAL_SPACING),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(300), height = fixed(60)),
            children = listOf(
                LayoutNode("a", id = "a", layout = Layout(width = fixed(70), height = fixed(20))),
                LayoutNode("b", layout = Layout(constraints = listOf(c(WIDTH, "a"), c(HEIGHT, "superview", multiplier = 0.5)))),
            ),
        )
        val r = solveLayout(screen(stack), PHONE)
        assertEquals(frame(230, 15, 70, 30), r["b"])
    }

    @Test
    fun nestedStacks() {
        val row = LayoutNode(
            "row", stack = StackSpec(StackAxis.HORIZONTAL, 12.0, StackAlignment.CENTER),
            children = listOf(
                LayoutNode("icon", layout = Layout(width = fixed(24), height = fixed(24))),
                LayoutNode(
                    "texts", stack = StackSpec(StackAxis.VERTICAL, 2.0),
                    children = listOf(label("title", 120.0), label("subtitle", 90.0, height = 16.0)),
                ),
            ),
        )
        val list = LayoutNode(
            "list", stack = StackSpec(StackAxis.VERTICAL, 8.0),
            layout = Layout(leading = fixed(16), trailing = fixed(-16), top = fixed(100)),
            children = listOf(row),
        )
        val r = solveLayout(screen(list), PHONE)
        assertEquals(frame(16, 100, 370, 38), r["list"])
        assertEquals(frame(16, 107, 24, 24), r["icon"])
        // The vertical stack fills the row's remaining width; its labels fill it too.
        assertEquals(frame(52, 100, 334, 38), r["texts"])
        assertEquals(frame(52, 122, 334, 16), r["subtitle"])
    }

    @Test
    fun largeScreenSolvesQuickly() {
        val rows = (0 until 150).map { i ->
            LayoutNode(
                "row$i", stack = StackSpec(StackAxis.HORIZONTAL, 8.0, StackAlignment.CENTER),
                children = listOf(
                    LayoutNode("row$i.icon", layout = Layout(width = fixed(24), height = fixed(24))),
                    LayoutNode(
                        "row$i.texts", stack = StackSpec(StackAxis.VERTICAL, 2.0),
                        children = listOf(label("row$i.title", 120.0), label("row$i.sub", 90.0, height = 16.0)),
                    ),
                ),
            )
        }
        val list = LayoutNode(
            "list", stack = StackSpec(StackAxis.VERTICAL, 8.0),
            layout = Layout(leading = fixed(16), trailing = fixed(-16), top = fixed(0), bottom = fixed(0)),
            children = rows,
        )
        val scroll = LayoutNode("scroll", scroll = ScrollSpec(), children = listOf(list))
        solveLayout(screen(scroll), PHONE, SAFE) // warm-up
        val start = System.nanoTime()
        val r = solveLayout(screen(scroll), PHONE, SAFE)
        val ms = (System.nanoTime() - start) / 1e6
        println("750-node screen: ${"%.1f".format(ms)} ms, ${r.passes} passes")
        assertEquals(150 * 38.0 + 149 * 8, r.contentSizes["scroll"]!!.height)
        kotlin.test.assertTrue(ms < 2000, "took $ms ms")
    }

    @Test
    fun stackChildrenCanTargetLeadingFromWidthAsLikeToLike() {
        // width → leading is not a supported pair: it relates width to width.
        val stack = LayoutNode(
            "s", stack = StackSpec(StackAxis.VERTICAL, alignment = StackAlignment.LEADING),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(200)),
            children = listOf(LayoutNode("a", layout = Layout(height = fixed(10), constraints = listOf(c(WIDTH, "superview", attribute = LEADING, multiplier = 0.25))))),
        )
        assertEquals(50.0, solveLayout(screen(stack), PHONE)["a"]!!.width)
    }

    @Test
    fun firstNestedStackTakesTheSlackInAFillStack() {
        // screens/StackViewProfileCard: two centre-aligned rows in a 76pt fill stack. UIKit
        // stretches the first row (index tie-break), like leaf views with equal hugging.
        fun row(k: String) = LayoutNode(
            k,
            stack = StackSpec(StackAxis.HORIZONTAL, 10.0, StackAlignment.CENTER, StackDistribution.FILL),
            children = listOf(
                LayoutNode("$k-icon", layout = Layout(width = fixed(16), height = fixed(16))),
                label("$k-text", 150.0, 20.0),
            ),
        )
        val info = LayoutNode(
            "info",
            stack = StackSpec(StackAxis.VERTICAL, 12.0, StackAlignment.FILL, StackDistribution.FILL),
            layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(320), height = fixed(76)),
            children = listOf(row("a"), row("b")),
        )
        val r = solveLayout(screen(info), PHONE)
        assertEquals(Frame(0.0, 0.0, 320.0, 44.0), r["a"])
        assertEquals(Frame(0.0, 56.0, 320.0, 20.0), r["b"])
        assertEquals(12.0, r["a-text"]!!.y)
    }
}
