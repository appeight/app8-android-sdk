package dev.app8.engine.navigation

import dev.app8.engine.core.model.NavigationBar
import dev.app8.engine.core.model.SearchBar
import org.junit.Test
import kotlin.test.assertEquals

/**
 * iOS `SearchFillsBarTests` (app8-ios-sdk#36): `fillsBar` + `integrated` keeps the field in its
 * integrated slot, widened across the bar — here the title slot, which spans the bar between
 * the navigation icon and the actions (the ✕ shows inside the field while focused).
 */
class SearchFillsBarTest {
    private fun bar(placement: SearchBar.Placement?, fillsBar: Boolean?, title: String? = "Shop") =
        NavigationBar(title = title, search = SearchBar(placement = placement, fillsBar = fillsBar))

    @Test
    fun fillsBarIntegratedStaysInTheTitleSlot() {
        assertEquals(SearchSlot.Title, searchSlot(bar(SearchBar.Placement.integrated, fillsBar = true), "Shop"))
        assertEquals(SearchSlot.Title, searchSlot(bar(SearchBar.Placement.integrated, fillsBar = false), "Shop"))
    }

    @Test
    fun fillsBarDoesNotMoveOtherPlacements() {
        assertEquals(SearchSlot.Row, searchSlot(bar(SearchBar.Placement.stacked, fillsBar = true), "Shop"))
        assertEquals(SearchSlot.Button, searchSlot(bar(SearchBar.Placement.integratedButton, fillsBar = true), "Shop"))
    }
}
