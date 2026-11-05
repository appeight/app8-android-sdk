package dev.app8.engine.navigation

import dev.app8.engine.core.model.NavigationBar
import dev.app8.engine.navigation.transitions.MaterialTransitions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the system chrome numbers to docs/android-chrome-spec.md, which the Studio web
 * renderer copies. Change both together, with the Material source that justifies it.
 */
class ChromeSpecTest {

    @Test
    fun topAppBarHeights() {
        assertEquals(64.0, NavigationBar.TopAppBarStyle.small.expandedHeight)
        assertEquals(64.0, NavigationBar.TopAppBarStyle.centerAligned.expandedHeight)
        assertEquals(112.0, NavigationBar.TopAppBarStyle.medium.expandedHeight)
        assertEquals(152.0, NavigationBar.TopAppBarStyle.large.expandedHeight)
        assertTrue(NavigationBar.TopAppBarStyle.large.collapses)
        assertTrue(NavigationBar.TopAppBarStyle.medium.collapses)
        assertFalse(NavigationBar.TopAppBarStyle.small.collapses)
    }

    @Test
    fun searchAndNavigationBar() {
        assertEquals(56, SEARCH_FIELD_HEIGHT)
        assertEquals(TOP_BAR_HEIGHT, SEARCH_FIELD_HEIGHT + 2.0 * SEARCH_APP_BAR_VERTICAL_PADDING)
        assertEquals(12, SEARCH_APP_BAR_FIELD_INSET)
        assertEquals(64.0, SEARCH_ROW_HEIGHT)
        assertEquals(64.0, TAB_BAR_HEIGHT)
        assertEquals(600, TAB_BAR_HORIZONTAL_ITEMS_WIDTH)
        assertEquals(48f, SHEET_HANDLE_BLOCK)
    }

    @Test
    fun systemTransition() {
        assertEquals(450, MaterialTransitions.DURATION_MS)
        assertEquals(30, MaterialTransitions.SLIDE_DP)
        assertEquals(0.9f, MaterialTransitions.PREDICTIVE_SCALE)
    }

    @Test
    fun heroScrollDistanceIsTheHeroBottomUnderTheBar() {
        fun f(x: Double, y: Double, w: Double, h: Double) = dev.app8.engine.core.layout.Frame(x, y, w, h)
        val solved = dev.app8.engine.render.Solved(
            local = emptyMap(),
            absolute = mapOf(
                "root" to f(0.0, 0.0, 412.0, 915.0),
                "scroll" to f(0.0, 0.0, 412.0, 915.0),
                "gallery" to f(0.0, 0.0, 412.0, 420.0),
                "title" to f(16.0, 436.0, 380.0, 28.0),
            ),
            contentSizes = emptyMap(),
            safeArea = dev.app8.engine.core.layout.EdgeInsets.ZERO,
        )
        // Status bar 52 + bar 64: opaque once the 420pt gallery's bottom passes y = 116.
        assertEquals(304.0, heroScrollDistance(solved, 116.0))
        assertEquals(0.0, heroScrollDistance(dev.app8.engine.render.Solved.EMPTY, 116.0))
        assertEquals(0.9f, OVER_CONTENT_ICON_ALPHA)
    }
}
