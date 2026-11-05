package dev.app8.engine.style

import android.graphics.Matrix
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.SweepGradientShader
import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.Gradient
import kotlin.math.atan2

/** One color stop; [position] in `0…1`. */
internal data class GradientStop(val position: Float, val color: Color)

/**
 * A resolved gradient. Points are in unit coordinates of the box it fills, as
 * `CAGradientLayer.startPoint` / `endPoint` are.
 */
@Immutable
internal sealed interface GradientSpec {
    val stops: List<GradientStop>

    /**
     * Axial gradient (`CAGradientLayer.type = .axial`, the only kind the DSL has today).
     * Drawn in unit space and stretched to the box, so on a non-square box the bands are
     * skewed exactly like Core Animation's (and SVG `objectBoundingBox`).
     */
    data class Linear(val start: Offset, val end: Offset, override val stops: List<GradientStop>) : GradientSpec

    /** `.radial`: [center] to an ellipse through [edge] (unit space, so it stretches with the box). */
    data class Radial(val center: Offset, val edge: Offset, override val stops: List<GradientStop>) : GradientSpec

    /** `.conic`: around [center], starting at the direction of [toward] (measured in px space). */
    data class Sweep(val center: Offset, val toward: Offset, override val stops: List<GradientStop>) : GradientSpec
}

/** The paint of a fill or outline: a solid color or a gradient. */
@Immutable
internal sealed interface DslPaint {
    data class Solid(val color: Color) : DslPaint
    data class Gradient(val spec: GradientSpec) : DslPaint
}

/**
 * iOS `Fill.LayerFactory`: a solid color wins; otherwise the gradient; otherwise nothing.
 * Unresolved pointers count as absent.
 */
internal fun Fill.toPaint(appearance: App8Appearance): DslPaint? {
    solid.colorOrNull(appearance)?.let { return DslPaint.Solid(it) }
    return gradient.value?.toSpec(appearance)?.let { DslPaint.Gradient(it) }
}

/**
 * iOS `colorsList` / `locationsList`: `[start, middle…, end]` at `[0, positions…, 1]`.
 * A missing color is clear (iOS drops a missing start color, which mis-pairs its
 * colors and locations; treating it as clear is what that renders closest to).
 */
internal fun Gradient.toSpec(appearance: App8Appearance): GradientSpec.Linear {
    val stops = buildList {
        add(GradientStop(0f, start.color.colorOrNull(appearance) ?: Color.Transparent))
        middlePoints.forEach { add(GradientStop(it.position.toFloat(), it.color.colorOrNull(appearance) ?: Color.Transparent)) }
        add(GradientStop(1f, end.color.colorOrNull(appearance) ?: Color.Transparent))
    }
    return GradientSpec.Linear(Offset(start.x.toFloat(), start.y.toFloat()), Offset(end.x.toFloat(), end.y.toFloat()), stops)
}

/**
 * Stop positions clamped to `0…1` and made non-decreasing: Skia requires that, Core
 * Animation tolerates anything. An out-of-order stop collapses onto its predecessor.
 */
internal fun monotonicStops(stops: List<GradientStop>): List<Float> {
    var last = 0f
    return stops.map { s -> maxOf(last, s.position.coerceIn(0f, 1f)).also { last = it } }
}

/** A brush painting [this] across [rect] (px, in the draw scope's coordinates). */
internal fun DslPaint.toBrush(rect: Rect): Brush = when (this) {
    is DslPaint.Solid -> SolidColor(color)
    is DslPaint.Gradient -> GradientBrush(spec, rect)
}

/** Shader brush pinned to [rect] (instead of the draw size) so outlines can paint their grown frame. */
private class GradientBrush(private val spec: GradientSpec, private val rect: Rect) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val colors = spec.stops.map { it.color }
        val positions = monotonicStops(spec.stops)
        val unitToRect = Matrix().apply {
            setScale(rect.width, rect.height)
            postTranslate(rect.left, rect.top)
        }
        return when (spec) {
            is GradientSpec.Linear ->
                LinearGradientShader(spec.start, spec.end, colors, positions).apply { setLocalMatrix(unitToRect) }
            is GradientSpec.Radial -> {
                val dx = spec.edge.x - spec.center.x
                val dy = spec.edge.y - spec.center.y
                // Unit circle radius 1 around the centre, then scaled to the ellipse.
                val rx = kotlin.math.abs(dx).coerceAtLeast(1e-4f)
                val ry = kotlin.math.abs(dy).coerceAtLeast(1e-4f)
                RadialGradientShader(Offset.Zero, 1f, colors, positions).apply {
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(rx, ry)
                            postTranslate(spec.center.x, spec.center.y)
                            postConcat(unitToRect)
                        },
                    )
                }
            }
            is GradientSpec.Sweep -> {
                val c = Offset(rect.left + spec.center.x * rect.width, rect.top + spec.center.y * rect.height)
                val t = Offset(rect.left + spec.toward.x * rect.width, rect.top + spec.toward.y * rect.height)
                val angle = Math.toDegrees(atan2((t.y - c.y).toDouble(), (t.x - c.x).toDouble())).toFloat()
                SweepGradientShader(c, colors, positions).apply {
                    setLocalMatrix(Matrix().apply { setRotate(angle, c.x, c.y) })
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is GradientBrush && other.spec == spec && other.rect == rect
    override fun hashCode(): Int = 31 * spec.hashCode() + rect.hashCode()
}
