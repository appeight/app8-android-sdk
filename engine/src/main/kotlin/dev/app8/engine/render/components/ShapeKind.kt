package dev.app8.engine.render.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.app8.engine.core.model.Animation
import dev.app8.engine.core.model.ShapeProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.number
import dev.app8.engine.render.string
import dev.app8.engine.render.toSpec

/** `shape` (iOS `CShapeView`): arc / bar progress, circle, line, polyline. */
internal object ShapeKind : ComponentKind {
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? ShapeProperties ?: return Box(modifier)
        fun color(raw: String?): Color? = raw?.let { node.string(it) }?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }
        val stroke = color(p.strokeColor) ?: Color.Black
        val track = color(p.trackColor) ?: Color.Transparent
        val fill = color(p.fillColor)
        val target = (p.progress?.let { node.number(it.value) } ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        val animation = p.progress?.animation ?: Animation.Inline(duration = p.animationDuration ?: 0.4)
        val progress by animateFloatAsState(target, animation.toSpec(node), label = "progress")
        val points = p.points?.map { (node.number(it.x) ?: 0.0) to (node.number(it.y) ?: 0.0) }
        val lw = (p.lineWidth ?: 8.0).toFloat()
        val trackLw = (p.trackLineWidth ?: p.lineWidth ?: 8.0).toFloat()
        val cap = when (p.lineCap) {
            ShapeProperties.LineCap.butt -> StrokeCap.Butt
            ShapeProperties.LineCap.square -> StrokeCap.Square
            else -> StrokeCap.Round
        }
        Box(modifier) {
            Canvas(Modifier.fillMaxSize()) {
                val w = lw * density
                when (p.kind) {
                    ShapeProperties.Kind.arc -> {
                        val r = (minOf(size.width, size.height) / 2 - w / 2).coerceAtLeast(0f)
                        val topLeft = Offset(center.x - r, center.y - r)
                        val start = (p.startAngle ?: -90.0).toFloat()
                        drawArc(track, start, 360f, false, topLeft, Size(r * 2, r * 2), style = Stroke(trackLw * density, cap = cap))
                        if (progress > 0f) drawArc(stroke, start, 360f * progress, false, topLeft, Size(r * 2, r * 2), style = Stroke(w, cap = cap))
                    }
                    ShapeProperties.Kind.bar -> {
                        val off = if (cap == StrokeCap.Butt) 0f else w / 2
                        val y = size.height / 2
                        drawLine(track, Offset(off, y), Offset(size.width - off, y), trackLw * density, cap)
                        if (progress > 0f) drawLine(stroke, Offset(off, y), Offset(off + (size.width - 2 * off) * progress, y), w, cap)
                    }
                    ShapeProperties.Kind.circle -> {
                        val r = (minOf(size.width, size.height) - w) / 2
                        fill?.let { drawCircle(it, r) }
                        if (p.strokeColor != null) drawArc(stroke, -90f, 360f * (if (p.progress == null) 1f else progress), false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(w, cap = cap))
                    }
                    ShapeProperties.Kind.line -> {
                        if (size.width >= size.height) drawLine(stroke, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, cap)
                        else drawLine(stroke, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, cap)
                    }
                    ShapeProperties.Kind.polyline -> {
                        val pts = points?.map { (x, y) ->
                            Offset(if (x <= 1) (x * size.width).toFloat() else (x * density).toFloat(), if (y <= 1) (y * size.height).toFloat() else (y * density).toFloat())
                        }.orEmpty()
                        if (pts.size >= 2) {
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                if (p.smooth == true) catmullRom(pts, p.closed == true) else for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                if (p.closed == true) close()
                            }
                            if (p.closed == true && fill != null) drawPath(path, fill)
                            drawPath(path, stroke, style = Stroke(w, cap = cap))
                        }
                    }
                }
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }

    /** Centripetal-ish Catmull-Rom through [pts] as cubic Béziers (iOS `catmullRomPath`, uniform). */
    private fun Path.catmullRom(pts: List<Offset>, closed: Boolean) {
        val n = pts.size
        val segments = if (closed) n else n - 1
        for (i in 0 until segments) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            val p0 = if (closed) pts[(i - 1 + n) % n] else if (i > 0) pts[i - 1] else Offset(2 * p1.x - p2.x, 2 * p1.y - p2.y)
            val p3 = if (closed) pts[(i + 2) % n] else if (i + 2 < n) pts[i + 2] else Offset(2 * p2.x - p1.x, 2 * p2.y - p1.y)
            val c1 = Offset(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6)
            val c2 = Offset(p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6)
            cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
    }
}
