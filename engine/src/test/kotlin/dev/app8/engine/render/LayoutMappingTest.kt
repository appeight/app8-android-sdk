package dev.app8.engine.render

import dev.app8.engine.core.layout.ConstraintTarget
import dev.app8.engine.core.layout.Dimension
import dev.app8.engine.core.layout.LayoutAttribute
import dev.app8.engine.core.layout.LayoutPriority
import dev.app8.engine.core.layout.LayoutRelation
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.content
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutMappingTest {
    private fun dsl(layout: String) =
        DslDecoder().decodeComponent("""{"type":"view","id":"v","content":{"layout":$layout}}""").content!!.layout

    @Test
    fun `empty layout maps to null so the view fills its superview`() {
        assertNull(LayoutMapping.map(dsl("{}")) { null })
        assertNull(LayoutMapping.map(null) { null })
    }

    @Test
    fun `dimensions map fixed, fraction and evaluated expressions`() {
        val l = LayoutMapping.map(dsl("""{"width":120,"height":{"type":"fraction","value":0.5},"top":"{{gap}}","leading":"{{missing}}"}""")) { expr ->
            if (expr == "{{gap}}") 24.0 else null
        }!!
        assertEquals(Dimension.Fixed(120.0), l.width)
        assertEquals(Dimension.Fraction(0.5), l.height)
        assertEquals(Dimension.Fixed(24.0), l.top)
        // An expression that does not evaluate to a number is dropped, like iOS skips the constraint.
        assertNull(l.leading)
    }

    @Test
    fun `constraints keep target, attribute, relation, multiplier and priority`() {
        val l = LayoutMapping.map(
            dsl(
                """{"constraints":[
                {"type":"top","target":"safeArea","constant":8},
                {"type":"leading","target":"sibling(icon)","attribute":"trailing","constant":12,"op":">=","priority":"defaultHigh"},
                {"type":"width","target":"self","attribute":"height","multiplier":1.5,"priority":2000},
                {"type":"height","constant":44,"op":"<="},
                {"type":"centerY","target":"header"}
                ]}""",
            ),
        ) { null }!!
        val c = l.constraints
        assertEquals(ConstraintTarget.SafeArea(null), c[0].target)
        assertEquals(8.0, c[0].constant)
        assertEquals(LayoutPriority.REQUIRED, c[0].priority)
        assertEquals(ConstraintTarget.Sibling("icon"), c[1].target)
        assertEquals(LayoutAttribute.TRAILING, c[1].attribute)
        assertEquals(LayoutRelation.GREATER_OR_EQUAL, c[1].op)
        assertEquals(750f, c[1].priority)
        assertEquals(ConstraintTarget.SelfView, c[2].target)
        assertEquals(1.5, c[2].multiplier)
        assertEquals(1000f, c[2].priority, "priorities clamp to 1…1000 like UIKit")
        assertNull(c[3].target)
        assertEquals(LayoutRelation.LESS_OR_EQUAL, c[3].op)
        assertEquals(ConstraintTarget.Sibling("header"), c[4].target)
    }

    @Test
    fun `hugging and compression priorities map per axis`() {
        val l = LayoutMapping.map(dsl("""{"contentHuggingPriority":{"h":"required"},"contentCompressionResistancePriority":{"v":100}}""")) { null }!!
        assertEquals(1000f, l.contentHuggingPriority?.h)
        assertNull(l.contentHuggingPriority?.v)
        assertEquals(100f, l.contentCompressionResistancePriority?.v)
    }
}
