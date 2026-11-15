package dev.app8.engine.navigation

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tapping the selected tab again (navigation.md, "Reselecting the selected tab"): a pushed
 * stack pops to its root; at the root, the screen's main (outermost) vertical scroller
 * scrolls to the top.
 */
class TabReselectTest {

    @Test
    fun reselectWithPushedScreensPopsToRootWithoutScrolling() = runBlocking {
        val stack = StackState(Route("home"))
        val root = stack.entries.first()
        var scrolled = 0
        stack.scrollSignal(root).register("list", 3) { scrolled++ }
        stack.push(Route("list"))
        stack.push(Route("detail"))

        stack.reselect()

        assertEquals(listOf("home"), stack.entries.map { it.screenId })
        assertEquals(0, scrolled)
    }

    @Test
    fun reselectAtRootScrollsTheOutermostVerticalScroller() = runBlocking {
        val stack = StackState(Route("home"))
        val signal = stack.scrollSignal(stack.entries.first())
        val calls = mutableListOf<String>()
        // Registration order is composition order, not tree order: the lowest order wins.
        signal.register("innerGrid", 9) { calls += "innerGrid" }
        signal.register("screenList", 2) { calls += "screenList" }

        stack.reselect()

        assertEquals(listOf("screenList"), calls)
        assertEquals(listOf("home"), stack.entries.map { it.screenId })
    }

    @Test
    fun aScreenWithoutScrollersIgnoresReselect() = runBlocking {
        val signal = ScrollToTopSignal()
        assertFalse(signal.scrollToTop())
        signal.register("a", 0) {}
        signal.unregister("a")
        assertFalse(signal.scrollToTop())
        signal.register("b", 0) {}
        assertTrue(signal.scrollToTop())
    }

    @Test
    fun poppedEntriesDropTheirScrollers() = runBlocking {
        val stack = StackState(Route("home"))
        val pushed = Route("list")
        stack.push(pushed)
        val before = stack.scrollSignal(pushed)
        stack.pop(toRoot = false)
        // A fresh signal: the popped screen's scrollers are gone with it.
        assertTrue(before !== stack.scrollSignal(pushed))
    }
}
