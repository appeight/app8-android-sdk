package dev.app8.engine.render

import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.StackAlignment
import dev.app8.engine.core.layout.StackAxis
import dev.app8.engine.core.layout.StackSpec
import dev.app8.engine.core.layout.solveLayout
import dev.app8.engine.core.model.Defer
import dev.app8.engine.render.components.ComponentRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DSL "Deferred sections": placeholders with the screen, the components after it is shown. */
class DeferredSectionsTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"rows":{"type":"array","initialValue":[1,2]}},
      "children":[
        {"type":"scrollView","id":"page","content":{"children":[
          {"type":"stackView","id":"stack","content":{
            "properties":{"axis":"vertical","spacing":8,"alignment":"center"},
            "defaultStateName":"open",
            "states":{"open":{"childStates":{"inner":"on"}}},
            "children":[
              {"type":"label","id":"title","content":{"properties":{"text":"Section A"}}},
              {"type":"view","id":"details","defer":{"placeholder":"shimmer","estimatedHeight":240},"content":{"children":[
                {"type":"view","id":"inner","content":{"states":{"on":{}}}},
                {"type":"stackView","id":"nested","content":{"children":[
                  {"type":"view","id":"nestedDeferred","defer":{"placeholder":"shimmer","estimatedHeight":40},"content":{}}
                ]}},
                {"type":"textField","id":"field","content":{"properties":{"placeholder":"Sample Item"}}}
              ]}},
              {"type":"view","id":"faqs","defer":{"placeholder":"none"},"content":{}},
              {"type":"collection","id":"list","content":{"properties":{"data":"{{rows}}"},
                "template":{"type":"stackView","id":"cell","content":{"children":[
                  {"type":"view","id":"cellPart","defer":{"placeholder":"shimmer","estimatedHeight":20},"content":{}}
                ]}}}}
            ]}}
        ]}},
        {"type":"view","id":"loose","defer":{"placeholder":"shimmer","estimatedHeight":50},"content":{}}
      ]}}"""

    private val rt = runtime(mapOf("s" to screen))
    private val session = session(rt)
    private val stack get() = session.root.find("stack")!!

    @Test
    fun `deferred stack children are placeholders until the screen is shown`() {
        val keys = stack.children.map { it.key }
        assertEquals(listOf("s.page.stack.title", "s.page.stack.details#deferred", "s.page.stack.faqs#deferred", "s.page.stack.list"), keys)
        assertNull(session.root.find("details"), "a deferred component does not exist yet")
        assertNull(session.root.find("inner"))
        assertNull(session.root.find("faqs"))
        val placeholder = stack.children[1]
        assertSame(DeferredPlaceholderKind, ComponentRegistry.kind(placeholder))
        assertEquals(Defer.Placeholder.shimmer, placeholder.deferredSlot!!.defer.placeholder)
        assertEquals(Defer.Placeholder.none, stack.children[2].deferredSlot!!.defer.placeholder)
        assertEquals(2, session.deferred.size)
    }

    @Test
    fun `defer outside a stack builds with the screen and warns`() {
        assertNotNull(session.root.find("loose"))
        val warnings = rt.diagnostics.snapshot().filter { it.startsWith("defer.notInStack") }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue("'loose'" in warnings.single())
    }

    @Test
    fun `deferred components build in document order, at least one per frame, and swap in place`() {
        // A spent budget still builds one.
        assertEquals(1, session.buildDeferred(budgetNanos = 0))
        val details = stack.children[1]
        assertEquals("s.page.stack.details", details.key, "built under the key it would have had")
        assertSame(details, session.root.find("details"))
        assertTrue(details.fadesIn)
        assertSame(stack, details.parent)
        assertTrue(stack.children[2].deferredSlot != null, "the next one is still a placeholder")

        assertEquals(1, session.buildAllDeferred())
        assertEquals(listOf("title", "details", "faqs", "list"), stack.children.map { it.id })
        assertTrue(session.deferred.isEmpty)
        assertEquals(0, session.buildAllDeferred())
    }

    @Test
    fun `defer inside a deferred component is ignored`() {
        session.buildAllDeferred()
        val nested = session.root.find("nestedDeferred")
        assertNotNull(nested, "built with its deferred parent")
        assertNull(nested.deferredSlot)
        assertFalse(rt.diagnostics.snapshot().any { "nestedDeferred" in it })
    }

    @Test
    fun `the stack's current state reaches a deferred component when it arrives`() {
        session.buildAllDeferred()
        assertEquals("on", session.root.find("inner")!!.stateName)
    }

    @Test
    fun `focus order includes deferred text inputs once built`() {
        assertNull(session.treeOrder["s.page.stack.details.field"])
        session.buildAllDeferred()
        val order = session.treeOrder
        assertTrue(order.getValue("s.page.stack.details.field") > order.getValue("s.page.stack.title"))
    }

    @Test
    fun `nothing is built for a screen that left the app`() {
        session.dispose()
        assertEquals(0, session.buildAllDeferred())
        assertNull(session.root.find("details"))
    }

    @Test
    fun `the placeholder is as wide as the stack and estimatedHeight tall`() {
        fun solveStack(): Map<String, dev.app8.engine.core.layout.Frame> {
            val children = stack.children.map { n ->
                LayoutNode(n.key, n.id, layout = LayoutMapping.map(n.content.layout) { null })
            }
            val tree = LayoutNode(
                "root",
                children = listOf(
                    LayoutNode("stack", stack = StackSpec(StackAxis.VERTICAL, 8.0, StackAlignment.CENTER), children = children),
                ),
            )
            return solveLayout(tree, LayoutBounds(360.0, 800.0)).frames
        }
        val frames = solveStack()
        val shimmer = frames.getValue("s.page.stack.details#deferred")
        assertEquals(360.0, shimmer.width)
        assertEquals(240.0, shimmer.height)
        assertEquals(0.0, frames.getValue("s.page.stack.faqs#deferred").height)

        val horizontal = DeferredSlot.layout(Defer(estimatedWidth = 120.0, estimatedHeight = 999.0), horizontal = true)
        val row = LayoutNode(
            "row",
            stack = StackSpec(StackAxis.HORIZONTAL, alignment = StackAlignment.CENTER),
            // A sibling without a size takes the rest of the row (a lone child would fill it).
            children = listOf(LayoutNode("p", layout = LayoutMapping.map(horizontal) { null }), LayoutNode("rest")),
        )
        val f = solveLayout(LayoutNode("root", children = listOf(row)), LayoutBounds(360.0, 64.0)).frames.getValue("p")
        assertEquals(120.0, f.width)
        assertEquals(64.0, f.height)
    }

    @Test
    fun `defer in collection items is ignored without a warning`() {
        assertFalse(rt.diagnostics.snapshot().any { "cellPart" in it })
        val cell = dev.app8.engine.render.collection.Cell.create(
            session.root.find("list")!!,
            (session.root.find("list")!!.content as dev.app8.engine.core.model.CollectionContent).template!!,
            dev.app8.engine.core.vars.ScopedVariableStore(session.scope.store),
            null,
            null,
        )!!
        val part = cell.root.find("cellPart")!!
        assertNull(part.deferredSlot)
        assertTrue(session.deferred.size == 2, "nothing queued for the cell")
        assertFalse(rt.diagnostics.snapshot().any { "cellPart" in it })
    }
}
