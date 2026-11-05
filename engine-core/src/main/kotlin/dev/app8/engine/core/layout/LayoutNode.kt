package dev.app8.engine.core.layout

public data class Size(val width: Double, val height: Double) {
    public companion object {
        /** `UIView.noIntrinsicMetric`: no intrinsic size on that axis. */
        public const val NO_INTRINSIC_METRIC: Double = -1.0
    }
}

public data class Frame(val x: Double, val y: Double, val width: Double, val height: Double) {
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
}

public data class EdgeInsets(
    val top: Double = 0.0,
    val left: Double = 0.0,
    val bottom: Double = 0.0,
    val right: Double = 0.0,
) {
    public companion object {
        public val ZERO: EdgeInsets = EdgeInsets()
    }
}

/** A per-axis pair of priorities (hugging or compression resistance). */
public data class AxisValues(val h: Float, val v: Float) {
    public companion object {
        /** `UIView` defaults. */
        public val DEFAULT_HUGGING: AxisValues = AxisValues(LayoutPriority.DEFAULT_LOW, LayoutPriority.DEFAULT_LOW)
        public val DEFAULT_COMPRESSION_RESISTANCE: AxisValues =
            AxisValues(LayoutPriority.DEFAULT_HIGH, LayoutPriority.DEFAULT_HIGH)
    }
}

/**
 * The view's natural size (`intrinsicContentSize`). [maxWidth] is the width
 * the view was given by the previous layout pass (null on the first), so
 * wrapping text can re-measure, as UILabel does with `preferredMaxLayoutWidth`.
 * Return null, or [Size.NO_INTRINSIC_METRIC] on an axis, for no intrinsic size.
 */
public fun interface IntrinsicMeasure {
    public fun measure(maxWidth: Double?): Size?
}

public enum class StackAxis { VERTICAL, HORIZONTAL }

/** `UIStackView.Alignment`; `top` is `leading` and `bottom` is `trailing`, as in UIKit. */
public enum class StackAlignment {
    FILL, LEADING, CENTER, TRAILING;

    public companion object {
        /** As the iOS view model maps `properties.alignment` (unknown → fill). */
        public fun parse(raw: String?): StackAlignment = when (raw) {
            "center" -> CENTER
            "top", "leading" -> LEADING
            "bottom", "trailing" -> TRAILING
            else -> FILL
        }
    }
}

public enum class StackDistribution {
    FILL, FILL_EQUALLY, FILL_PROPORTIONALLY, EQUAL_SPACING, EQUAL_CENTERING;

    public companion object {
        public fun parse(raw: String?): StackDistribution = when (raw) {
            "fillEqually" -> FILL_EQUALLY
            "fillProportionally" -> FILL_PROPORTIONALLY
            "equalSpacing" -> EQUAL_SPACING
            "equalCentering" -> EQUAL_CENTERING
            else -> FILL
        }
    }
}

public data class StackSpec(
    val axis: StackAxis = StackAxis.VERTICAL,
    val spacing: Double = 0.0,
    val alignment: StackAlignment = StackAlignment.FILL,
    val distribution: StackDistribution = StackDistribution.FILL,
)

public enum class ScrollDirection { VERTICAL, HORIZONTAL }

/** A scroll view: children are laid out in a content view as wide (or tall) as the scroll view. */
public data class ScrollSpec(val direction: ScrollDirection = ScrollDirection.VERTICAL)

/**
 * One view in the layout tree: the platform-neutral equivalent of a UIView
 * with its DSL layout.
 *
 * @property key unique within the tree (e.g. the component path); frames are keyed by it.
 * @property id the DSL id, for `sibling(id)` / bare-id / `safeArea(id)` targets.
 * @property stack set on stack views: [children] are its arranged subviews.
 * @property scroll set on scroll views: [children] go into its content view.
 * @property hidden hidden arranged subviews collapse out of their stack (other views keep their place, as in UIKit).
 * @property intrinsicSize text, images, buttons… (`intrinsicContentSize`); null for plain containers.
 * @property hugging / [compressionResistance] defaults per component (UILabel 251/750, UIView 250/750);
 *   `layout.contentHuggingPriority` etc. override them per axis.
 * @property opaque contents laid out separately (collections): children are not visited.
 */
public class LayoutNode(
    public val key: String,
    public val id: String? = null,
    public val layout: Layout? = null,
    public val children: List<LayoutNode> = emptyList(),
    public val stack: StackSpec? = null,
    public val scroll: ScrollSpec? = null,
    public val hidden: Boolean = false,
    public val intrinsicSize: IntrinsicMeasure? = null,
    public val hugging: AxisValues = AxisValues.DEFAULT_HUGGING,
    public val compressionResistance: AxisValues = AxisValues.DEFAULT_COMPRESSION_RESISTANCE,
    public val opaque: Boolean = false,
) {
    init {
        require(stack == null || scroll == null) { "$key: a node is either a stack or a scroll view" }
    }

    override fun toString(): String = "LayoutNode($key)"
}
