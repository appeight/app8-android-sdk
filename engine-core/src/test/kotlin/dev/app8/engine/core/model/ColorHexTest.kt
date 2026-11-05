package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.decodeColorHex
import dev.app8.engine.core.model.style.decodeFill
import dev.app8.engine.core.model.style.decodeThemedColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hex color parsing (iOS `Style.Color.Hex` / `Themed`). */
class ColorHexTest {

    private fun themed(json: String): ThemedColor = DslDecoder().decode(json, ::decodeThemedColor)

    @Test
    fun parsesSixDigits() {
        val c = ColorHex.parse("#FF8000")
        assertEquals("FF8000", c.value)
        assertEquals(1f, c.r)
        assertEquals(128f / 255f, c.g)
        assertEquals(0f, c.b)
        assertEquals(1f, c.a)
        assertEquals(0xFFFF8000.toInt(), c.argb)
    }

    @Test
    fun parsesEightDigitsWithAlphaLast() {
        val c = ColorHex.parse("00000080")
        assertEquals(0f, c.r)
        assertEquals(128f / 255f, c.a)
        assertEquals(0x80000000.toInt(), c.argb)
    }

    @Test
    fun parsesThreeAndFourDigits() {
        val three = ColorHex.parse("#f0a")
        assertEquals("F0A", three.value)
        assertEquals(1f, three.r)
        assertEquals(0f, three.g)
        assertEquals(10f / 15f, three.b)
        assertEquals(1f, three.a)
        val four = ColorHex.parse("F00 8".replace(" ", ""))
        assertEquals(1f, four.r)
        assertEquals(8f / 15f, four.a)
    }

    @Test
    fun trimsWhitespaceAndIsCaseInsensitive() {
        assertEquals(ColorHex.parse("#ABCDEF"), ColorHex.parse("  #abcdef\n"))
    }

    @Test
    fun invalidThrows() {
        assertFailsWith<IllegalArgumentException> { ColorHex.parse("#12345") }
        assertFailsWith<IllegalArgumentException> { ColorHex.parse("#GGGGGG") }
        assertFailsWith<IllegalArgumentException> { ColorHex.parse("") }
        assertFailsWith<DslDecodingException> { DslDecoder().decode("\"red\"", ::decodeColorHex) }
        assertFailsWith<DslDecodingException> { DslDecoder().decode("123", ::decodeColorHex) }
    }

    @Test
    fun fallbackIsClear() {
        assertEquals(ColorHex.CLEAR, ColorHex.parseOrFallback("nope"))
        assertEquals(0, ColorHex.CLEAR.argb)
    }

    @Test
    fun validityCheck() {
        assertTrue(ColorHex.isValidHexString("#fff"))
        assertTrue(ColorHex.isValidHexString("FFFFFF80"))
        assertFalse(ColorHex.isValidHexString("#ffff"))
        assertFalse(ColorHex.isValidHexString("{{c}}"))
    }

    @Test
    fun themedSingleStringIsLenient() {
        val single = themed("\"#112233\"")
        assertEquals(ThemedColor.Source.SINGLE_HEX, single.source)
        assertEquals("112233", single.light?.value)
        assertNull(single.dark)
        // Invalid single hex (e.g. an expression) falls back to clear instead of failing.
        assertEquals(ColorHex.CLEAR, themed("\"{{item.color}}\"").light)
    }

    @Test
    fun themedLightDark() {
        val c = themed("""{ "light": "#FFFFFF", "dark": "#000000" }""")
        assertEquals(ThemedColor.Source.THEMED, c.source)
        assertEquals("FFFFFF", c.resolve(isDark = false).value)
        assertEquals("000000", c.resolve(isDark = true).value)
    }

    @Test
    fun themedRefsAreKept() {
        val c = themed("""{ "light": { "id": "white" }, "dark": "#000000" }""")
        assertEquals("white", c.lightRef)
        assertNull(c.light)
        assertEquals("000000", c.dark?.value)
        assertEquals(listOf("white"), c.unresolvedPointerIds)
        assertTrue(c.isResolved) // dark is concrete
    }

    @Test
    fun themedDarkIsRequired() {
        assertFailsWith<DslDecodingException> { themed("""{ "light": "#FFFFFF" }""") }
        assertFailsWith<DslDecodingException> { themed("""{ "light": "#FFFFFF", "dark": "nope" }""") }
    }

    @Test
    fun fillDropsInvalidSolidInsteadOfFailing() {
        val fill = DslDecoder().decode("""{ "solid": { "light": "#FFFFFF" } }""", ::decodeFill)
        assertNull(fill.solid.value)
        assertEquals("ABCDEF", DslDecoder().decode("""{ "solid": "#abcdef" }""", ::decodeFill).solid.value?.light?.value)
    }
}
