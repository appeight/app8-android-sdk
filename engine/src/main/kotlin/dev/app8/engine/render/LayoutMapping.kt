package dev.app8.engine.render

import dev.app8.engine.core.layout.AxisPriority
import dev.app8.engine.core.layout.ConstraintTarget
import dev.app8.engine.core.layout.Dimension
import dev.app8.engine.core.layout.LayoutAttribute
import dev.app8.engine.core.layout.LayoutConstraint
import dev.app8.engine.core.layout.LayoutPriority
import dev.app8.engine.core.layout.LayoutRelation
import dev.app8.engine.core.model.Layout as DslLayout
import dev.app8.engine.core.layout.Layout as SolverLayout

/**
 * The decoded DSL layout ([DslLayout], `model`) as solver input ([SolverLayout], `layout`).
 * `{{…}}` dimensions are evaluated here, in the component's scope, so the solver only sees
 * numbers; one that does not evaluate to a number is dropped (as iOS skips the constraint).
 */
internal object LayoutMapping {

    fun map(layout: DslLayout?, resolveExpression: (String) -> Double?): SolverLayout? {
        if (layout == null || layout.isEmpty) return null
        fun dim(d: DslLayout.Dimension?): Dimension? = when (d) {
            null -> null
            is DslLayout.Dimension.Fixed -> Dimension.Fixed(d.value.toDouble())
            is DslLayout.Dimension.Fraction -> Dimension.Fraction(d.value.toDouble())
            is DslLayout.Dimension.Expression -> resolveExpression(d.expression)?.takeIf { it.isFinite() }?.let { Dimension.Fixed(it) }
        }
        return SolverLayout(
            width = dim(layout.width),
            height = dim(layout.height),
            leading = dim(layout.leading),
            trailing = dim(layout.trailing),
            top = dim(layout.top),
            bottom = dim(layout.bottom),
            ignoresSafeArea = layout.ignoresSafeArea,
            constraints = layout.constraints.orEmpty().map(::constraint),
            contentHuggingPriority = layout.contentHuggingPriority?.let(::axis),
            contentCompressionResistancePriority = layout.contentCompressionResistancePriority?.let(::axis),
        )
    }

    fun constraint(c: DslLayout.Constraint): LayoutConstraint = LayoutConstraint(
        type = attribute(c.type),
        target = c.target?.let(::target),
        attribute = c.attribute?.let(::attribute),
        constant = c.constant?.toDouble() ?: 0.0,
        multiplier = c.multiplier?.toDouble(),
        op = when (c.op) {
            DslLayout.Relation.LESS_THAN_OR_EQUAL -> LayoutRelation.LESS_OR_EQUAL
            DslLayout.Relation.GREATER_THAN_OR_EQUAL -> LayoutRelation.GREATER_OR_EQUAL
            DslLayout.Relation.EQUAL, null -> LayoutRelation.EQUAL
        },
        priority = c.priority?.let(::priority) ?: LayoutPriority.REQUIRED,
    )

    fun attribute(a: DslLayout.Attribute): LayoutAttribute = when (a) {
        DslLayout.Attribute.leading -> LayoutAttribute.LEADING
        DslLayout.Attribute.trailing -> LayoutAttribute.TRAILING
        DslLayout.Attribute.top -> LayoutAttribute.TOP
        DslLayout.Attribute.bottom -> LayoutAttribute.BOTTOM
        DslLayout.Attribute.centerX -> LayoutAttribute.CENTER_X
        DslLayout.Attribute.centerY -> LayoutAttribute.CENTER_Y
        DslLayout.Attribute.width -> LayoutAttribute.WIDTH
        DslLayout.Attribute.height -> LayoutAttribute.HEIGHT
    }

    fun target(t: DslLayout.Target): ConstraintTarget = when (t) {
        DslLayout.Target.Superview -> ConstraintTarget.Superview
        DslLayout.Target.Keyboard -> ConstraintTarget.Keyboard
        DslLayout.Target.Self -> ConstraintTarget.SelfView
        is DslLayout.Target.Sibling -> ConstraintTarget.Sibling(t.id)
        is DslLayout.Target.SafeArea -> ConstraintTarget.SafeArea(t.id)
    }

    fun priority(p: DslLayout.PriorityValue): Float = LayoutPriority.clamp(
        when (p) {
            is DslLayout.PriorityValue.Named -> p.name.value
            is DslLayout.PriorityValue.Custom -> p.value
        },
    )

    private fun axis(a: DslLayout.AxisPriority) = AxisPriority(a.h?.let(::priority), a.v?.let(::priority))
}
