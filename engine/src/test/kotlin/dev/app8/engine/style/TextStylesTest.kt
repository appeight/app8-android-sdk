package dev.app8.engine.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TextStylesTest {

    @Test
    fun `size weight color alignment and decorations`() {
        val t = TestStyles.text(
            """{ "fontSize": 15, "fontWeight": "semibold", "alignment": "center", "underline": true, "strikethrough": true,
                 "color": { "light": "#111111", "dark": "#EEEEEE" } }""",
        ).toResolvedText(App8Appearance.Dark)
        assertEquals(15.sp, t.style.fontSize)
        assertEquals(FontWeight.W600, t.style.fontWeight)
        assertEquals(FontFamily.Default, t.style.fontFamily)
        assertEquals(TextAlign.Center, t.style.textAlign)
        assertEquals(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), t.style.textDecoration)
        assertEquals(Color(0xFFEEEEEE), t.style.color)
        assertEquals(Int.MAX_VALUE, t.maxLines)
        assertEquals(TextOverflow.Ellipsis, t.overflow)
    }

    @Test
    fun `alignment maps natural to start and accepts the iOS integers`() {
        assertEquals(TextAlign.Start, TestStyles.text("""{ "fontSize": 12, "alignment": "natural" }""").toResolvedText(App8Appearance.Light).style.textAlign)
        assertEquals(TextAlign.Right, TestStyles.text("""{ "fontSize": 12, "alignment": 2 }""").toResolvedText(App8Appearance.Light).style.textAlign)
        assertEquals(TextAlign.Justify, TestStyles.text("""{ "fontSize": 12, "alignment": "justified" }""").toResolvedText(App8Appearance.Light).style.textAlign)
    }

    @Test
    fun `all nine weights map to 100 through 900`() {
        assertEquals(
            (1..9).map { it * 100 },
            dev.app8.engine.core.model.style.Font.Weight.entries.map { it.toFontWeight().weight },
        )
    }

    @Test
    fun `letter spacing in points becomes em of the font size`() {
        val t = TestStyles.text("""{ "fontSize": 20, "letterSpacing": -1 }""").toResolvedText(App8Appearance.Light)
        assertEquals((-0.05f).em, t.style.letterSpacing)
        val auto = TestStyles.text("""{ "fontSize": 20, "letterSpacing": { "type": "auto" } }""").toResolvedText(App8Appearance.Light)
        assertEquals(TextUnit.Unspecified, auto.style.letterSpacing)
    }

    @Test
    fun `line heights follow CLabelView paragraph styles`() {
        fun lh(json: String) = TestStyles.text("""{ "fontSize": 10, "lineHeight": $json }""").toResolvedText(App8Appearance.Light).style
        val fixed = lh("""{ "type": "fixed", "value": 22 }""")
        assertEquals(22.sp, fixed.lineHeight)
        // Half-leading: a fixed line centres the glyphs, first and last line included.
        assertEquals(LineHeightStyle.Alignment.Center, fixed.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.None, fixed.lineHeightStyle?.trim)
        assertEquals(LineHeightStyle.Mode.Fixed, fixed.lineHeightStyle?.mode)
        val multiplier = lh("""{ "type": "multiplier", "value": 1.5 }""")
        assertEquals((IOS_NATURAL_LINE_HEIGHT_EM * 1.5f).em, multiplier.lineHeight)
        assertEquals(LineHeightStyle.Alignment.Bottom, multiplier.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Mode.Fixed, multiplier.lineHeightStyle?.mode)
        val spacing = lh("""{ "type": "interLineSpacing", "value": 4 }""")
        assertEquals((10 * IOS_NATURAL_LINE_HEIGHT_EM + 4).sp.value, spacing.lineHeight.value, 1e-4f)
        assertEquals(LineHeightStyle.Trim.LastLineBottom, spacing.lineHeightStyle?.trim)
        assertEquals((10 * IOS_NATURAL_LINE_HEIGHT_EM + 5).sp.value, lh("""{ "type": "fontSizeFraction", "value": 0.5 }""").lineHeight.value, 1e-4f)
        assertEquals(TextUnit.Unspecified, lh("""{ "type": "auto", "value": 0 }""").lineHeight)
    }

    @Test
    fun `fixed scaling converts points through the sizer once`() {
        // A sizer that halves (as Dp.toSp does at fontScale 2): size and absolute line height both go through it.
        val half = TextSizer { (it / 2).sp }
        val t = TestStyles.text("""{ "fontSize": 20, "lineHeight": { "type": "fixed", "value": 30 } }""")
            .toResolvedText(App8Appearance.Light, sizer = half)
        assertEquals(10.sp, t.style.fontSize)
        assertEquals(15.sp, t.style.lineHeight)
        // em values are relative to the (already converted) size: not converted again.
        val m = TestStyles.text("""{ "fontSize": 20, "letterSpacing": 2 }""").toResolvedText(App8Appearance.Light, sizer = half)
        assertEquals(0.1f.em, m.style.letterSpacing)
    }

    @Test
    fun `lines and line break modes`() {
        fun r(json: String) = TestStyles.text(json).toResolvedText(App8Appearance.Light)
        assertEquals(2, r("""{ "fontSize": 12, "numberOfLines": 2 }""").maxLines)
        assertEquals(TextOverflow.MiddleEllipsis, r("""{ "fontSize": 12, "lineBreakMode": "truncateMiddle" }""").overflow)
        assertEquals(TextOverflow.StartEllipsis, r("""{ "fontSize": 12, "lineBreakMode": "truncateHead" }""").overflow)
        assertEquals(TextOverflow.Clip, r("""{ "fontSize": 12, "lineBreakMode": "clip" }""").overflow)
        // Labels avoid orphans (iOS .pushOut) on every style, with or without a line height.
        assertEquals(LineBreak.Paragraph, r("""{ "fontSize": 12 }""").style.lineBreak)
        assertEquals(LineBreak.Paragraph, r("""{ "fontSize": 30, "lineHeight": { "type": "multiplier", "value": 1.05 } }""").style.lineBreak)
        val shrink = r("""{ "fontSize": 12, "adjustsFontSizeToFitWidth": true }""")
        assertEquals(true, shrink.adjustsFontSizeToFitWidth)
        assertEquals(0.5f, shrink.minimumScaleFactor)
    }

    @Test
    fun `a bundle font's multiplier line height multiplies its own natural height`() {
        val brand = FontFamily.Serif
        val provider = App8FontProvider { name, _ -> if (name == "Brand-Regular") brand else null }
        // The measured natural height of the bundle font (UIFont.lineHeight / pointSize on iOS).
        val natural: (FontFamily, FontWeight) -> Float? = { f, _ -> if (f == brand) 1.44f else null }
        fun lh(font: String, json: String) =
            TestStyles.text("""{ "fontSize": 10, $font"lineHeight": $json }""").toResolvedText(App8Appearance.Light, provider, naturalLineHeightEm = natural).style.lineHeight
        assertEquals((1.44f * 1.4f).em, lh(""""fontFamily": "Brand-Regular", """, """{ "type": "multiplier", "value": 1.4 }"""))
        assertEquals((10 * 1.44f + 4).sp.value, lh(""""fontFamily": "Brand-Regular", """, """{ "type": "interLineSpacing", "value": 4 }""").value, 1e-4f)
        assertEquals(22.sp, lh(""""fontFamily": "Brand-Regular", """, """{ "type": "fixed", "value": 22 }"""))
        // The system font keeps SF Pro's natural height.
        assertEquals((IOS_NATURAL_LINE_HEIGHT_EM * 1.4f).em, lh("", """{ "type": "multiplier", "value": 1.4 }"""))
    }

    @Test
    fun `fonts resolve in iOS order and custom fonts never synthesize`() {
        val inter = FontFamily.SansSerif
        val brand = FontFamily.Serif
        val provider = App8FontProvider { name, _ ->
            when (name) {
                "Inter-Bold" -> inter
                "Brand Sans" -> brand
                "system" -> FontFamily.Default
                else -> null
            }
        }
        val font = """"font": { "family": { "displayName": "Brand Sans", "isSystemFont": false } }"""
        val byPostScript = TestStyles.text("""{ "fontSize": 12, "fontFamily": "Inter-Bold", $font }""").toResolvedText(App8Appearance.Light, provider)
        assertEquals(inter, byPostScript.style.fontFamily)
        assertEquals(FontSynthesis.None, byPostScript.style.fontSynthesis)
        val byFamily = TestStyles.text("""{ "fontSize": 12, "fontFamily": "Missing", $font }""").toResolvedText(App8Appearance.Light, provider)
        assertEquals(brand, byFamily.style.fontFamily)
        val system = TestStyles.text("""{ "fontSize": 12, "fontWeight": "bold" }""").toResolvedText(App8Appearance.Light, provider)
        assertEquals(FontFamily.Default, system.style.fontFamily)
        assertNull(system.style.fontSynthesis)
        assertEquals(FontWeight.Bold, system.style.fontWeight)
    }

    @Test
    fun `text transforms use the locale`() {
        assertEquals("İSTANBUL", TextTransform.Uppercase.apply("istanbul", Locale.forLanguageTag("tr")))
        assertEquals("Hello Big World", TextTransform.Capitalize.apply("hello big world"))
        assertEquals("abc", TextTransform.Lowercase.apply("ABC"))
    }

    @Test
    fun `a fixed line below the font's own height is the value on every line`() {
        // The resolved font is 1.17 × its size tall on this platform (ascent + descent).
        fun fixed(size: Int, value: Int, em: Float? = 1.17f) =
            TestStyles.text("""{ "fontSize": $size, "lineHeight": { "type": "fixed", "value": $value } }""")
                .toResolvedText(App8Appearance.Light, naturalLineHeightEm = { _, _ -> em }).style
        // 40 pt on a 36 pt line (font height 46.8): first and last line are trimmed too, so n
        // lines are n × 36 and the glyphs overflow by the same amount above and below.
        val tight = fixed(40, 36)
        assertEquals(36.sp, tight.lineHeight)
        assertEquals(LineHeightStyle.Alignment.Center, tight.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.Both, tight.lineHeightStyle?.trim)
        assertEquals(LineHeightStyle.Mode.Tight, tight.lineHeightStyle?.mode)
        // A line above the font's height keeps its leading on the first and the last line.
        for ((size, value) in listOf(14 to 22, 12 to 20, 17 to 28, 11 to 24, 40 to 47)) {
            val roomy = fixed(size, value)
            assertEquals(LineHeightStyle.Alignment.Center, roomy.lineHeightStyle?.alignment)
            assertEquals(LineHeightStyle.Trim.None, roomy.lineHeightStyle?.trim)
            assertEquals(LineHeightStyle.Mode.Fixed, roomy.lineHeightStyle?.mode)
        }
        // Font height unknown: never trims.
        assertEquals(LineHeightStyle.Trim.None, fixed(40, 36, em = null).lineHeightStyle?.trim)
    }

    @Test
    fun `only decorated text with letter spacing is trimmed at the line ends`() {
        fun style(json: String) = TestStyles.text(json).toResolvedText(App8Appearance.Light)
        assertEquals(true, style("""{ "fontSize": 16, "letterSpacing": 4, "underline": true }""").style.hasSpacedDecoration)
        assertEquals(true, style("""{ "fontSize": 16, "letterSpacing": 1.5, "strikethrough": true }""").style.hasSpacedDecoration)
        // Negative spacing too: the glyphs then reach beyond the line.
        assertEquals(true, style("""{ "fontSize": 16, "letterSpacing": -1, "underline": true }""").style.hasSpacedDecoration)
        // No decoration: the label keeps its width, whatever the spacing.
        assertEquals(false, style("""{ "fontSize": 16, "letterSpacing": 4 }""").style.hasSpacedDecoration)
        assertEquals(false, style("""{ "fontSize": 16, "letterSpacing": 4, "underline": false }""").style.hasSpacedDecoration)
        assertEquals(false, style("""{ "fontSize": 16, "letterSpacing": 4 }""").style.isDecorated)
        // No spacing: nothing lies outside the glyphs.
        assertEquals(false, style("""{ "fontSize": 16, "underline": true }""").style.hasSpacedDecoration)
        assertEquals(false, style("""{ "fontSize": 16, "letterSpacing": 0, "underline": true }""").style.hasSpacedDecoration)
        assertEquals(true, style("""{ "fontSize": 16, "underline": true }""").style.isDecorated)
        // The trim itself is measured by the renderer, for labels; the style alone never trims.
        assertEquals(0f, style("""{ "fontSize": 16, "letterSpacing": 4, "underline": true }""").decorationEdgeTrim, 0f)
    }

    @Test
    fun `edge letter spacing is half the spacing where one glyph gains it, and none elsewhere`() {
        // Up to Android 14: a lone glyph of 30 px measures 41 px with 10.5 px of letter spacing
        // (whole pixels), so each end of a line carries 5.25 px.
        assertEquals(5.25f, edgeLetterSpacing(spacedWidth = 41f, plainWidth = 30f, spacingPx = 10.5f), 0f)
        // Negative spacing there: the glyph loses it, each end of a line is 2 px short of its glyphs.
        assertEquals(-2f, edgeLetterSpacing(spacedWidth = 26f, plainWidth = 30f, spacingPx = -4f), 0f)
        // Android 15 and later: spacing between glyphs only, a lone glyph gains nothing.
        assertEquals(0f, edgeLetterSpacing(spacedWidth = 30f, plainWidth = 30f, spacingPx = 10.5f), 0f)
        assertEquals(0f, edgeLetterSpacing(spacedWidth = 31f, plainWidth = 30f, spacingPx = 10.5f), 0f)
        assertEquals(0f, edgeLetterSpacing(spacedWidth = 30f, plainWidth = 30f, spacingPx = -4f), 0f)
        assertEquals(0f, edgeLetterSpacing(41f, 30f, spacingPx = 0f), 0f)
        assertEquals(0f, edgeLetterSpacing(Float.NaN, 30f, 10.5f), 0f)
    }

    @Test
    fun `a decoration runs from the first glyph of a line to the end of the last`() {
        // A line from 100 to 517 px with 5.25 px of spacing outside its first and last glyph.
        assertEquals(105.25f to 511.75f, decorationRange(left = 100f, right = 517f, trim = 5.25f))
        assertEquals(100f to 517f, decorationRange(100f, 517f, 0f))
        // Negative spacing: the glyphs reach 2 px beyond the line at both ends.
        assertEquals(98f to 519f, decorationRange(100f, 517f, -2f))
        // An empty line, or one with nothing left: no decoration.
        assertEquals(null, decorationRange(100f, 100f, 0f))
        assertEquals(null, decorationRange(100f, 110f, 5.25f))
    }
}
