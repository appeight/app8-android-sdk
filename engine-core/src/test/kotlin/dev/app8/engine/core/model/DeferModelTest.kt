package dev.app8.engine.core.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DSL "Deferred sections": `defer` on the component envelope and where it applies. */
class DeferModelTest {
    private fun stack(vararg children: String) =
        """{"type":"stackView","id":"stack","content":{"properties":{"axis":"vertical"},"children":[${children.joinToString(",")}]}}"""

    private fun children(json: String) = DslDecoder().decodeComponent(json).content!!.children.map { it as ComponentEntity<*> }

    @Test
    fun `defer decodes next to type and id`() {
        val (a, b, c) = children(
            stack(
                """{"type":"label","id":"title","content":{"properties":{"text":"Section A"}}}""",
                """{"type":"view","id":"details","defer":{"placeholder":"shimmer","estimatedHeight":240},"content":{"children":[]}}""",
                """{"type":"view","id":"more","defer":{"placeholder":"none","estimatedWidth":80},"content":{}}""",
            ),
        )
        assertNull(a.defer)
        assertEquals(Defer(Defer.Placeholder.shimmer, estimatedHeight = 240.0), b.defer)
        assertEquals(Defer(Defer.Placeholder.none, estimatedWidth = 80.0), c.defer)
    }

    @Test
    fun `defer defaults to an empty placeholder of size zero`() {
        val (a) = children(stack("""{"type":"view","id":"a","defer":{},"content":{}}"""))
        assertEquals(Defer(Defer.Placeholder.none, 0.0, 0.0), a.defer)
    }

    @Test
    fun `a malformed defer never rejects the component`() {
        val list = children(
            stack(
                """{"type":"view","id":"a","defer":true,"content":{}}""",
                """{"type":"view","id":"b","defer":{"placeholder":"sparkle","estimatedHeight":"tall"},"content":{}}""",
                """{"type":"view","id":"c","defer":{"placeholder":3,"estimatedHeight":-20,"estimatedWidth":12.5},"content":{}}""",
                """{"type":"view","id":"d","defer":null,"content":{}}""",
            ),
        )
        assertEquals(listOf("a", "b", "c", "d"), list.map { it.id })
        assertNull(list[0].defer)
        assertEquals(Defer(), list[1].defer)
        assertEquals(Defer(estimatedWidth = 12.5), list[2].defer)
        assertNull(list[3].defer)
    }

    @Test
    fun `a template instance keeps its own defer`() {
        val resolver = TemplateResolver(listOf(DslDecoder().decodeTemplate("""{"id":"card","type":"view","content":{"properties":{}}}""")))
        val raw = TemplatePreprocessor(resolver).process(
            DslDecoder().parse(stack("""{"templateId":"card","id":"c1","defer":{"placeholder":"shimmer","estimatedHeight":100},"content":{}}""")),
        )
        val (c1) = DslDecoder().decodeComponent(raw).content!!.children.map { it as ComponentEntity<*> }
        assertEquals(Defer(Defer.Placeholder.shimmer, estimatedHeight = 100.0), c1.defer)
    }

    @Test
    fun `placement rules`() {
        val defer = Defer()
        val stack = ComponentType.Known(ComponentType.Key.stackView)
        val view = ComponentType.Known(ComponentType.Key.view)
        val collection = ComponentType.Known(ComponentType.Key.collection)
        assertEquals(DeferPlacement.NONE, DeferPlacement.of(null, stack, insideDeferred = false, insidePerItem = false))
        assertEquals(DeferPlacement.DEFERRED, DeferPlacement.of(defer, stack, insideDeferred = false, insidePerItem = false))
        assertEquals(DeferPlacement.NOT_IN_STACK, DeferPlacement.of(defer, view, insideDeferred = false, insidePerItem = false))
        assertEquals(DeferPlacement.NOT_IN_STACK, DeferPlacement.of(defer, null, insideDeferred = false, insidePerItem = false), "a screen root")
        assertEquals(DeferPlacement.NOT_IN_STACK, DeferPlacement.of(defer, ComponentType.Custom("card"), insideDeferred = false, insidePerItem = false))
        // Nested in a deferred component, or in per-item content: built with the parent, no warning.
        assertEquals(DeferPlacement.IGNORED, DeferPlacement.of(defer, stack, insideDeferred = true, insidePerItem = false))
        assertEquals(DeferPlacement.IGNORED, DeferPlacement.of(defer, stack, insideDeferred = false, insidePerItem = true))
        assertEquals(DeferPlacement.IGNORED, DeferPlacement.of(defer, collection, insideDeferred = false, insidePerItem = true))
        assertEquals("defer.notInStack", DeferPlacement.NOT_IN_STACK_WARNING)
    }
}
