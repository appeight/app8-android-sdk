package dev.app8.engine.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
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
        assertEquals(LineHeightStyle.Alignment.Bottom, fixed.lineHeightStyle?.alignment)
        assertEquals((IOS_NATURAL_LINE_HEIGHT_EM * 1.5f).em, lh("""{ "type": "multiplier", "value": 1.5 }""").lineHeight)
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
        val shrink = r("""{ "fontSize": 12, "adjustsFontSizeToFitWidth": true }""")
        assertEquals(true, shrink.adjustsFontSizeToFitWidth)
        assertEquals(0.5f, shrink.minimumScaleFactor)
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
}
