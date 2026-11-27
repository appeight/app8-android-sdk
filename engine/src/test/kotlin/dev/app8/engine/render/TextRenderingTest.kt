package dev.app8.engine.render

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import dev.app8.engine.render.components.DslText
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.app8.engine.render.components.PickerKind
import dev.app8.engine.render.components.TextInputKind
import dev.app8.engine.render.components.decorationMetrics
import dev.app8.engine.render.components.decorationSegments
import dev.app8.engine.render.components.drawDecorations
import dev.app8.engine.render.components.slackWidth
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8FontProvider
import dev.app8.engine.style.ResolvedText
import dev.app8.engine.style.TestStyles
import dev.app8.engine.style.TextSizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text as the platform lays it out and draws it (Robolectric native graphics), on both
 * letter-spacing behaviours: API 34 puts half a spacing outside the first and the last glyph
 * of a line, API 35 does not. Every test runs on both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34, 35])
class TextRenderingTest {
    private val density = Density(2.625f)
    private val fonts = createFontFamilyResolver(RuntimeEnvironment.getApplication())
    private val measurer = TextMeasurer(fonts, density, LayoutDirection.Ltr)
    private val rt = runtime(mapOf("s" to """{"type":"screen","id":"s","content":{}}"""))
    private val env = RenderEnv(rt, App8Appearance.Light, density, measurer, App8FontProvider.System, TextSizer.FollowSystem)

    private fun px(points: Double) = (points * density.density).toFloat()
    private fun label(json: String, lines: Int = 0) =
        env.labelText(TestStyles.text(json), 17f, Color.Red, lines).let { it.copy(style = it.style.copy(color = Color.Red)) }

    /** The advance of [text] with no letter spacing at all, in px. */
    private fun advance(text: String, style: TextStyle): Float {
        val plain = TextStyle(fontFamily = style.fontFamily, fontWeight = style.fontWeight, fontSize = style.fontSize)
        return measurer.measure(AnnotatedString(text.repeat(40)), plain, maxLines = 1, softWrap = false).getLineRight(0) / 40f
    }

    private fun spacingPx(style: TextStyle) = with(density) { style.fontSize.toPx() } * style.letterSpacing.value

    /** A label as `DslText` lays it out and draws it in a frame [frame] px wide: glyphs red, decoration blue. */
    private inner class Drawn(text: String, val style: ResolvedText, val frame: Int) {
        val pad = style.decorationEdgeTrim.roundToInt()
        val layout: TextLayoutResult = measurer.measure(
            AnnotatedString(text), style.style.copy(textDecoration = TextDecoration.None),
            overflow = style.overflow, softWrap = style.softWrap && style.maxLines != 1, maxLines = style.maxLines,
            constraints = Constraints.fixedWidth(slackWidth(frame, pad)),
        )
        private val margin = 60
        private val w = layout.size.width + 2 * margin
        private val h = layout.size.height + 2 * margin
        private val pixels = IntArray(w * h)

        init {
            val image = ImageBitmap(w, h)
            val segments = decorationSegments(layout, AnnotatedString(text), style.decorationEdgeTrim)
            val metrics = decorationMetrics(fonts, style.style, density)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                translate(margin.toFloat(), margin.toFloat()) {
                    drawText(layout)
                    drawDecorations(segments, metrics, style.style.textDecoration!!, Color.Blue)
                }
            }
            image.asAndroidBitmap().getPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun alpha(x: Int, y: Int, blue: Boolean): Int {
            val p = pixels[y * w + x]
            val a = p ushr 24
            val isBlue = (p and 0xFF) > ((p shr 16) and 0xFF)
            return if (a > 0 && isBlue == blue) a else 0
        }

        /** Left and right edge of the red (glyphs) or blue (decoration) ink of [line], in the label's px. */
        fun ink(line: Int, blue: Boolean): Pair<Float, Float> {
            val rows = (layout.getLineTop(line).toInt() + margin) until (layout.getLineBottom(line).toInt() + margin)
            // The row with the most ink: the middle of the bar for a decoration.
            val row = rows.maxBy { y -> (0 until w).sumOf { x -> alpha(x, y, blue) } }
            val scan = if (blue) row..row else rows
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (y in scan) {
                val first = (0 until w).firstOrNull { alpha(it, y, blue) > 0 } ?: continue
                val last = (0 until w).last { alpha(it, y, blue) > 0 }
                left = minOf(left, first + 1f - alpha(first, y, blue) / 255f)
                right = maxOf(right, last + alpha(last, y, blue) / 255f)
            }
            assertTrue(right > left, "no ${if (blue) "decoration" else "glyphs"} on line $line")
            // Bitmap → text → label: the text is placed 1 + pad px left of the label.
            val shift = margin + 1 + pad
            return left - shift to right - shift
        }
    }

    private fun spaced(spacing: Double, extra: String = """"underline": true""") =
        """{ "fontSize": 16, "letterSpacing": $spacing, $extra }"""

    @Test
    fun `an underline is as wide as the glyphs of its line and lies under them`() {
        for (spacing in listOf(4.0, 1.0, 0.0, -1.5)) {
            val style = label(spaced(spacing), lines = 0)
            val h = advance("H", style.style)
            val s = spacingPx(style.style).takeIf { spacing != 0.0 } ?: 0f
            // One line, then wrapped after a space, then broken by a newline.
            for ((text, maxWidth, lines) in listOf(Triple("HHHHHH", null, listOf(6)), Triple("HHHHH HHH", 7.0 * (h + s) / density.density, listOf(5, 3)), Triple("HHHH\nHH", null, listOf(4, 2)))) {
                val size = env.measure(AnnotatedString(text), style, maxWidth)
                val frame = ceil(px(size.width) - 0.01f).toInt()
                val drawn = Drawn(text, style, frame)
                assertEquals(lines.size, drawn.layout.lineCount, "$text at $spacing")
                var widest = 0f
                for ((line, n) in lines.withIndex()) {
                    // n glyphs and the n - 1 spacings between them: no spacing after the last.
                    val expected = n * h + (n - 1) * s
                    widest = maxOf(widest, expected)
                    val (from, to) = drawn.ink(line, blue = true)
                    assertEquals(expected, to - from, 1f, "underline width, '$text' line $line, spacing $spacing")
                    val (inkFrom, inkTo) = drawn.ink(line, blue = false)
                    assertEquals((inkFrom + inkTo) / 2f, (from + to) / 2f, 1f, "underline centred on the glyphs, '$text' line $line, spacing $spacing")
                    // In the label: it starts at the label's left edge (text is laid out 1 px left of it).
                    assertEquals(-1f, from, 1f, "underline start, '$text' line $line, spacing $spacing")
                }
                // measure(): the label is as wide as its widest line of glyphs, rounded up to pixels.
                // (The platform reports line widths in whole pixels and adds one for letter spacing.)
                assertTrue(px(size.width) - widest in -0.01f..2.01f, "label width ${px(size.width)} for glyphs of $widest, '$text', spacing $spacing")
            }
        }
    }

    @Test
    fun `a truncated line is underlined up to its ellipsis`() {
        for (spacing in listOf(4.0, 0.0, -1.5)) {
            val style = label(spaced(spacing)).copy(maxLines = 1)
            val text = "H".repeat(30)
            val frame = 400
            val drawn = Drawn(text, style, frame)
            assertTrue(drawn.layout.isLineEllipsized(0))
            val shown = drawn.layout.getLineEnd(0, visibleEnd = true)
            assertTrue(shown in 3..29, "shown $shown")
            val s = spacingPx(style.style).takeIf { spacing != 0.0 } ?: 0f
            val expected = shown * advance("H", style.style) + shown * s + advance("…", style.style)
            val (from, to) = drawn.ink(0, blue = true)
            assertEquals(expected, to - from, 1f, "underline width, spacing $spacing")
            assertEquals(-1f, from, 1f, "underline start, spacing $spacing")
            // Never past the dots (their side bearing aside), never past the label.
            val (_, inkTo) = drawn.ink(0, blue = false)
            assertTrue(to - inkTo in -0.5f..6f, "underline ends ${to - inkTo} px after the ellipsis, spacing $spacing")
            assertTrue(to <= frame + 1f, "underline ends at $to in a $frame px label, spacing $spacing")
        }
    }

    @Test
    fun `the decoration drawn here is the platform's own`() {
        for (decoration in listOf("underline", "strikethrough")) {
            val style = label("""{ "fontSize": 16, "$decoration": true }""", lines = 1)
            val text = AnnotatedString("Hxgp")
            fun render(platform: Boolean): IntArray {
                val layout = measurer.measure(text, if (platform) style.style else style.style.copy(textDecoration = TextDecoration.None), maxLines = 1)
                val image = ImageBitmap(layout.size.width, layout.size.height + 20)
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
                    drawText(layout)
                    if (!platform) drawDecorations(decorationSegments(layout, text, 0f), decorationMetrics(fonts, style.style, this@TextRenderingTest.density), style.style.textDecoration!!, Color.Red)
                }
                return IntArray(image.width * image.height).also { image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            }
            val ours = render(platform = false)
            val theirs = render(platform = true)
            val plain = measurer.measure(text, style.style.copy(textDecoration = TextDecoration.None), maxLines = 1).let { l ->
                val image = ImageBitmap(l.size.width, l.size.height + 20)
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) { drawText(l) }
                IntArray(image.width * image.height).also { image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
            }
            assertTrue(theirs.indices.count { theirs[it] != plain[it] } > 50, "the platform drew no $decoration")
            // The platform reports a line's width in whole pixels, so the bar drawn here can end a
            // fraction of a pixel after the platform's: the last column is left out.
            val width = measurer.measure(text, style.style, maxLines = 1).size.width
            val worst = ours.indices.filter { it % width < width - 1 }.maxOf { abs((ours[it] ushr 24) - (theirs[it] ushr 24)) }
            assertTrue(worst <= 2, "$decoration differs from the platform's by up to $worst / 255 alpha")
        }
    }

    @Test
    fun `a block of fixed lines is n times the line height, the glyphs centred in each line`() {
        // 40 pt on 36 pt lines (below the font's own height: trimmed, tight) and 14 pt on 22 pt lines.
        for ((size, value) in listOf(40 to 36, 14 to 22, 17 to 28)) {
            val style = env.labelText(TestStyles.text("""{ "fontSize": $size, "lineHeight": { "type": "fixed", "value": $value } }"""), 17f, Color.Red, 0)
            for (n in 1..4) {
                val text = AnnotatedString(List(n) { "Hg" }.joinToString("\n"))
                assertEquals(n * value.toDouble(), env.measure(text, style, 300.0).height, n / density.density + 0.01, "$n lines of $size on $value")
            }
            // Half-leading: what the line has over (or under) ascent + descent, half above the glyphs.
            val metrics = Paint().apply { typeface = fonts.resolve(FontFamily.Default).value as Typeface; textSize = px(size.toDouble()) }.fontMetrics
            val line = px(value.toDouble())
            val expectedBaseline = (line - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            val layout = measurer.measure(AnnotatedString("Hg\nHg\nHg"), style.style)
            for (i in 0 until 3) assertEquals(i * line + expectedBaseline, layout.getLineBaseline(i), 1.5f, "baseline of line $i, $size on $value")
        }
    }

    @Test
    fun `controls that draw their own text keep the whole line as their frame`() {
        val decorated = """{ "fontSize": 16, "letterSpacing": 4, "underline": true }"""
        // What the control's own text draws: the platform's line, edge spacing included.
        fun platformWidth(text: String, style: ResolvedText) =
            measurer.measure(AnnotatedString(text), style.style, maxLines = 1, softWrap = false).size.width / density.density.toDouble()
        val plain = env.text(TestStyles.text(decorated), 15f, Color.Black, 1)

        val session = session(runtime(mapOf("s" to """{"type":"screen","id":"s","content":{"children":[
          {"type":"picker","id":"pulldown","content":{"properties":{"placeholder":"SELECT SIZE"},"style":{"label":$decorated,"indicator":""}}},
          {"type":"picker","id":"menu","content":{"properties":{"placeholder":"SELECT SIZE"},"style":{"text":$decorated}}},
          {"type":"textField","id":"field","content":{"properties":{"placeholder":"SEARCH HERE"},"style":{"placeholder":$decorated}}}
        ]}}""")))
        val title = platformWidth("SELECT SIZE", plain)
        val pulldown = PickerKind.traits(session.root.find("pulldown")!!, env).intrinsic!!.measure(null)!!
        assertTrue(pulldown.width >= title - 0.01, "pull-down ${pulldown.width} < its title $title")
        val menu = PickerKind.traits(session.root.find("menu")!!, env).intrinsic!!.measure(null)!!
        assertTrue(menu.width >= title + 16 * 1.1 + 4.0 - 0.01, "menu ${menu.width} < its title $title and chevron")
        val field = TextInputKind(multiline = false).traits(session.root.find("field")!!, env).intrinsic!!.measure(null)!!
        val placeholder = platformWidth("SEARCH HERE", plain)
        assertTrue(field.width >= placeholder + 24.0 - 0.01, "field ${field.width} < its placeholder $placeholder and padding")
        assertEquals(0f, plain.decorationEdgeTrim)
        assertTrue(env.measure(AnnotatedString("SIZE"), plain, null).width >= platformWidth("SIZE", plain) - 0.01)
        // A label with the same style is the glyphs only: narrower where the platform has edge spacing.
        val label = env.labelText(TestStyles.text(decorated), 15f, Color.Black, 1)
        assertEquals(platformWidth("SELECT SIZE", plain) - 2 * label.decorationEdgeTrim / density.density, env.measure(AnnotatedString("SELECT SIZE"), label, null).width, 0.5)
    }

    @Test
    fun `a label on screen draws its underline from edge to edge of its frame`() {
        for (spacing in listOf(4.0, -1.5)) {
            val style = label(spaced(spacing)).copy(maxLines = 1)
            val text = AnnotatedString("HHHHHH")
            val size = env.measure(text, style, null)
            val frame = ceil(px(size.width) - 0.01f).toInt()
            val glyphs = 6 * advance("H", style.style) + 5 * spacingPx(style.style)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val left = 40
            activity.setContent {
                CompositionLocalProvider(LocalDensity provides density) {
                    Layout(content = { DslText(text, style, Modifier.fillMaxWidth()) }) { measurables, _ ->
                        val p = measurables.single().measure(Constraints.fixedWidth(frame))
                        layout(300, 200) { p.place(left, 40) }
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            val w = 300
            val h = 200
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            activity.window.decorView.draw(android.graphics.Canvas(bitmap))
            val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            fun red(x: Int, y: Int) = pixels[y * w + x].let { p -> if (((p shr 16) and 0xFF) > 2 * (p and 0xFF)) p ushr 24 else 0 }
            // The underline is the row with the most ink; the glyphs are the rows above it.
            val bar = (0 until h).maxBy { y -> (0 until w).sumOf { red(it, y) } }
            val first = (0 until w).first { red(it, bar) > 0 }
            val last = (0 until w).last { red(it, bar) > 0 }
            val from = first + 1f - red(first, bar) / 255f - left
            val to = last + red(last, bar) / 255f - left
            assertEquals(glyphs, to - from, 1f, "underline width on screen, spacing $spacing")
            assertEquals(-1f, from, 1f, "underline start in the frame, spacing $spacing")
            assertTrue(to <= frame + 0.5f, "underline ends at $to in a $frame px frame, spacing $spacing")
            val ink = (0 until bar - 3).flatMap { y -> (0 until w).filter { red(it, y) > 0 } }
            assertTrue(ink.isNotEmpty(), "no glyphs drawn")
            assertEquals((ink.min() + ink.max() + 1) / 2f - left, (from + to) / 2f, 1f, "underline centred on the glyphs, spacing $spacing")
        }
    }

    @Test
    fun `a color span colors its part of the decoration`() {
        val style = label(spaced(4.0)).copy(maxLines = 1)
        val plain = AnnotatedString("HHHHHH")
        val spanned = AnnotatedString("HHHHHH", listOf(AnnotatedString.Range(SpanStyle(color = Color.Green), 2, 4)))
        val layout = measurer.measure(spanned, style.style.copy(textDecoration = TextDecoration.None), maxLines = 1)
        val whole = decorationSegments(layout, plain, style.decorationEdgeTrim).single()
        val parts = decorationSegments(layout, spanned, style.decorationEdgeTrim)
        assertEquals(listOf(Color.Unspecified, Color.Green, Color.Unspecified), parts.map { it.color })
        assertEquals(whole.from, parts.first().from)
        assertEquals(whole.to, parts.last().to)
        for (i in 0..1) assertEquals(parts[i].to, parts[i + 1].from)
        // Glyphs 3 and 4 of 6, with the spacing around them: a third of the line.
        assertEquals((whole.to - whole.from) / 3f, parts[1].to - parts[1].from, spacingPx(style.style))
    }
}
