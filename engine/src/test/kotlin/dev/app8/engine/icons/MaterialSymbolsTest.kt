package dev.app8.engine.icons

import dev.app8.engine.icons.MaterialSymbols.Glyph
import dev.app8.engine.icons.MaterialSymbols.Lookup
import org.junit.Test
import kotlin.test.assertEquals

class MaterialSymbolsTest {
    private val table = mapOf("suitcase" to "luggage", "heart" to "favorite", "briefcase.circle" to "work")
    private val font = mapOf("luggage" to 0xf235, "favorite" to 0xe87d, "home" to 0xe9b2)

    @Test
    fun sfNamesMapThroughTheTable() {
        assertEquals(Lookup.Found(Glyph("luggage", 0xf235, false)), MaterialSymbols.lookup("suitcase", table, font))
        assertEquals(Lookup.Found(Glyph("favorite", 0xe87d, true)), MaterialSymbols.lookup("heart.fill", table, font))
        assertEquals("", Glyph("favorite", 0xe87d, true).text)
    }

    @Test
    fun materialNamesMustBeInTheSubset() {
        assertEquals(Lookup.Found(Glyph("home", 0xe9b2, false)), MaterialSymbols.lookup("home", table, font))
        // A Material name outside the bundled subset (an `android` override): drawn as nothing.
        assertEquals(Lookup.Unavailable("star"), MaterialSymbols.lookup("star", table, font))
        // Mapped, but the target glyph is not bundled.
        assertEquals(Lookup.Unavailable("work"), MaterialSymbols.lookup("briefcase.circle", table, font))
    }

    @Test
    fun unknownSfNamesAreUnmapped() {
        assertEquals(Lookup.Unmapped, MaterialSymbols.lookup("cart.badge.questionmark", table, font))
    }
}
