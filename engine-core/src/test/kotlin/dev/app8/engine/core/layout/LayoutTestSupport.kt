package dev.app8.engine.core.layout

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A label of [width] that wraps into [lineHeight] lines when narrower. */
internal fun text(width: Double, lineHeight: Double = 20.0) = IntrinsicMeasure { max ->
    val lines = if (max != null && max < width) ceil(width / max) else 1.0
    Size(if (max != null) min(width, max) else width, lines * lineHeight)
}

internal fun fixedSize(width: Double, height: Double) = IntrinsicMeasure { Size(width, height) }

/** UILabel's default priorities. */
internal val LABEL_HUGGING = AxisValues(251f, 251f)

internal fun label(
    key: String,
    width: Double,
    height: Double = 20.0,
    id: String? = null,
    layout: Layout? = null,
    hidden: Boolean = false,
) = LayoutNode(
    key = key,
    id = id,
    layout = layout,
    hidden = hidden,
    intrinsicSize = fixedSize(width, height),
    hugging = LABEL_HUGGING,
)

internal fun screen(vararg children: LayoutNode) = LayoutNode(key = "root", children = children.toList())

internal val PHONE = LayoutBounds(width = 402.0, height = 874.0)
internal val SAFE = EdgeInsets(top = 62.0, bottom = 34.0)

internal fun fixed(v: Number) = Dimension.Fixed(v.toDouble())

internal fun c(
    type: LayoutAttribute,
    target: String? = null,
    attribute: LayoutAttribute? = null,
    constant: Number = 0,
    multiplier: Number? = null,
    op: LayoutRelation = LayoutRelation.EQUAL,
    priority: Float = LayoutPriority.REQUIRED,
) = LayoutConstraint(
    type = type,
    target = target?.let(ConstraintTarget::parse),
    attribute = attribute,
    constant = constant.toDouble(),
    multiplier = multiplier?.toDouble(),
    op = op,
    priority = priority,
)

internal fun frame(x: Number, y: Number, w: Number, h: Number) =
    Frame(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())

/** Within half a 3x pixel. */
internal fun assertFrame(expected: Frame, actual: Frame?, message: String = "") {
    if (actual == null) fail("$message: no frame")
    val ok = abs(expected.x - actual.x) < 0.2 && abs(expected.y - actual.y) < 0.2 &&
        abs(expected.width - actual.width) < 0.2 && abs(expected.height - actual.height) < 0.2
    assertTrue(ok, "$message: expected $expected, was $actual")
}

internal fun assertNear(expected: Double, actual: Double, message: String = "") =
    assertEquals(expected, actual, 0.2, message)
