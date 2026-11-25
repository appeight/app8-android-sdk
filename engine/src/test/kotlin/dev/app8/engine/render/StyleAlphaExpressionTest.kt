package dev.app8.engine.render

import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.style.BaseStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * styles.md: `style.alpha` is a number or an expression (since 0.7.17), and a mistyped style
 * field is ignored with a warning: the screen still opens. Before, an `alpha` expression was
 * dropped (the web honoured it), and on iOS it failed a bottom bar's component and with it the
 * whole screen, which never appeared.
 */
class StyleAlphaExpressionTest {

    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"ready":{"type":"boolean","initialValue":false}},
      "bottomBar":{"component":{"id":"save","type":"view","content":{
        "style":{"alpha":"{{ready ? 1 : 0.35}}","transform":5},"layout":{"height":48}}}},
      "children":[
        {"type":"label","id":"t","content":{"properties":{"text":"Hi"},"style":{"alpha":"{{ready ? 1 : 0.5}}"}}},
        {"type":"view","id":"n","content":{"style":{"alpha":"0.4"}}},
        {"type":"view","id":"x","content":{"style":{"alpha":[1]}}}]}}"""

    private fun BaseStyle?.orFail(): BaseStyle = assertNotNull(this)

    @Test
    fun `an alpha expression in style decodes and resolves in the component's scope`() {
        val rt = runtime(mapOf("s" to screen))
        val s = session(rt)
        val label = s.root.find("t")!!
        val style = (label.content.style as? BaseStyle).orFail()
        assertNull(style.alpha)
        assertEquals("{{ready ? 1 : 0.5}}", style.alphaExpression)
        assertEquals(0.5, label.number(style.alphaExpression))
        s.scope.store.setValue("ready", true)
        assertEquals(1.0, label.number(style.alphaExpression))

        assertEquals(0.4, (s.root.find("n")!!.content.style as? BaseStyle).orFail().alpha, "a number in a string")
        val bad = (s.root.find("x")!!.content.style as? BaseStyle).orFail()
        assertNull(bad.alpha, "anything else is ignored")
        assertNull(bad.alphaExpression)
    }

    @Test
    fun `a mistyped style field in the bottom bar is ignored and the screen still opens`() {
        val rt = runtime(mapOf("s" to screen))
        val s = session(rt)
        val content = s.content as ComponentContent<*, *>
        val bar = assertNotNull(content.bottomBar, "the bottom bar survives")
        val barStyle = ((bar.component as ComponentEntity<*>).content as ComponentContent<*, *>).style as BaseStyle
        assertEquals("{{ready ? 1 : 0.35}}", barStyle.alphaExpression)
        assertNull(barStyle.transform)
        val warnings = rt.diagnostics.snapshot()
        assertTrue(warnings.any { "bottomBar.component.content.style.transform" in it }, warnings.toString())
    }
}
