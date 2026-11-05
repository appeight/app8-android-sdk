package dev.app8.engine.core.model.style

import dev.app8.engine.core.model.Cursor

/**
 * A parsed hex color (iOS `Style.Color.Hex`). Accepts `RGB`, `RGBA`, `RRGGBB`, `RRGGBBAA`
 * (CSS order, alpha last) with or without `#`. Components are `0…1`.
 */
public data class ColorHex(
    /** Uppercased, without `#`. */
    val value: String,
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
) {
    /** Packed `0xAARRGGBB` (Android / Compose order). */
    public val argb: Int
        get() {
            fun ch(v: Float) = Math.round(v.coerceIn(0f, 1f) * 255f)
            return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }

    public companion object {
        public val CLEAR: ColorHex = ColorHex("00000000", 0f, 0f, 0f, 0f)

        private val VALID = Regex("^#?([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

        public fun isValidHexString(hex: String): Boolean = VALID.matches(hex)

        /** Strict parse; throws [IllegalArgumentException] on invalid input. */
        public fun parse(hexString: String): ColorHex {
            var s = hexString.trim().uppercase()
            if (s.startsWith("#")) s = s.substring(1)
            // Scanner.scanHexInt64 semantics: optional 0x prefix, then the longest run of hex digits.
            val digitsStart = if (s.startsWith("0X")) 2 else 0
            var end = digitsStart
            while (end < s.length && s[end].isHexDigitChar()) end++
            require(end > digitsStart) { "Invalid hex digits in $hexString" }
            val value = s.substring(digitsStart, minOf(end, digitsStart + 16)).toULong(16).toLong()
            fun n(shift: Int, mask: Long, max: Float) = ((value shr shift) and mask).toFloat() / max
            return when (s.length) {
                3 -> ColorHex(s, n(8, 0xF, 15f), n(4, 0xF, 15f), n(0, 0xF, 15f), 1f)
                4 -> ColorHex(s, n(12, 0xF, 15f), n(8, 0xF, 15f), n(4, 0xF, 15f), n(0, 0xF, 15f))
                6 -> ColorHex(s, n(16, 0xFF, 255f), n(8, 0xFF, 255f), n(0, 0xFF, 255f), 1f)
                8 -> ColorHex(s, n(24, 0xFF, 255f), n(16, 0xFF, 255f), n(8, 0xFF, 255f), n(0, 0xFF, 255f))
                else -> throw IllegalArgumentException("Invalid hex color length in $hexString")
            }
        }

        /** Non-throwing parse; invalid input gives [fallback] (iOS `Hex(_:fallback:)`). */
        public fun parseOrFallback(hexString: String, fallback: ColorHex = CLEAR): ColorHex =
            try {
                parse(hexString)
            } catch (e: IllegalArgumentException) {
                fallback
            }

        private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'A'..'F'
    }
}

internal fun decodeColorHex(c: Cursor): ColorHex {
    val s = c.string()
    return try {
        ColorHex.parse(s)
    } catch (e: IllegalArgumentException) {
        c.fail(e.message ?: "invalid hex color")
    }
}

/**
 * A theme-aware color (iOS `Style.Color.Themed`). A bare string is a single hex (invalid → clear);
 * an object needs both `light` and `dark`, each a hex or a `{ "id": … }` reference to another color style.
 */
public data class ThemedColor(
    val source: Source,
    val light: ColorHex? = null,
    val dark: ColorHex? = null,
    val lightRef: String? = null,
    val darkRef: String? = null,
) {
    public enum class Source { SINGLE_HEX, THEMED }

    public fun resolve(isDark: Boolean): ColorHex = (if (isDark && dark != null) dark else light) ?: ColorHex.CLEAR

    public val isResolved: Boolean get() = light != null || dark != null

    public val unresolvedPointerIds: List<String>
        get() = listOfNotNull(lightRef.takeIf { light == null }, darkRef.takeIf { dark == null })
}

internal fun decodeThemedColor(c: Cursor): ThemedColor {
    c.stringOrNull()?.let { return ThemedColor(ThemedColor.Source.SINGLE_HEX, light = ColorHex.parseOrFallback(it)) }
    c.obj()
    fun ref(key: String): String? = c.attempt { it.req(key) { r -> r.obj(); r.reqString("id") } }
    val lightRef = ref("light")
    val light = if (lightRef == null) c.req("light", ::decodeColorHex) else null
    val darkRef = ref("dark")
    val dark = if (darkRef == null) c.req("dark", ::decodeColorHex) else null
    return ThemedColor(ThemedColor.Source.THEMED, light, dark, lightRef, darkRef)
}
