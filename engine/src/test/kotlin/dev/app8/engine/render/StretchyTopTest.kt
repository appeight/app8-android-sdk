package dev.app8.engine.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.navigation.keysOutsideAdjustingScrolls
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Stretchy top hero, main scroll view, preview corners (DSL 0.7.11). */
class StretchyTopTest {
    private val stackScreen = """{"type":"screen","id":"s","content":{"children":[
      {"type":"scrollView","id":"scroll","content":{"properties":{"stretchesTopChild":true,"contentInsetAdjustment":"bottom"},"children":[
        {"type":"stackView","id":"stack","content":{"properties":{"axis":"vertical"},"children":[
          {"type":"view","id":"heroBox","content":{"children":[
            {"type":"image","id":"photo","content":{}},
            {"type":"label","id":"caption","content":{"properties":{"text":"Cover"}}}]}},
          {"type":"label","id":"title","content":{"properties":{"text":"Name"}}}]}}]}},
      {"type":"scrollView","id":"side","content":{"children":[]}}]}}"""

    private val stackSolved = Solved(
        local = mapOf(
            "s.scroll" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll.stack" to Frame(0.0, 0.0, 412.0, 1400.0),
            "s.scroll.stack.heroBox" to Frame(0.0, 0.0, 412.0, 500.0),
            // The photo fills the hero box: the box stays the hero, so the caption scales too.
            "s.scroll.stack.heroBox.photo" to Frame(0.0, 0.0, 412.0, 500.0),
            "s.scroll.stack.heroBox.caption" to Frame(16.0, 460.0, 200.0, 24.0),
            "s.scroll.stack.title" to Frame(16.0, 510.0, 380.0, 24.0),
            "s.side" to Frame(0.0, 0.0, 100.0, 100.0),
        ),
        absolute = mapOf(
            "s" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.side" to Frame(0.0, 0.0, 100.0, 100.0),
        ),
        contentSizes = emptyMap(),
        safeArea = EdgeInsets(top = 52.0, bottom = 48.0),
    )

    @Test
    fun heroDescendsThroughAVerticalStackToTheHeroBox() {
        val root = session(runtime(mapOf("s" to stackScreen))).root
        val scroll = root.children.first { it.id == "scroll" }
        assertEquals("s.scroll.stack.heroBox", stretchHeroKey(scroll, stackSolved))
    }

    @Test
    fun heroDescendsIntoAViewWhoseFirstChildIsPinnedToItsTop() {
        val screen = """{"type":"screen","id":"s","content":{"children":[
          {"type":"scrollView","id":"scroll","content":{"properties":{"stretchesTopChild":true},"children":[
            {"type":"view","id":"content","content":{"children":[
              {"type":"collection","id":"pager","content":{"properties":{}}},
              {"type":"label","id":"title","content":{"properties":{"text":"Name"}}}]}}]}}]}}"""
        val solved = Solved(
            local = mapOf(
                "s.scroll.content" to Frame(0.0, 0.0, 412.0, 1200.0),
                "s.scroll.content.pager" to Frame(0.0, 0.0, 412.0, 520.0),
                "s.scroll.content.title" to Frame(16.0, 530.0, 380.0, 24.0),
            ),
            absolute = emptyMap(), contentSizes = emptyMap(), safeArea = EdgeInsets.ZERO,
        )
        val scroll = session(runtime(mapOf("s" to screen))).root.children.single()
        assertEquals("s.scroll.content.pager", stretchHeroKey(scroll, solved))
    }

    @Test
    fun horizontalStackAndUnpinnedFirstChildStopTheDescent() {
        val screen = """{"type":"screen","id":"s","content":{"children":[
          {"type":"scrollView","id":"scroll","content":{"properties":{"stretchesTopChild":true},"children":[
            {"type":"stackView","id":"row","content":{"properties":{"axis":"horizontal"},"children":[
              {"type":"view","id":"a","content":{}}]}},
            {"type":"view","id":"padded","content":{"children":[{"type":"view","id":"inner","content":{}}]}}]}}]}}"""
        val scroll = session(runtime(mapOf("s" to screen))).root.children.single()
        val local = mapOf(
            "s.scroll.row" to Frame(0.0, 0.0, 412.0, 300.0),
            "s.scroll.row.a" to Frame(0.0, 0.0, 100.0, 300.0),
            "s.scroll.padded" to Frame(0.0, 300.0, 412.0, 300.0),
            "s.scroll.padded.inner" to Frame(0.0, 20.0, 412.0, 200.0),
        )
        assertEquals("s.scroll.row", stretchHeroKey(scroll, Solved(local, emptyMap(), emptyMap(), EdgeInsets.ZERO)))
        // A hidden first child (no height) is skipped; the padded view's child is not top-pinned.
        val hiddenRow = local + ("s.scroll.row" to Frame(0.0, 0.0, 412.0, 0.0))
        assertEquals("s.scroll.padded", stretchHeroKey(scroll, Solved(hiddenRow, emptyMap(), emptyMap(), EdgeInsets.ZERO)))
    }

    @Test
    fun mainVerticalScrollIsTheLargestTopLevelOne() {
        val root = session(runtime(mapOf("s" to stackScreen))).root
        assertEquals("s.scroll", mainVerticalScrollKey(root, stackSolved))
    }

    @Test
    fun bottomAdjustingScrollContentDrawsUnderTheBar() {
        // `bottom` does not inset the top: its hero sits under the top bar like `never`.
        val keys = keysOutsideAdjustingScrolls(session(runtime(mapOf("s" to stackScreen))).root)
        assertTrue("s.scroll.stack.heroBox" in keys, keys.toString())
    }

    @Test
    fun pullAtTheTopStretchesAndRelaxesBeforeScrolling() {
        val effect = StretchyTopOverscroll { 1000f }
        // At the top nothing scrolls: the whole drag goes into the pull, at half the travel.
        val consumed = effect.applyToScroll(Offset(0f, 40f), NestedScrollSource.UserInput) { Offset.Zero }
        assertEquals(40f, consumed.y)
        assertEquals(20f * (1f), effect.pull.value, 0.01f)
        assertTrue(effect.isInProgress)
        // Dragging back up relaxes the pull first; the content does not scroll for that part.
        var scrolled = Offset.Zero
        effect.applyToScroll(Offset(0f, -50f), NestedScrollSource.UserInput) { scrolled = it; it }
        assertEquals(0f, effect.pull.value)
        assertEquals(-30f, scrolled.y, 0.01f)
        // A fling reaching the edge does not pull.
        effect.applyToScroll(Offset(0f, 30f), NestedScrollSource.SideEffect) { Offset.Zero }
        assertEquals(0f, effect.pull.value)
    }

    @Test
    fun rubberBandResistsTowardsTheMaximum() {
        val a = StretchyTopOverscroll.rubberBand(0f, 100f, 1000f)
        val b = StretchyTopOverscroll.rubberBand(500f, 100f, 1000f)
        assertEquals(50f, a)
        assertEquals(525f, b)
        assertEquals(1000f, StretchyTopOverscroll.rubberBand(1000f, 100f, 1000f))
    }

    @Test
    fun previewCornerRadiusShapes() {
        assertEquals(RectangleShape, previewShape(0.0))
        assertNotEquals(RectangleShape, previewShape(null))
        assertNotEquals(RectangleShape, previewShape(12.0))
        assertNotEquals(RectangleShape, previewShape(-3.0))
    }
}
