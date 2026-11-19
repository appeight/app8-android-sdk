package dev.app8.engine.render

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A screen whose computed variable throws still loads: the rest renders, the failure is logged. */
class FailingExpressionScreenTest {
    @Test
    fun `a throwing computed variable does not fail the screen`() {
        val screen = """{"type":"screen","id":"s","content":{
          "variables":{
            "lines":{"type":"array","initialValue":[{"name":"Gift set","quantity":1}]},
            "total":{"type":"number","computed":"{{sum(map(lines, item.unitPrice * item.quantity))}}"},
            "heading":{"type":"string","initialValue":"Bag"}},
          "children":[{"type":"label","id":"title","content":{"properties":{"text":"{{heading}}"}}},
                      {"type":"label","id":"sum","content":{"properties":{"text":"{{total}}"}}}]}}"""
        val rt = runtime(mapOf("s" to screen))
        val session = session(rt)
        assertEquals("Bag", session.root.find("title")!!.string("{{heading}}"))
        assertFalse(session.scope.store.hasVariable("total"))
        assertNull(session.scope.store.getValue("total"))
        assertTrue(rt.diagnostics.snapshot().any { it.toString().contains("total") }, rt.diagnostics.snapshot().toString())
    }
}
