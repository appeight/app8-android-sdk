package dev.app8.engine.icons

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MaterialSymbolsTest {
    private val table = mapOf("suitcase" to "luggage", "heart" to "favorite")
    private val font = setOf("luggage", "favorite", "home")

    @Test
    fun sfNamesMapThroughTheTable() {
        assertEquals(MaterialSymbols.Glyph("luggage", false), MaterialSymbols.resolve("suitcase", table, font::contains))
        assertEquals(MaterialSymbols.Glyph("favorite", true), MaterialSymbols.resolve("heart.fill", table, font::contains))
    }

    @Test
    fun bareNamesMustExistInTheFont() {
        assertEquals(MaterialSymbols.Glyph("home", false), MaterialSymbols.resolve("home", table, font::contains))
        // Not a ligature: would draw its letters.
        assertNull(MaterialSymbols.resolve("briefcase", table, font::contains))
    }
}
