package dev.app8.engine.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.style.Corner

/**
 * A rounded rectangle with continuous (squircle) corners, the outline of
 * `CALayer.cornerCurve = .continuous`. Radii are in dp, per corner;
 * [smoothing] `0` draws plain circular corners.
 *
 * Absolute (not start/end) corners: DSL layout is not mirrored for RTL either.
 */
@Immutable
internal class ContinuousRoundedShape(
    private val topLeft: Dp,
    private val topRight: Dp,
    private val bottomRight: Dp,
    private val bottomLeft: Dp,
    private val smoothing: Float = IOS_CORNER_SMOOTHING,
) : Shape {

    constructor(radius: Dp, smoothing: Float = IOS_CORNER_SMOOTHING) : this(radius, radius, radius, radius, smoothing)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radii = with(density) { CornerRadii(topLeft.toPx(), topRight.toPx(), bottomRight.toPx(), bottomLeft.toPx()) }
        return outlineOf(Rect(0f, 0f, size.width, size.height), radii, smoothing)
    }

    override fun equals(other: Any?): Boolean =
        other is ContinuousRoundedShape && other.topLeft == topLeft && other.topRight == topRight &&
            other.bottomRight == bottomRight && other.bottomLeft == bottomLeft && other.smoothing == smoothing

    override fun hashCode(): Int {
        var h = topLeft.hashCode()
        h = 31 * h + topRight.hashCode()
        h = 31 * h + bottomRight.hashCode()
        h = 31 * h + bottomLeft.hashCode()
        return 31 * h + smoothing.hashCode()
    }

    override fun toString(): String = "ContinuousRoundedShape($topLeft, $topRight, $bottomRight, $bottomLeft, smoothing=$smoothing)"
}

/**
 * The shape of a DSL [Corner]: radius resolved against the box (fraction / capsule
 * are size-relative, so the shape resolves them in [createOutline]), `continuous`
 * curve → smoothing 0.6, `circular` → 0.
 */
@Immutable
internal data class DslCornerShape(val corner: Corner) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.radius.resolved(size.width.toDp().value.toDouble(), size.height.toDp().value.toDouble()).toFloat().dp.toPx() }
        return outlineOf(Rect(0f, 0f, size.width, size.height), CornerRadii(r), corner.smoothing)
    }
}

internal val Corner.smoothing: Float
    get() = if (curve == Corner.Curve.continuous) IOS_CORNER_SMOOTHING else 0f

/** Outline of [rect] with [radii] (px). Circular corners use the platform round rect. */
internal fun outlineOf(rect: Rect, radii: CornerRadii, smoothing: Float): Outline {
    if (radii.isZero) return Outline.Rectangle(rect)
    return Outline.Generic(Path().apply { addRoundedRect(rect, radii, smoothing) })
}

/** Appends a rounded [rect] with [radii] (px). Clamps every radius to half the shorter side like UIKit. */
internal fun Path.addRoundedRect(rect: Rect, radii: CornerRadii, smoothing: Float) =
    roundedRect(ComposePathSink(this), rect, radii, smoothing)

/**
 * [Path.addRoundedRect] into any [sink]. A square-cornered rect is drawn with lines, never
 * `addRect`: before Android 13 a path made only of `addRect` calls is a "simple path" that
 * HWUI draws as the union of its rects, ignoring the fill type, so an even-odd outline ring
 * (two rects) filled solid.
 */
internal fun roundedRect(sink: PathSink, rect: Rect, radii: CornerRadii, smoothing: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    if (radii.isZero) {
        sink.moveTo(rect.left, rect.top)
        sink.lineTo(rect.right, rect.top)
        sink.lineTo(rect.right, rect.bottom)
        sink.lineTo(rect.left, rect.bottom)
        sink.close()
        return
    }
    continuousRoundedRect(sink, rect.left, rect.top, rect.width, rect.height, radii, smoothing)
}

private class ComposePathSink(private val path: Path) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        path.cubicTo(x1, y1, x2, y2, x3, y3)

    override fun arc(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float) =
        path.arcTo(Rect(cx - radius, cy - radius, cx + radius, cy + radius), startDeg, sweepDeg, forceMoveTo = false)

    override fun close() = path.close()
}
