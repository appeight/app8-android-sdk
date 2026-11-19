package dev.app8.engine.icons

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bar icon buttons get a TalkBack label from their symbol. */
class SymbolLabelsTest {
    @Test fun commonActionsUseMaterialWording() {
        assertEquals("Navigate up", SymbolLabels.of("arrow_back"))
        assertEquals("Search", SymbolLabels.of("magnifyingglass"))
        assertEquals("Favourite", SymbolLabels.of("heart"))
        assertEquals("Favourite", SymbolLabels.of("heart.fill"))
        assertEquals("Close", SymbolLabels.of("xmark"))
        assertEquals("More options", SymbolLabels.of("ellipsis"))
        assertEquals("Share", SymbolLabels.of("square.and.arrow.up"))
        assertEquals("Filter", SymbolLabels.of("slider.horizontal.3"))
    }

    @Test fun otherSymbolsAreReadFromTheirName() {
        assertEquals("Cart", SymbolLabels.of("cart.badge.plus"))
        assertEquals("Bag", SymbolLabels.of("bag.fill"))
        assertEquals("Shopping bag", SymbolLabels.of("shopping_bag"))
        assertEquals("Gift", SymbolLabels.of("gift"))
        assertEquals("", SymbolLabels.of(" "))
    }
}
