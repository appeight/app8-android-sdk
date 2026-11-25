package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_X
import dev.app8.engine.core.layout.LayoutAttribute.CENTER_Y
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.TRAILING
import kotlin.test.Test

/**
 * layout.md, Constraint Structure: a multiplier on a position (edge or centre) scales the
 * target's position in the superview's bounds, as UIKit does (app8-ios-sdk
 * PositionMultiplierTests): `centerX` × 2 of the superview is its trailing edge. Before 0.7.17
 * the multiplier applied to width and height only, so markers at fractions all sat in the middle.
 */
class PositionMultiplierTest {

    private fun marker(key: String, vararg constraints: LayoutConstraint) =
        LayoutNode(key, id = key, layout = Layout(width = fixed(10), height = fixed(10), constraints = constraints.toList()))

    /** A 300 × 200 frame at (40, 100): positions are relative to it, not to the screen. */
    private fun solve(vararg markers: LayoutNode) = solveLayout(
        screen(
            LayoutNode(
                "frame",
                layout = Layout(leading = fixed(40), top = fixed(100), width = fixed(300), height = fixed(200)),
                children = markers.toList(),
            ),
        ),
        PHONE,
    )

    @Test
    fun centreMultipliersPlaceAtFractionsOfTheSuperview() {
        val r = solve(
            marker("q", c(CENTER_X, "superview", multiplier = 0.5), c(CENTER_Y, "superview", multiplier = 0.5)),
            marker("t", c(CENTER_X, "superview", multiplier = 1.6), c(CENTER_Y, "superview", multiplier = 1.2)),
            marker("e", c(CENTER_X, "superview", multiplier = 2), c(CENTER_Y, "superview", multiplier = 2)),
            marker("m", c(CENTER_X, "superview"), c(CENTER_Y, "superview")),
        )
        assertFrame(frame(40 + 75 - 5, 100 + 50 - 5, 10, 10), r["q"], "q")
        assertFrame(frame(40 + 240 - 5, 100 + 120 - 5, 10, 10), r["t"], "t")
        assertFrame(frame(40 + 300 - 5, 100 + 200 - 5, 10, 10), r["e"], "× 2: the bottom-trailing corner")
        assertFrame(frame(40 + 150 - 5, 100 + 100 - 5, 10, 10), r["m"], "no multiplier: unchanged")
    }

    @Test
    fun edgeMultiplierAndConstant() {
        val r = solve(
            marker(
                "l",
                c(LEADING, "superview", attribute = TRAILING, multiplier = 0.25, constant = 5),
                c(TOP, "superview", attribute = BOTTOM, multiplier = 0.5),
            ),
        )
        assertFrame(frame(40 + 80, 100 + 100, 10, 10), r["l"], "l")
    }

    @Test
    fun siblingTargetUsesTheSharedSuperviewsBounds() {
        val r = solve(
            marker("a", c(LEADING, "superview", constant = 100), c(TOP, "superview", constant = 20)),
            marker("b", c(CENTER_X, "sibling(a)", multiplier = 2), c(TOP, "sibling(a)", attribute = BOTTOM, multiplier = 3)),
        )
        assertFrame(frame(40 + 210 - 5, 100 + 90, 10, 10), r["b"], "b")
    }

    @Test
    fun zeroMultiplierOnAPositionIsIgnored() {
        val r = solve(marker("z", c(CENTER_X, "superview", multiplier = 0), c(CENTER_Y, "superview")))
        assertFrame(frame(40 + 150 - 5, 100 + 100 - 5, 10, 10), r["z"], "z")
    }
}
