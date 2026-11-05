package dev.app8.engine.core.layout

// Layout input types, mirroring app8-ios-sdk DSLModels/Layout.swift. They are
// independent of the DSL decoding models so the solver has no dependency on
// them; the renderer maps one onto the other.

/** A size or edge value: `120`, `{type: fraction, value: 0.5}`, `"{{expr}}"`. */
public sealed interface Dimension {
    /** Points. */
    public data class Fixed(val value: Double) : Dimension

    /** Multiplier of the superview's same dimension (`0.5` = half). Edges ignore it, as on iOS. */
    public data class Fraction(val value: Double) : Dimension

    /** A `{{…}}` expression, resolved by the `resolveExpression` passed to [solveLayout]. */
    public data class Expression(val source: String) : Dimension
}

public enum class LayoutAttribute(public val raw: String) {
    LEADING("leading"),
    TRAILING("trailing"),
    TOP("top"),
    BOTTOM("bottom"),
    CENTER_X("centerX"),
    CENTER_Y("centerY"),
    WIDTH("width"),
    HEIGHT("height");

    public val isDimension: Boolean get() = this == WIDTH || this == HEIGHT

    public companion object {
        public fun parse(raw: String): LayoutAttribute? = entries.firstOrNull { it.raw == raw }
    }
}

/** The constraint's `op`. */
public enum class LayoutRelation(public val raw: String) {
    EQUAL("="),
    LESS_OR_EQUAL("<="),
    GREATER_OR_EQUAL(">=");

    public companion object {
        public fun parse(raw: String): LayoutRelation? = entries.firstOrNull { it.raw == raw }
    }
}

public sealed interface ConstraintTarget {
    public data object Superview : ConstraintTarget

    /** `"self"`: relates two of the view's own anchors (`width = height × 1.5`). */
    public data object SelfView : ConstraintTarget

    /** A zero-height guide whose top tracks the keyboard (the safe-area bottom when hidden). */
    public data object Keyboard : ConstraintTarget

    /** `"safeArea"` (superview's guide, [id] null) or `"safeArea(id)"` (a sibling's guide). */
    public data class SafeArea(val id: String?) : ConstraintTarget

    /** `"sibling(id)"` or a bare id. */
    public data class Sibling(val id: String) : ConstraintTarget

    public companion object {
        /** Parses a DSL target string exactly as the iOS decoder does. */
        public fun parse(raw: String): ConstraintTarget = when (raw.lowercase()) {
            "superview" -> Superview
            "keyboard" -> Keyboard
            "safearea" -> SafeArea(null)
            "self" -> SelfView
            else -> wrappedId(raw, "safearea")?.let { SafeArea(it) }
                ?: wrappedId(raw, "sibling")?.let { Sibling(it) }
                ?: Sibling(raw)
        }

        private fun wrappedId(raw: String, keyword: String): String? {
            val trimmed = raw.trim()
            if (!trimmed.lowercase().startsWith("$keyword(") || !trimmed.endsWith(")")) return null
            val id = trimmed.substring(keyword.length + 1, trimmed.length - 1).trim()
            return id.ifEmpty { null }
        }
    }
}

/** UIKit layout priorities (`UILayoutPriority`). */
public object LayoutPriority {
    public const val REQUIRED: Float = 1000f
    public const val DEFAULT_HIGH: Float = 750f
    public const val DRAG_THAT_CAN_RESIZE_SCENE: Float = 510f
    public const val SCENE_SIZE_STAY_PUT: Float = 500f
    public const val DRAG_THAT_CANNOT_RESIZE_SCENE: Float = 490f
    public const val DEFAULT_LOW: Float = 250f
    public const val FITTING_SIZE_LEVEL: Float = 50f

    /** A named priority (`"defaultHigh"`), or null. */
    public fun named(name: String): Float? = when (name) {
        "required" -> REQUIRED
        "defaultHigh" -> DEFAULT_HIGH
        "defaultLow" -> DEFAULT_LOW
        "fittingSizeLevel" -> FITTING_SIZE_LEVEL
        "dragThatCanResizeScene" -> DRAG_THAT_CAN_RESIZE_SCENE
        "sceneSizeStayPut" -> SCENE_SIZE_STAY_PUT
        "dragThatCannotResizeScene" -> DRAG_THAT_CANNOT_RESIZE_SCENE
        else -> null
    }

    /** UIKit only accepts 1…1000. */
    public fun clamp(priority: Float): Float = priority.coerceIn(1f, REQUIRED)
}

/**
 * One `constraints[]` entry. [target] null is a self-dimension clamp (only
 * width/height); [attribute] null means the same as [type].
 */
public data class LayoutConstraint(
    val type: LayoutAttribute,
    val target: ConstraintTarget? = null,
    val attribute: LayoutAttribute? = null,
    val constant: Double = 0.0,
    val multiplier: Double? = null,
    val op: LayoutRelation = LayoutRelation.EQUAL,
    val priority: Float = LayoutPriority.REQUIRED,
)

/** Per-axis priority override; null axes keep the view's default. */
public data class AxisPriority(val h: Float? = null, val v: Float? = null)

public data class Layout(
    val width: Dimension? = null,
    val height: Dimension? = null,
    val leading: Dimension? = null,
    val trailing: Dimension? = null,
    val top: Dimension? = null,
    val bottom: Dimension? = null,
    val ignoresSafeArea: Boolean? = null,
    val constraints: List<LayoutConstraint> = emptyList(),
    val contentHuggingPriority: AxisPriority? = null,
    val contentCompressionResistancePriority: AxisPriority? = null,
) {
    /** No positioning or sizing information: behaves like no layout (fills the superview). */
    public val isEmpty: Boolean
        get() = width == null && height == null &&
            leading == null && trailing == null && top == null && bottom == null &&
            ignoresSafeArea == null && constraints.isEmpty() &&
            contentHuggingPriority == null && contentCompressionResistancePriority == null

    public companion object {
        public val FILL_SUPERVIEW: Layout = Layout(
            leading = Dimension.Fixed(0.0),
            trailing = Dimension.Fixed(0.0),
            top = Dimension.Fixed(0.0),
            bottom = Dimension.Fixed(0.0),
        )
    }
}
