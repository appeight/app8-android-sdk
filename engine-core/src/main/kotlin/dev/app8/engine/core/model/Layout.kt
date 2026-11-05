package dev.app8.engine.core.model

/** Component positioning (iOS `DSL.Model.Layout`). */
public data class Layout(
    val width: Dimension? = null,
    val height: Dimension? = null,
    val leading: Dimension? = null,
    val trailing: Dimension? = null,
    val top: Dimension? = null,
    val bottom: Dimension? = null,
    val ignoresSafeArea: Boolean? = null,
    /** `@SafeOptionalArrayDecodable`: bad constraints are dropped, a non-array gives `null`. */
    val constraints: List<Constraint>? = null,
    val contentHuggingPriority: AxisPriority? = null,
    val contentCompressionResistancePriority: AxisPriority? = null,
) {
    /** True when the layout carries no positioning or sizing information (`{}` behaves like `null`). */
    val isEmpty: Boolean
        get() = width == null && height == null && leading == null && trailing == null &&
            top == null && bottom == null && ignoresSafeArea == null &&
            constraints.isNullOrEmpty() && contentHuggingPriority == null &&
            contentCompressionResistancePriority == null

    public sealed interface Dimension {
        public data class Fixed(val value: Float) : Dimension
        public data class Fraction(val value: Float) : Dimension
        public data class Expression(val expression: String) : Dimension
    }

    /** A single constraint. Without `target`, `type` must be `width`/`height` and applies to the view itself. */
    public data class Constraint(
        val type: Attribute,
        val target: Target? = null,
        val attribute: Attribute? = null,
        val constant: Float? = null,
        val multiplier: Float? = null,
        val op: Relation? = null,
        val priority: PriorityValue? = null,
    )

    @Suppress("EnumEntryName")
    public enum class Attribute { leading, trailing, top, bottom, centerX, centerY, width, height }

    public enum class Relation(public val raw: String) {
        EQUAL("="),
        LESS_THAN_OR_EQUAL("<="),
        GREATER_THAN_OR_EQUAL(">="),
    }

    public sealed interface Target {
        public data object Superview : Target
        public data object Keyboard : Target
        public data object Self : Target
        public data class Sibling(val id: String) : Target

        /** `null` id = the superview's safe area; otherwise the safe area of sibling [id]. */
        public data class SafeArea(val id: String?) : Target
    }

    public data class AxisPriority(val h: PriorityValue? = null, val v: PriorityValue? = null)

    public sealed interface PriorityValue {
        public data class Named(val name: PriorityName) : PriorityValue
        public data class Custom(val value: Float) : PriorityValue
    }

    @Suppress("EnumEntryName")
    public enum class PriorityName(public val value: Float) {
        required(1000f),
        defaultHigh(750f),
        defaultLow(250f),
        fittingSizeLevel(50f),
        dragThatCanResizeScene(510f),
        sceneSizeStayPut(500f),
        dragThatCannotResizeScene(490f),
    }
}

internal fun decodeLayout(c: Cursor): Layout {
    c.obj()
    return Layout(
        width = c.opt("width", ::decodeDimension),
        height = c.opt("height", ::decodeDimension),
        leading = c.opt("leading", ::decodeDimension),
        trailing = c.opt("trailing", ::decodeDimension),
        top = c.opt("top", ::decodeDimension),
        bottom = c.opt("bottom", ::decodeDimension),
        ignoresSafeArea = c.optBool("ignoresSafeArea"),
        constraints = c.safeOptionalList("constraints", "Layout.Constraint", ::decodeConstraint),
        contentHuggingPriority = c.opt("contentHuggingPriority", ::decodeAxisPriority),
        contentCompressionResistancePriority = c.opt("contentCompressionResistancePriority", ::decodeAxisPriority),
    )
}

internal fun decodeDimension(c: Cursor): Layout.Dimension {
    c.doubleOrNull()?.let { return Layout.Dimension.Fixed(it.toFloat()) }
    c.stringOrNull()?.let { if (it.contains("{{")) return Layout.Dimension.Expression(it) }
    return when (c.req("type") { it.strictEnum<DimensionType>() }) {
        DimensionType.fixed -> Layout.Dimension.Fixed(c.reqFloat("value"))
        DimensionType.fraction -> Layout.Dimension.Fraction(c.reqFloat("value"))
        DimensionType.expression -> Layout.Dimension.Expression(c.reqString("value"))
    }
}

@Suppress("EnumEntryName")
private enum class DimensionType { fixed, fraction, expression }

internal fun decodeConstraint(c: Cursor): Layout.Constraint {
    c.obj()
    return Layout.Constraint(
        type = c.req("type") { it.strictEnum<Layout.Attribute>() },
        target = c.opt("target", ::decodeTarget),
        attribute = c.optEnum<Layout.Attribute>("attribute"),
        constant = c.optFloat("constant"),
        multiplier = c.optFloat("multiplier"),
        op = c.optEnum<Layout.Relation>("op") { it.raw },
        priority = c.opt("priority", ::decodePriorityValue),
    )
}

internal fun decodeTarget(c: Cursor): Layout.Target {
    val raw = c.string()
    return when (raw.lowercase()) {
        "superview" -> Layout.Target.Superview
        "keyboard" -> Layout.Target.Keyboard
        "safearea" -> Layout.Target.SafeArea(null)
        "self" -> Layout.Target.Self
        else -> wrappedId(raw, "safearea")?.let { Layout.Target.SafeArea(it) }
            ?: wrappedId(raw, "sibling")?.let { Layout.Target.Sibling(it) }
            ?: Layout.Target.Sibling(raw)
    }
}

/** Parses `<keyword>(<id>)`; keyword case-insensitive, id verbatim. */
private fun wrappedId(raw: String, keyword: String): String? {
    val trimmed = raw.trim(' ')
    if (!trimmed.lowercase().startsWith("$keyword(") || !trimmed.endsWith(")")) return null
    val id = trimmed.substring(keyword.length + 1, trimmed.length - 1).trim(' ')
    return id.ifEmpty { null }
}

internal fun decodeAxisPriority(c: Cursor): Layout.AxisPriority {
    c.obj()
    return Layout.AxisPriority(h = c.opt("h", ::decodePriorityValue), v = c.opt("v", ::decodePriorityValue))
}

internal fun decodePriorityValue(c: Cursor): Layout.PriorityValue {
    c.doubleOrNull()?.let { return Layout.PriorityValue.Custom(it.toFloat()) }
    val s = c.stringOrNull()
    val named = Layout.PriorityName.entries.firstOrNull { it.name == s }
        ?: c.fail("expected a named priority string or a number")
    return Layout.PriorityValue.Named(named)
}
