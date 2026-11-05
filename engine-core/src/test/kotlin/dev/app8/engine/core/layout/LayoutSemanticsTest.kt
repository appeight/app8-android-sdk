package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_X
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_Y
import dev.app8.engine.core.layout.LayoutAttribute.HEIGHT
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.TRAILING
import dev.app8.engine.core.layout.LayoutAttribute.WIDTH
import dev.app8.engine.core.layout.LayoutRelation.GREATER_OR_EQUAL
import dev.app8.engine.core.layout.LayoutRelation.LESS_OR_EQUAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BaseView.makeConstraints semantics (app8-ios-sdk) and UIKit behaviour. */
class LayoutSemanticsTest {

    // region dimensions

    @Test
    fun fixedDimensionsCenteredInSuperview() {
        val box = LayoutNode(
            "box",
            layout = Layout(width = fixed(200), height = fixed(50), constraints = listOf(c(CENTER_X, "superview"), c(CENTER_Y, "superview"))),
        )
        val r = solveLayout(screen(box), PHONE, SAFE)
        assertEquals(frame(101, 412, 200, 50), r["box"])
    }

    @Test
    fun fractionDimensionsAreMultipliersOfTheSuperview() {
        val card = LayoutNode(
            "card",
            layout = Layout(leading = fixed(20), top = fixed(10), width = fixed(300), height = fixed(400)),
            children = listOf(
                LayoutNode("half", layout = Layout(leading = fixed(0), top = fixed(0), width = Dimension.Fraction(0.5), height = Dimension.Fraction(0.25))),
            ),
        )
        val r = solveLayout(screen(card), PHONE)
        assertEquals(frame(20, 10, 150, 100), r["half"])
    }

    @Test
    fun fractionalEdgesAreIgnoredAsOnIOS() {
        val v = LayoutNode("v", layout = Layout(leading = Dimension.Fraction(0.5), top = fixed(0), width = fixed(10), height = fixed(10)))
        assertEquals(frame(0, 0, 10, 10), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun expressionDimensionsUseTheResolver() {
        val v = LayoutNode(
            "v",
            layout = Layout(leading = fixed(0), top = fixed(0), width = Dimension.Expression("{{w}}"), height = Dimension.Expression("{{unknown}}")),
        )
        val r = solveLayout(screen(v), PHONE, resolveExpression = { if (it == "{{w}}") 120.0 else null })
        // Unresolved expressions are not applied (the SDK ignores them): zero height.
        assertEquals(frame(0, 0, 120, 0), r["v"])
        // Without a resolver both are ignored.
        assertEquals(frame(0, 0, 0, 0), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun selfDimensionClampWithLowPriorityPreference() {
        // { height >= 16 } + { height = 50 @defaultLow }: 50 with room, the floor under pressure.
        fun spacerLayout(vararg extra: LayoutConstraint) = Layout(
            leading = fixed(0), trailing = fixed(0),
            constraints = listOf(
                c(HEIGHT, constant = 16, op = GREATER_OR_EQUAL),
                c(HEIGHT, constant = 50, priority = LayoutPriority.DEFAULT_LOW),
            ) + extra,
        )
        val top = LayoutNode("a", id = "a", layout = Layout(top = fixed(0), leading = fixed(0), trailing = fixed(0), height = fixed(100)))
        val spacer = LayoutNode("sp", id = "sp", layout = spacerLayout(c(TOP, "a", attribute = BOTTOM)))
        val bottom = LayoutNode(
            "b",
            layout = Layout(
                leading = fixed(0), trailing = fixed(0),
                constraints = listOf(
                    c(TOP, "sp", attribute = BOTTOM),
                    c(HEIGHT, constant = 80, priority = LayoutPriority.DEFAULT_HIGH),
                    c(BOTTOM, "superview", op = LESS_OR_EQUAL),
                ),
            ),
        )
        fun solve(h: Double) = solveLayout(screen(top, spacer, bottom), LayoutBounds(320.0, h))
        solve(400.0).let {
            assertEquals(50.0, it["sp"]!!.height)
            assertEquals(frame(0, 150, 320, 80), it["b"])
        }
        solve(200.0).let {
            assertEquals(20.0, it["sp"]!!.height)
            assertEquals(80.0, it["b"]!!.height)
        }
        // The spacer stops at its floor; the defaultHigh height gives way.
        solve(150.0).let {
            assertEquals(16.0, it["sp"]!!.height)
            assertEquals(frame(0, 116, 320, 34), it["b"])
        }
    }

    // endregion

    // region relations

    @Test
    fun siblingAnchorsAndBareIds() {
        val avatar = LayoutNode("av", id = "avatar", layout = Layout(leading = fixed(16), top = fixed(100), width = fixed(40), height = fixed(40)))
        val name = label(
            "name", 80.0, id = "name",
            layout = Layout(constraints = listOf(c(LEADING, "sibling(avatar)", attribute = TRAILING, constant = 12), c(CENTER_Y, "sibling(avatar)"))),
        )
        val badge = label(
            "badge", 30.0,
            layout = Layout(constraints = listOf(c(LEADING, "name", attribute = TRAILING, constant = 8), c(TOP, "name"))),
        )
        val r = solveLayout(screen(avatar, name, badge), PHONE, SAFE)
        assertEquals(frame(68, 110, 80, 20), r["name"])
        assertEquals(frame(156, 110, 30, 20), r["badge"])
    }

    @Test
    fun aspectRatioThroughSelfTargetMultiplier() {
        val image = LayoutNode(
            "img",
            layout = Layout(
                leading = fixed(16), trailing = fixed(-16), top = fixed(0),
                constraints = listOf(c(HEIGHT, "self", attribute = WIDTH, multiplier = 0.5)),
            ),
        )
        val square = LayoutNode(
            "sq",
            layout = Layout(
                top = fixed(300), leading = fixed(0), height = fixed(90),
                constraints = listOf(c(WIDTH, "self", attribute = HEIGHT)),
            ),
        )
        val r = solveLayout(screen(image, square), PHONE)
        assertEquals(frame(16, 0, 370, 185), r["img"])
        assertEquals(frame(0, 300, 90, 90), r["sq"])
    }

    @Test
    fun multiplierAgainstSiblingDimensions() {
        val a = LayoutNode("a", id = "a", layout = Layout(leading = fixed(0), top = fixed(0), width = fixed(100), height = fixed(40)))
        val b = LayoutNode(
            "b",
            layout = Layout(
                leading = fixed(0), top = fixed(100),
                constraints = listOf(c(WIDTH, "a", multiplier = 1.5, constant = 10), c(HEIGHT, "a", attribute = WIDTH, multiplier = 0.25)),
            ),
        )
        assertEquals(frame(0, 100, 160, 25), solveLayout(screen(a, b), PHONE)["b"])
    }

    @Test
    fun multipliersOnPositionAnchorsAreIgnoredAsOnIOS() {
        val v = LayoutNode(
            "v",
            layout = Layout(width = fixed(10), height = fixed(10), constraints = listOf(c(LEADING, "superview", constant = 10, multiplier = 2), c(TOP, "superview", multiplier = 3))),
        )
        assertEquals(frame(10, 0, 10, 10), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun unsupportedAnchorPairsFallBackToLikeToLike() {
        val a = LayoutNode("a", id = "a", layout = Layout(leading = fixed(50), top = fixed(70), width = fixed(10), height = fixed(10)))
        val b = LayoutNode(
            "b",
            layout = Layout(width = fixed(10), height = fixed(10), constraints = listOf(c(LEADING, "a", attribute = TOP), c(CENTER_X, "a", attribute = CENTER_Y, constant = 0), c(TOP, "a", attribute = CENTER_X))),
        )
        // leading→top becomes leading→leading; top→centerX becomes top→top.
        assertEquals(frame(50, 70, 10, 10), solveLayout(screen(a, b), PHONE)["b"])
    }

    @Test
    fun centerAnchorsAgainstEdges() {
        val v = LayoutNode(
            "v",
            layout = Layout(width = fixed(20), height = fixed(20), constraints = listOf(c(CENTER_X, "superview", attribute = TRAILING, constant = -30), c(CENTER_Y, "superview", attribute = TOP, constant = 100))),
        )
        assertEquals(frame(362, 90, 20, 20), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun missingTargetsAreSkipped() {
        val v = LayoutNode(
            "v",
            layout = Layout(leading = fixed(5), top = fixed(5), width = fixed(10), height = fixed(10), constraints = listOf(c(TOP, "nobody", constant = 300), c(TOP, "safeArea(nobody)", constant = 300))),
        )
        assertEquals(frame(5, 5, 10, 10), solveLayout(screen(v), PHONE)["v"])
    }

    @Test
    fun underConstrainedViewsCollapseAtTheSuperviewOrigin() {
        val card = LayoutNode(
            "card", layout = Layout(leading = fixed(30), top = fixed(40), width = fixed(100), height = fixed(100)),
            children = listOf(LayoutNode("loose", layout = Layout(constraints = listOf(c(WIDTH, constant = 20, op = GREATER_OR_EQUAL))))),
        )
        assertEquals(frame(30, 40, 20, 0), solveLayout(screen(card), PHONE)["loose"])
    }

    // endregion

    // region priorities

    @Test
    fun higherPriorityWinsAndIsNeverOutvoted() {
        val v = LayoutNode(
            "v",
            layout = Layout(
                leading = fixed(0), top = fixed(0), height = fixed(10),
                constraints = listOf(
                    c(WIDTH, constant = 100, priority = 251f),
                    c(WIDTH, constant = 200, priority = 250f),
                    c(WIDTH, constant = 210, priority = 250f),
                    c(WIDTH, constant = 220, priority = 250f),
                ),
            ),
        )
        assertEquals(100.0, solveLayout(screen(v), PHONE)["v"]!!.width)
    }

    @Test
    fun inequalityBeatsLowerPriorityEquality() {
        val v = LayoutNode(
            "v",
            layout = Layout(
                leading = fixed(0), top = fixed(0), height = fixed(10),
                constraints = listOf(
                    c(WIDTH, constant = 500, priority = LayoutPriority.DEFAULT_HIGH),
                    c(WIDTH, constant = 300, op = LESS_OR_EQUAL),
                ),
            ),
        )
        assertEquals(300.0, solveLayout(screen(v), PHONE)["v"]!!.width)
    }

    @Test
    fun conflictingRequiredConstraintsBreakTheLaterOne() {
        val v = LayoutNode(
            "v",
            layout = Layout(leading = fixed(0), top = fixed(0), height = fixed(10), width = fixed(100), constraints = listOf(c(WIDTH, constant = 200))),
        )
        assertEquals(100.0, solveLayout(screen(v), PHONE)["v"]!!.width)
    }

    @Test
    fun namedPriorities() {
        assertEquals(1000f, LayoutPriority.named("required"))
        assertEquals(750f, LayoutPriority.named("defaultHigh"))
        assertEquals(250f, LayoutPriority.named("defaultLow"))
        assertEquals(50f, LayoutPriority.named("fittingSizeLevel"))
        assertEquals(510f, LayoutPriority.named("dragThatCanResizeScene"))
        assertEquals(500f, LayoutPriority.named("sceneSizeStayPut"))
        assertEquals(490f, LayoutPriority.named("dragThatCannotResizeScene"))
        assertNull(LayoutPriority.named("high"))
        assertEquals(1000f, LayoutPriority.clamp(5000f))
        assertEquals(1f, LayoutPriority.clamp(0f))
    }

    // endregion

    // region hugging and compression resistance

    /** Two labels side by side between the superview edges. */
    private fun row(width: Double, aLayout: Layout? = null, bLayout: Layout? = null): LayoutResult {
        val a = label("a", 100.0, id = "a", layout = (aLayout ?: Layout()).copy(constraints = listOf(c(LEADING, "superview"), c(TOP, "superview"))))
        val b = label(
            "b", 100.0,
            layout = (bLayout ?: Layout()).copy(constraints = listOf(c(LEADING, "a", attribute = TRAILING), c(TRAILING, "superview"), c(TOP, "superview"))),
        )
        return solveLayout(screen(a, b), LayoutBounds(width, 100.0))
    }

    @Test
    fun lowerHuggingStretches() {
        row(300.0, bLayout = Layout(contentHuggingPriority = AxisPriority(h = 250f))).let {
            assertEquals(100.0, it["a"]!!.width)
            assertEquals(200.0, it["b"]!!.width)
        }
        row(300.0, aLayout = Layout(contentHuggingPriority = AxisPriority(h = 250f))).let {
            assertEquals(200.0, it["a"]!!.width)
            assertEquals(100.0, it["b"]!!.width)
        }
    }

    @Test
    fun lowerCompressionResistanceShrinks() {
        row(150.0, bLayout = Layout(contentCompressionResistancePriority = AxisPriority(h = 749f))).let {
            assertEquals(100.0, it["a"]!!.width)
            assertEquals(50.0, it["b"]!!.width)
        }
        row(150.0, aLayout = Layout(contentCompressionResistancePriority = AxisPriority(h = 749f))).let {
            assertEquals(50.0, it["a"]!!.width)
            assertEquals(100.0, it["b"]!!.width)
        }
    }

    @Test
    fun huggingOnlyAppliesToTheOverriddenAxis() {
        val r = row(300.0, bLayout = Layout(contentHuggingPriority = AxisPriority(h = 250f)))
        // Vertical hugging stays at the label default: intrinsic height.
        assertEquals(20.0, r["b"]!!.height)
    }

    @Test
    fun requiredCompressionResistanceOverflowsInsteadOfShrinking() {
        val v = label("v", 500.0, layout = Layout(constraints = listOf(c(LEADING, "superview"), c(TRAILING, "superview", op = LESS_OR_EQUAL, priority = 999f), c(TOP, "superview")), contentCompressionResistancePriority = AxisPriority(h = 1000f)))
        assertEquals(500.0, solveLayout(screen(v), LayoutBounds(300.0, 100.0))["v"]!!.width)
    }

    // endregion

    // region safe area and keyboard

    @Test
    fun ignoresSafeAreaShiftsTopConstraintsToTheSuperview() {
        val hero = LayoutNode(
            "hero",
            layout = Layout(
                ignoresSafeArea = true, height = fixed(300),
                constraints = listOf(c(TOP, "superview", constant = 10), c(LEADING, "superview"), c(TRAILING, "superview")),
            ),
        )
        val plain = LayoutNode("plain", layout = Layout(top = fixed(0), leading = fixed(0), width = fixed(10), height = fixed(10)))
        val r = solveLayout(screen(hero, plain), PHONE, SAFE)
        assertEquals(-52.0, r["hero"]!!.y)
        // Without ignoresSafeArea, top is the raw superview edge.
        assertEquals(0.0, r["plain"]!!.y)
    }

    @Test
    fun layoutWithOnlyIgnoresSafeAreaIsNotEmpty() {
        assertTrue(Layout().isEmpty)
        assertTrue(!Layout(ignoresSafeArea = true).isEmpty)
        assertTrue(!Layout(contentHuggingPriority = AxisPriority(v = 1f)).isEmpty)
        // So it does not fill the superview (as on iOS): nothing positions it.
        val r = solveLayout(screen(LayoutNode("v", layout = Layout(ignoresSafeArea = true))), PHONE, SAFE)
        assertEquals(frame(0, 0, 0, 0), r["v"])
    }

    @Test
    fun nestedViewsGetTheirOverlapWithTheSafeArea() {
        val container = LayoutNode(
            "container", // fills the screen
            children = listOf(
                LayoutNode("title", layout = Layout(height = fixed(44), constraints = listOf(c(TOP, "safeArea"), c(LEADING, "safeArea"), c(TRAILING, "safeArea")))),
                LayoutNode("bar", layout = Layout(height = fixed(50), constraints = listOf(c(BOTTOM, "safeArea"), c(LEADING, "superview"), c(TRAILING, "superview")))),
                LayoutNode("bleed", layout = Layout(top = fixed(0), leading = fixed(0), width = fixed(10), height = fixed(10), ignoresSafeArea = true)),
            ),
        )
        val r = solveLayout(screen(container), PHONE, SAFE)
        assertEquals(frame(0, 62, 402, 44), r["title"])
        assertEquals(frame(0, 790, 402, 50), r["bar"])
        assertEquals(-62.0, r["bleed"]!!.y)
        assertTrue(r.passes >= 2)
    }

    @Test
    fun viewsInsideTheSafeAreaHaveNoInsets() {
        val card = LayoutNode(
            "card", layout = Layout(top = fixed(200), leading = fixed(0), trailing = fixed(0), height = fixed(300)),
            children = listOf(LayoutNode("inner", layout = Layout(height = fixed(10), constraints = listOf(c(TOP, "safeArea"), c(LEADING, "superview"), c(TRAILING, "superview"))))),
        )
        assertEquals(200.0, solveLayout(screen(card), PHONE, SAFE)["inner"]!!.y)
    }

    @Test
    fun safeAreaOfASibling() {
        val header = LayoutNode("header", id = "headerBar", layout = Layout(top = fixed(0), leading = fixed(0), trailing = fixed(0), height = fixed(120)))
        val below = LayoutNode(
            "below",
            layout = Layout(
                height = fixed(10),
                constraints = listOf(
                    c(TOP, "safeArea(headerBar)", attribute = TOP), // the header's guide starts below the status bar
                    c(LEADING, "safeArea( headerBar )"),
                    c(WIDTH, "safeArea(headerBar)", multiplier = 0.5), // guides ignore multipliers
                ),
            ),
        )
        val r = solveLayout(screen(header, below), PHONE, SAFE)
        assertEquals(frame(0, 62, 402, 10), r["below"])
    }

    @Test
    fun unsupportedGuidePairsBecomeBottomToGuideBottom() {
        val v = LayoutNode("v", layout = Layout(height = fixed(10), width = fixed(10), constraints = listOf(c(CENTER_X, "safeArea", attribute = LEADING, constant = -5))))
        assertEquals(874.0 - 34 - 5, solveLayout(screen(v), PHONE, SAFE)["v"]!!.maxY)
    }

    @Test
    fun keyboardTarget() {
        fun button(ignores: Boolean? = null) = LayoutNode(
            "btn",
            layout = Layout(leading = fixed(20), trailing = fixed(-20), height = fixed(50), ignoresSafeArea = ignores, constraints = listOf(c(BOTTOM, "keyboard", constant = -16))),
        )
        // Hidden keyboard: the safe-area bottom.
        assertEquals(874.0 - 34 - 16, solveLayout(screen(button()), PHONE, SAFE)["btn"]!!.maxY)
        // Shown: above the keyboard.
        assertEquals(874.0 - 300 - 16, solveLayout(screen(button()), PHONE, SAFE, keyboardHeight = 300.0)["btn"]!!.maxY)
        // ignoresSafeArea tracks the container's bottom edge.
        assertEquals(874.0 - 16, solveLayout(screen(button(true)), PHONE, SAFE)["btn"]!!.maxY)
    }

    // endregion

    // region scroll views and roots

    @Test
    fun scrollContentIsAsWideAsTheScrollViewAndAsTallAsItsContent() {
        val scroll = LayoutNode(
            "scroll", scroll = ScrollSpec(),
            children = listOf(
                LayoutNode("first", id = "first", layout = Layout(top = fixed(20), leading = fixed(16), trailing = fixed(-16), height = fixed(1200))),
                label("last", 80.0, layout = Layout(constraints = listOf(c(TOP, "first", attribute = BOTTOM, constant = 10), c(LEADING, "superview", constant = 16), c(BOTTOM, "superview", constant = -30)))),
            ),
        )
        val r = solveLayout(screen(scroll), PHONE, SAFE)
        assertEquals(frame(0, 0, 402, 874), r["scroll"])
        assertEquals(Size(402.0, 20.0 + 1200 + 10 + 20 + 30), r.contentSizes["scroll"])
        assertEquals(frame(16, 1230, 80, 20), r["last"])
    }

    @Test
    fun horizontalScrollContent() {
        val scroll = LayoutNode(
            "scroll", scroll = ScrollSpec(ScrollDirection.HORIZONTAL),
            layout = Layout(top = fixed(100), leading = fixed(0), trailing = fixed(0), height = fixed(80)),
            children = listOf(
                LayoutNode("a", id = "a", layout = Layout(leading = fixed(0), top = fixed(0), bottom = fixed(0), width = fixed(300))),
                LayoutNode("b", layout = Layout(top = fixed(0), bottom = fixed(0), width = fixed(300), constraints = listOf(c(LEADING, "a", attribute = TRAILING), c(TRAILING, "superview")))),
            ),
        )
        val r = solveLayout(screen(scroll), PHONE)
        assertEquals(Size(600.0, 80.0), r.contentSizes["scroll"])
        assertEquals(frame(300, 100, 300, 80), r["b"])
        assertEquals(frame(300, 0, 300, 80), r.localFrame("b"))
    }

    @Test
    fun contentSizedRoot() {
        val cell = LayoutNode(
            "cell",
            children = listOf(
                label("t", 100.0, id = "t", layout = Layout(top = fixed(12), leading = fixed(16))),
                label("s", 100.0, layout = Layout(leading = fixed(16), bottom = fixed(-12), constraints = listOf(c(TOP, "t", attribute = BOTTOM, constant = 4)))),
            ),
        )
        val r = solveLayout(cell, LayoutBounds(width = 375.0, height = null))
        assertEquals(12.0 + 20 + 4 + 20 + 12, r.contentHeight)
        assertEquals(frame(0, 0, 375, 68), r["cell"])
    }

    @Test
    fun localFramesAreRelativeToTheParent() {
        val card = LayoutNode(
            "card", layout = Layout(leading = fixed(30), top = fixed(40), width = fixed(100), height = fixed(100)),
            children = listOf(LayoutNode("dot", layout = Layout(leading = fixed(5), top = fixed(6), width = fixed(1), height = fixed(1)))),
        )
        val r = solveLayout(screen(card), PHONE)
        assertEquals(frame(35, 46, 1, 1), r["dot"])
        assertEquals(frame(5, 6, 1, 1), r.localFrame("dot"))
    }

    @Test
    fun framesSnapToThePixelGrid() {
        val v = LayoutNode("v", layout = Layout(leading = fixed(0), top = fixed(0), width = Dimension.Fraction(1.0 / 7), height = fixed(10)))
        assertEquals(172.0 / 3, solveLayout(screen(v), PHONE)["v"]!!.width, 1e-9)
        assertEquals(402.0 / 7, solveLayout(screen(v), PHONE, scale = 0.0)["v"]!!.width, 1e-9)
        assertEquals(57.5, solveLayout(screen(v), PHONE, scale = 2.0)["v"]!!.width)
    }

    // endregion

    @Test
    fun duplicateKeysAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { solveLayout(screen(LayoutNode("a"), LayoutNode("a")), PHONE) }
    }

    @Test
    fun targetParsingMatchesTheIOSDecoder() {
        assertEquals(ConstraintTarget.Superview, ConstraintTarget.parse("superView"))
        assertEquals(ConstraintTarget.Keyboard, ConstraintTarget.parse("keyboard"))
        assertEquals(ConstraintTarget.SelfView, ConstraintTarget.parse("self"))
        assertEquals(ConstraintTarget.SafeArea(null), ConstraintTarget.parse("safeArea"))
        assertEquals(ConstraintTarget.SafeArea("Header"), ConstraintTarget.parse(" SafeArea( Header ) "))
        assertEquals(ConstraintTarget.Sibling("title"), ConstraintTarget.parse("sibling(title)"))
        assertEquals(ConstraintTarget.Sibling("title"), ConstraintTarget.parse("title"))
        assertEquals(ConstraintTarget.Sibling("safeArea()"), ConstraintTarget.parse("safeArea()"))
    }
}
