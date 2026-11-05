package dev.app8.engine.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Expected values were captured from Swift 6.4 on macOS (see the port's golden oracle). */
class SwiftCompatTest {

    @Test
    fun double_description_matches_swift() {
        val cases = mapOf(
            0.1 to "0.1", 2.0 to "2.0", 1e15 to "1000000000000000.0", 1e16 to "1e+16",
            9007199254740992.0 to "9007199254740992.0", 18014398509481984.0 to "1.8014398509481984e+16",
            12345678.5 to "12345678.5", 0.001 to "0.001", 0.0001 to "0.0001", 0.00012 to "0.00012",
            1e-5 to "1e-05", -0.0 to "-0.0", 1.0 / 3.0 to "0.3333333333333333", 100.0 / 7.0 to "14.285714285714286",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf",
            5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308", -2.5e-7 to "-2.5e-07",
        )
        for ((d, expected) in cases) assertEquals(expected, SwiftCompat.doubleDescription(d), "for $d")
    }

    @Test
    fun double_parsing_matches_swift() {
        val accepted = mapOf(
            "1e3" to 1000.0, "1E+3" to 1000.0, "0x10" to 16.0, "0X10" to 16.0, "0x1p3" to 8.0, "0x1.8" to 1.5,
            "+.5" to 0.5, "5." to 5.0, ".5" to 0.5, "+5" to 5.0, "00012" to 12.0, "-0" to -0.0,
            "Infinity" to Double.POSITIVE_INFINITY, "INF" to Double.POSITIVE_INFINITY, "+inf" to Double.POSITIVE_INFINITY,
            "1e400" to Double.POSITIVE_INFINITY, "4.9e-325" to 0.0,
        )
        for ((s, expected) in accepted) assertEquals(expected, SwiftCompat.parseDouble(s), "for '$s'")
        assertTrue(SwiftCompat.parseDouble("NaN")!!.isNaN())
        assertTrue(SwiftCompat.parseDouble("nan(0x1)")!!.isNaN())
        for (s in listOf("1e", "5 ", " 5", "1_000", "١", "-.e1", "0x", "  ", "infinit", "", "1d", "1f")) {
            assertNull(SwiftCompat.parseDouble(s), "for '$s'")
        }
    }

    @Test
    fun int_parsing_matches_swift() {
        assertEquals(5L, SwiftCompat.parseInt("+5"))
        assertEquals(0L, SwiftCompat.parseInt("-0"))
        assertNull(SwiftCompat.parseInt(" 5"))
        assertNull(SwiftCompat.parseInt("5.0"))
        assertNull(SwiftCompat.parseInt("٣"))
        assertNull(SwiftCompat.parseInt("99999999999999999999"))
        assertNull(SwiftCompat.parseInt("-"))
    }

    @Test
    fun character_counting_uses_grapheme_clusters() {
        assertEquals(1, SwiftCompat.characterCount("👍🏽"))
        assertEquals(3, SwiftCompat.characterCount("ábc"))
        assertEquals(3, SwiftCompat.characterCount("a\r\nb"))
        assertEquals(listOf("é", "x"), SwiftCompat.characters("éx").map { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) })
    }

    @Test
    fun rounding_is_half_away_from_zero() {
        assertEquals(3.0, SwiftCompat.roundHalfAwayFromZero(2.5))
        assertEquals(-3.0, SwiftCompat.roundHalfAwayFromZero(-2.5))
        assertEquals(0.0, SwiftCompat.roundHalfAwayFromZero(0.49999999999999994))
        assertEquals(1e300, SwiftCompat.roundHalfAwayFromZero(1e300))
    }

    @Test
    fun trimming_sets() {
        assertEquals("x", SwiftCompat.trimWhitespaces(" \t x 　"))
        assertEquals("x\n", SwiftCompat.trimWhitespaces(" x\n"))
        assertEquals("x", SwiftCompat.trimWhitespacesAndNewlines("\u0085  x \r\n"))
    }
}
