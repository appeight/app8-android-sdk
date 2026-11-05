package dev.app8.engine.style

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Continuous ("squircle") corners, as CALayer.cornerCurve = .continuous draws
// them on iOS. A port of shoppu-web `render/corner.ts` (`continuousPath`), which
// reconstructs Figma's corner smoothing (figma-squircle, MIT): each corner is a
// cubic Bézier, a shorter circular arc and a mirrored cubic. Smoothing 0.6 is
// Figma's "iOS" preset and matches UIKit's curve to a fraction of a point.
//
// Pure Kotlin (no Compose / Android types) so the geometry is unit-testable on
// the JVM. [ContinuousRoundedShape] feeds it into a Compose `Path`.

/** Figma's "iOS" corner smoothing; matches `CALayerCornerCurve.continuous`. */
internal const val IOS_CORNER_SMOOTHING: Float = 0.6f

/** Per-corner radii in px (or points: the geometry is unit-free). */
internal data class CornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    constructor(all: Float) : this(all, all, all, all)

    val isZero: Boolean get() = topLeft <= 0f && topRight <= 0f && bottomRight <= 0f && bottomLeft <= 0f

    fun map(f: (Float) -> Float): CornerRadii = CornerRadii(f(topLeft), f(topRight), f(bottomRight), f(bottomLeft))

    companion object {
        val ZERO: CornerRadii = CornerRadii(0f)
    }
}

/** Receives absolute path segments. Angles in degrees, y down (Android / Compose convention). */
internal interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)

    /** Arc of the circle centred at ([cx], [cy]) with [radius], starting at [startDeg], sweeping [sweepDeg]. */
    fun arc(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float)
    fun close()
}

/** corner.ts `cornerParams`: the Bézier / arc lengths of one corner. */
internal data class CornerParams(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    /** Distance along each edge the corner occupies (`(1 + smoothing) * r`, capped by the budget). */
    val p: Float,
    /** Projected length of the circular arc (both axes). */
    val arc: Float,
    val r: Float,
    /** Effective smoothing after the budget cap. */
    val smoothing: Float,
) {
    companion object {
        val NONE: CornerParams = CornerParams(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    }
}

internal fun cornerParams(radius: Float, smoothing: Float, budget: Float): CornerParams {
    val r = min(radius, budget)
    if (r <= 0f) return CornerParams.NONE
    // The transition may not run past the middle of the side.
    val s = max(0.0, min(smoothing.toDouble(), budget.toDouble() / r - 1))
    val p = min((1 + s) * r, budget.toDouble())
    fun rad(deg: Double) = deg * PI / 180
    val arcMeasure = 90 * (1 - s)
    val arc = sin(rad(arcMeasure / 2)) * r * sqrt(2.0)
    val alpha = (90 - arcMeasure) / 2
    val p3p4 = r * tan(rad(alpha / 2))
    val beta = 45 * s
    val c = p3p4 * cos(rad(beta))
    val d = c * tan(rad(beta))
    val b = (p - arc - c - d) / 3
    val a = 2 * b
    return CornerParams(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat(), p.toFloat(), arc.toFloat(), r, s.toFloat())
}

/**
 * Emits a [w]×[h] rectangle at ([x], [y]) with continuous corners into [sink]: the same
 * outline as corner.ts `continuousPath`, generalised to per-corner radii. Each corner's
 * budget is `min(w, h) / 2` as in corner.ts, so a capsule falls back to a circular end
 * the way UIKit's does. `smoothing = 0` gives plain circular corners.
 */
internal fun continuousRoundedRect(
    sink: PathSink,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radii: CornerRadii,
    smoothing: Float = IOS_CORNER_SMOOTHING,
) {
    if (w <= 0f || h <= 0f) return
    val budget = min(w, h) / 2
    val tl = cornerParams(radii.topLeft, smoothing, budget)
    val tr = cornerParams(radii.topRight, smoothing, budget)
    val br = cornerParams(radii.bottomRight, smoothing, budget)
    val bl = cornerParams(radii.bottomLeft, smoothing, budget)
    val right = x + w
    val bottom = y + h

    sink.moveTo(right - tr.p, y)
    // Top-right.
    if (tr.r > 0f) {
        val (a, b, c, d) = tr
        val sx = right - tr.p
        sink.cubicTo(sx + a, y, sx + a + b, y, sx + a + b + c, y + d)
        sink.arc(right - tr.r, y + tr.r, tr.r, -90f + 45f * tr.smoothing, 90f * (1 - tr.smoothing))
        val ex = sx + a + b + c + tr.arc
        val ey = y + d + tr.arc
        sink.cubicTo(ex + d, ey + c, ex + d, ey + b + c, ex + d, ey + a + b + c)
    }
    sink.lineTo(right, bottom - br.p)
    // Bottom-right.
    if (br.r > 0f) {
        val (a, b, c, d) = br
        val sy = bottom - br.p
        sink.cubicTo(right, sy + a, right, sy + a + b, right - d, sy + a + b + c)
        sink.arc(right - br.r, bottom - br.r, br.r, 45f * br.smoothing, 90f * (1 - br.smoothing))
        val ex = right - d - br.arc
        val ey = sy + a + b + c + br.arc
        sink.cubicTo(ex - c, ey + d, ex - (b + c), ey + d, ex - (a + b + c), ey + d)
    }
    sink.lineTo(x + bl.p, bottom)
    // Bottom-left.
    if (bl.r > 0f) {
        val (a, b, c, d) = bl
        val sx = x + bl.p
        sink.cubicTo(sx - a, bottom, sx - (a + b), bottom, sx - (a + b + c), bottom - d)
        sink.arc(x + bl.r, bottom - bl.r, bl.r, 90f + 45f * bl.smoothing, 90f * (1 - bl.smoothing))
        val ex = sx - (a + b + c) - bl.arc
        val ey = bottom - d - bl.arc
        sink.cubicTo(ex - d, ey - c, ex - d, ey - (b + c), ex - d, ey - (a + b + c))
    }
    sink.lineTo(x, y + tl.p)
    // Top-left.
    if (tl.r > 0f) {
        val (a, b, c, d) = tl
        val sy = y + tl.p
        sink.cubicTo(x, sy - a, x, sy - (a + b), x + d, sy - (a + b + c))
        sink.arc(x + tl.r, y + tl.r, tl.r, 180f + 45f * tl.smoothing, 90f * (1 - tl.smoothing))
        val ex = x + d + tl.arc
        val ey = sy - (a + b + c) - tl.arc
        sink.cubicTo(ex + c, ey - d, ex + b + c, ey - d, ex + a + b + c, ey - d)
    }
    sink.close()
}

/**
 * Corner radius of a DSL corner for a box, clamped like UIKit clamps `CALayer.cornerRadius`
 * (never more than half the shorter side).
 */
internal fun clampRadius(radius: Float, w: Float, h: Float): Float = max(0f, min(radius, min(w, h) / 2))
