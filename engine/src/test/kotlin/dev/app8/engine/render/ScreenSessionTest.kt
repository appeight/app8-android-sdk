package dev.app8.engine.render

import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.vars.Values
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ScreenSessionTest {
    @Test
    fun `datasources, params and component scopes are set up like iOS renderScreen`() {
        val screen = """{"type":"screen","id":"s","content":{
          "variables":{"items":{"type":"array","source":"datasources/items"},"title":{"type":"string","initialValue":"x"}},
          "children":[{"type":"view","id":"card","content":{"variables":{"liked":{"type":"boolean","initialValue":false}},
             "children":[{"type":"label","id":"t","content":{"properties":{"text":"{{title}}"}}}]}}]}}"""
        val rt = runtime(mapOf("s" to screen), datasources = mapOf("datasources/items" to """{"data":[1,2,3]}"""))
        val session = session(rt, params = mapOf("title" to "Hello", "extra" to 5))
        assertEquals(listOf(1L, 2L, 3L), session.scope.store.getValue("items"))
        assertEquals("Hello", session.scope.store.getValue("title"), rt.diagnostics.snapshot().toString())
        assertEquals(5L, Values.normalize(session.scope.store.getValue("extra")))
        val card = session.root.find("card")!!
        val label = session.root.find("t")!!
        assertNotSame(session.scope, card.scope, "a component with variables gets its own scope")
        assertSame(card.scope, label.scope, "children share the enclosing scope")
        assertEquals("s.card.t", label.key)
        assertEquals("Hello", label.string("{{title}}"))
    }

    @Test
    fun `the solve cache reuses results for the same signature, bounds and safe area`() {
        val cache = SolveCache()
        val tree = LayoutNode("root", children = listOf(LayoutNode("root.a")))
        val a = cache.solve(tree, "sig", LayoutBounds(400.0, 800.0), EdgeInsets(top = 50.0), 3.0)
        val b = cache.solve(tree, "sig", LayoutBounds(400.0, 800.0), EdgeInsets(top = 50.0), 3.0)
        assertSame(a, b)
        cache.solve(tree, "sig", LayoutBounds(390.0, 800.0), EdgeInsets(top = 50.0), 3.0)
        assertEquals(1, cache.hits)
        assertEquals(2, cache.misses)
        assertEquals(400.0, a.local.getValue("root.a").width, "an empty layout fills the superview")
    }
}
