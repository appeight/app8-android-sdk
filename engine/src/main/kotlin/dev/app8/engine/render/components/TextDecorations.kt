package dev.app8.engine.render.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import dev.app8.engine.style.decorationRange
import kotlin.math.max

/**
 * One stretch of underline / strikethrough: [from]..[to] on the line whose baseline is at
 * [baseline], in the text's own pixels. [color]: the color of a span covering the stretch,
 * unspecified for the text's own color.
 */
internal class DecorationSegment(val from: Float, val to: Float, val baseline: Float, val color: Color)

/**
 * Where the platform draws the two decorations for a font and size, relative to the
 * baseline (down is positive), and how thick.
 */
internal class DecorationMetrics(
    val underlineTop: Float,
    val underlineThickness: Float,
    val strikeTop: Float,
    val strikeThickness: Float,
)

/**
 * The platform's own decoration metrics for [style] (`Paint.getUnderlinePosition` and
 * friends, which are what its text drawing uses), so a decoration drawn here lies exactly
 * where the platform would have put it. Without a platform typeface: the platform's defaults
 * for a font that has no metrics of its own.
 */
internal fun decorationMetrics(fonts: FontFamily.Resolver, style: TextStyle, density: Density): DecorationMetrics {
    val size = with(density) { style.fontSize.toPx() }.takeIf { it.isFinite() && it > 0f } ?: 0f
    val typeface = runCatching {
        fonts.resolve(
            style.fontFamily, style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All,
        ).value as? Typeface
    }.getOrNull()
    val paint = typeface?.let { Paint().apply { this.typeface = it; textSize = size } }
    return if (paint != null) {
        DecorationMetrics(paint.underlinePosition, paint.underlineThickness, paint.strikeThruPosition, paint.strikeThruThickness)
    } else {
        DecorationMetrics(size / 9f, size / 18f, -size * 6f / 21f, size / 18f)
    }
}

/**
 * The decoration of every line of [layout]: from the first glyph of the line to the end of
 * its last one ([decorationRange] with the letter spacing [trim] the platform puts outside
 * them), so not under the spaces a wrapped line ends with, and up to the ellipsis of a
 * truncated one. Split where a color span of [text] starts or ends.
 */
internal fun decorationSegments(layout: TextLayoutResult, text: AnnotatedString, trim: Float): List<DecorationSegment> {
    val spans = text.spanStyles.filter { it.item.color.isSpecified && it.end > it.start }
    val tail = layout.layoutInput.overflow == TextOverflow.Ellipsis
    val out = ArrayList<DecorationSegment>(layout.lineCount)
    for (line in 0 until layout.lineCount) {
        var left = layout.getLineLeft(line)
        var right = layout.getLineRight(line)
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line, visibleEnd = true)
        if (tail && layout.isLineEllipsized(line) && end + 1 <= text.length) {
            // The characters behind the ellipsis have no width, but where the platform puts
            // letter spacing around every glyph each still takes one (or gives one back, for
            // negative spacing): the line's edge counts them. The ink ends with the ellipsis,
            // which stands where the first hidden character was.
            val afterEllipsis = layout.getHorizontalPosition(end + 1, usePrimaryDirection = true)
            if (layout.getParagraphDirection(start) == ResolvedTextDirection.Ltr) right = afterEllipsis else left = afterEllipsis
        }
        val (from, to) = decorationRange(left, right, trim) ?: continue
        val baseline = layout.getLineBaseline(line)
        val inLine = spans.filter { it.start < end && it.end > start }
        if (inLine.isEmpty()) {
            out += DecorationSegment(from, to, baseline, Color.Unspecified)
            continue
        }
        // Color changes inside the line, in visual order.
        val cuts = sortedSetOf(from, to)
        for (s in inLine) for (offset in intArrayOf(s.start, s.end)) {
            if (offset in (start + 1) until end) cuts += layout.getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(from, to)
        }
        val edges = cuts.toList()
        for (i in 0 until edges.size - 1) {
            val mid = (edges[i] + edges[i + 1]) / 2f
            val at = layout.getOffsetForPosition(Offset(mid, (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f))
            val color = inLine.lastOrNull { at >= it.start && at < it.end }?.item?.color ?: Color.Unspecified
            out += DecorationSegment(edges[i], edges[i + 1], baseline, color)
        }
    }
    return out
}

/** Draws [segments] as the platform draws a decoration: a filled bar at least one pixel thick. */
internal fun DrawScope.drawDecorations(segments: List<DecorationSegment>, metrics: DecorationMetrics, decoration: TextDecoration, color: Color) {
    val underline = TextDecoration.Underline in decoration
    val strike = TextDecoration.LineThrough in decoration
    for (i in segments.indices) {
        val s = segments[i]
        val c = if (s.color.isSpecified) s.color else color
        if (underline) bar(s, metrics.underlineTop, metrics.underlineThickness, c)
        if (strike) bar(s, metrics.strikeTop, metrics.strikeThickness, c)
    }
}

private fun DrawScope.bar(s: DecorationSegment, top: Float, thickness: Float, color: Color) {
    drawRect(color, Offset(s.from, s.baseline + top), Size(s.to - s.from, max(thickness, 1f)))
}
