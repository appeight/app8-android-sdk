package dev.app8.engine.render.components

import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.render.Solved
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A collection's automatic safe-area inset (UIKit `.automatic`): a grid of cards under a
 * heading inside a vertical scroll view gets none, because the scroll view has already
 * inset its content; it used to add the safe area a second time (a 25–102dp gap).
 */
class NestedCollectionInsetTest {
    private val grid = """{"type":"collection","id":"grid","content":{"properties":{"layout":{"type":"grid","columns":2}}}}"""

    private fun screen(scrollAdjustment: String) = """{"type":"screen","id":"s","content":{"children":[
      {"type":"scrollView","id":"scroll","content":{"properties":{"contentInsetAdjustment":"$scrollAdjustment"},"children":[
        {"type":"label","id":"heading","content":{"properties":{"text":"Heading"}}},
        $grid
      ]}}]}}"""

    /** Pixel 9: status bar 54 + top app bar 64; the heading is 54.5 tall in the scroll content. */
    private fun solved(gridHeight: Double = 1200.0) = Solved(
        local = emptyMap(),
        absolute = mapOf(
            "s" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll.heading" to Frame(16.0, 0.0, 380.0, 54.5),
            "s.scroll.grid" to Frame(0.0, 54.5, 412.0, gridHeight),
        ),
        contentSizes = emptyMap(),
        safeArea = EdgeInsets(top = 118.0, bottom = 88.0),
    )

    @Test
    fun selfSizingGridInAScrollViewGetsNoAutomaticInset() {
        val node = session(runtime(mapOf("s" to screen("automatic")))).root.find("grid")!!
        assertEquals(true, CollectionKind.sizesToContent(node))
        assertEquals(0.0 to 0.0, CollectionKind.automaticInsets(node, solved(), selfSizing = true))
    }

    @Test
    fun scrollingCollectionInAScrollViewMeasuresFromItsOnScreenPosition() {
        // With its own height it scrolls itself; on screen it starts at 118 + 54.5, under nothing.
        val node = session(runtime(mapOf("s" to screen("automatic")))).root.find("grid")!!
        assertEquals(0.0, CollectionKind.automaticInsets(node, solved(400.0), selfSizing = false).first)
        // Under a scroll view that does not inset (content drawn under the bar), the
        // part of the safe area it covers is still inset: 118 - 54.5.
        val under = session(runtime(mapOf("s" to screen("never")))).root.find("grid")!!
        assertEquals(63.5, CollectionKind.automaticInsets(under, solved(400.0), selfSizing = false).first)
    }

    @Test
    fun topLevelCollectionStillInsetsTheSafeArea() {
        val s = """{"type":"screen","id":"s","content":{"children":[$grid]}}"""
        val node = session(runtime(mapOf("s" to s))).root.find("grid")!!
        val solved = Solved(
            local = emptyMap(),
            absolute = mapOf("s" to Frame(0.0, 0.0, 412.0, 923.0), "s.grid" to Frame(0.0, 0.0, 412.0, 923.0)),
            contentSizes = emptyMap(),
            safeArea = EdgeInsets(top = 118.0, bottom = 88.0),
        )
        assertEquals(118.0 to 88.0, CollectionKind.automaticInsets(node, solved, CollectionKind.sizesToContent(node)))
    }
}
