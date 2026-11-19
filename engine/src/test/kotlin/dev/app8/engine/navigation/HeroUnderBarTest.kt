package dev.app8.engine.navigation

import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.render.Solved
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import org.junit.Test
import kotlin.test.assertEquals

/** Which frames count as a hero under the top app bar (ScreenHost `heroScrollDistance`). */
class HeroUnderBarTest {
    private fun screen(adjustment: String) = """{"type":"screen","id":"s","content":{"children":[
      {"type":"scrollView","id":"scroll","content":{"properties":{"contentInsetAdjustment":"$adjustment"},"children":[
        {"type":"stackView","id":"stack","content":{"children":[
          {"type":"view","id":"hero","content":{}},
          {"type":"label","id":"title","content":{"properties":{"text":"Name"}}}]}}]}}]}}"""

    private val solved = Solved(
        local = emptyMap(),
        absolute = mapOf(
            "s" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll" to Frame(0.0, 0.0, 412.0, 923.0),
            "s.scroll.stack" to Frame(0.0, 0.0, 412.0, 2400.0),
            "s.scroll.stack.hero" to Frame(0.0, 0.0, 412.0, 679.0),
            "s.scroll.stack.title" to Frame(16.0, 690.0, 380.0, 24.0),
            // A horizontal row's content, wider than the screen.
            "s.scroll.stack.row" to Frame(0.0, 720.0, 1244.0, 320.0),
        ),
        contentSizes = emptyMap(),
        safeArea = EdgeInsets(top = 118.0),
    )

    @Test
    fun heroInAScrollViewThatDrawsUnderTheBarCounts() {
        val keys = keysOutsideAdjustingScrolls(session(runtime(mapOf("s" to screen("never")))).root)
        assertEquals(true, "s.scroll.stack.hero" in keys, keys.toString())
        assertEquals(923.0 - 118.0, heroScrollDistance(solved, 118.0, keys + "s.scroll.stack.row", screenWidth = 412.0))
        // Measured against the widest frame instead, nothing is full width: no hero.
        assertEquals(0.0, heroScrollDistance(solved, 118.0, keys + "s.scroll.stack.row"))
    }

    @Test
    fun contentOfAnInsettingScrollViewIsNotAHero() {
        val keys = keysOutsideAdjustingScrolls(session(runtime(mapOf("s" to screen("automatic")))).root)
        assertEquals(false, "s.scroll.stack.hero" in keys)
    }
}
