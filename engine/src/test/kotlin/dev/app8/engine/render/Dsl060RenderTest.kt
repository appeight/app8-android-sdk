package dev.app8.engine.render

import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.ViewStyle
import dev.app8.engine.navigation.FlowController
import dev.app8.engine.navigation.StackNavigator
import dev.app8.engine.render.components.CollectionKind
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.render.style.StyleResolver
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.6.0 rendering rules: color expressions, isHidden on every type, collection sizing, dismiss then navigate. */
class Dsl060RenderTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"selected":{"type":"boolean","initialValue":false},"busy":{"type":"boolean","initialValue":true},"rows":{"type":"array","initialValue":[1,2]}},
      "children":[
        {"type":"view","id":"chip","content":{"style":{"material":[
          {"id":"bg","type":"fill","content":{"solid":"{{selected ? '#FFFFFF' : '#0D1311'}}"}},
          {"id":"line","type":"outline","content":{"lineWidth":1,"position":"inside","fill":{"solid":"{{selected ? 'nope' : '#00FF00'}}"}}}
        ]}}},
        {"type":"button","id":"go","content":{"properties":{"text":"Go","isHidden":"{{busy}}"}}},
        {"type":"image","id":"pic","content":{"properties":{"type":"none","isHidden":false}}},
        {"type":"collection","id":"free","content":{"properties":{"data":"{{rows}}"},"template":{"type":"view","id":"c","content":{}}}},
        {"type":"collection","id":"fixedOff","content":{"properties":{"data":"{{rows}}"},"layout":{"type":"vertical","scrollEnabled":false},"template":{"type":"view","id":"c","content":{}}}},
        {"type":"scrollView","id":"page","content":{"children":[
          {"type":"stackView","id":"stack","content":{"properties":{"axis":"vertical"},"children":[
            {"type":"collection","id":"inner","content":{"properties":{"data":"{{rows}}"},"template":{"type":"view","id":"c","content":{}}}},
            {"type":"collection","id":"innerTall","content":{"properties":{"data":"{{rows}}"},"layout":{"height":300},"template":{"type":"view","id":"c","content":{}}}},
            {"type":"collection","id":"innerRow","content":{"properties":{"data":"{{rows}}"},"layout":{"type":"horizontal"},"template":{"type":"view","id":"c","content":{}}}}
          ]}}
        ]}},
        {"type":"scrollView","id":"side","content":{"properties":{"direction":"horizontal"},"children":[
          {"type":"collection","id":"sideways","content":{"properties":{"data":"{{rows}}"},"template":{"type":"view","id":"c","content":{}}}}
        ]}}
      ]}}"""

    private val rt = runtime(mapOf("s" to screen))
    private val session = session(rt)
    private fun node(id: String) = session.root.find(id)!!

    @Test
    fun `style colors bind expressions in the component's scope`() {
        val chip = node("chip")
        val material = (chip.content.style as ViewStyle).material.value!!
        val resolver = StyleResolver(rt.styles)
        fun solid() = ((resolver.material(material, chip.colorBinder()).layers[0] as StyleEntity<*>).content as Fill).solid.value!!
        fun outline() = ((resolver.material(material, chip.colorBinder()).layers[1] as StyleEntity<*>).content as dev.app8.engine.core.model.style.Outline).fill.solid.value

        assertEquals("0D1311", solid().light!!.value)
        assertEquals("00FF00", outline()!!.light!!.value)
        session.scope.store.setValue("selected", true)
        assertEquals("FFFFFF", solid().light!!.value)
        assertNull(outline(), "a non-color result leaves the field unset")
        assertTrue(rt.diagnostics.snapshot().any { it.startsWith("style.color.invalid") })
        // Without a scope the field stays unbound (clear).
        val unbound = (resolver.material(material, null).layers[0] as StyleEntity<*>).content as Fill
        assertEquals("{{selected ? '#FFFFFF' : '#0D1311'}}", unbound.solid.value!!.expression)
    }

    @Test
    fun `isHidden works on every component type`() {
        val go = node("go")
        assertTrue(ComponentRegistry.kind(go).isHidden(go))
        session.scope.store.setValue("busy", false)
        assertFalse(ComponentRegistry.kind(go).isHidden(go))
        assertFalse(ComponentRegistry.kind(node("pic")).isHidden(node("pic")))
    }

    @Test
    fun `a collection that does not scroll itself sizes to its content`() {
        assertFalse(CollectionKind.sizesToContent(node("free")))
        assertTrue(CollectionKind.sizesToContent(node("fixedOff")))
        assertTrue(CollectionKind.sizesToContent(node("inner")), "inside a vertical scroll view")
        assertFalse(CollectionKind.sizesToContent(node("innerTall")), "its own height wins")
        assertFalse(CollectionKind.sizesToContent(node("innerRow")), "horizontal collections keep scrolling")
        assertFalse(CollectionKind.sizesToContent(node("sideways")), "a horizontal scroll view does not count")
    }

    @Test
    fun `dismissing a modal hands navigation to the presenting stack`() = runBlocking {
        val app = DslDecoder().decodeApp("""{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"home"}]}}""")
        val flow = FlowController(app, "home", startFlowId = "f")
        flow.present("sheet", emptyMap(), Action.PresentationStyle.sheet, null, null)
        val modal = flow.layers.last()
        // The sheet host slides the sheet away, then removes the layer.
        val host = launch {
            while (!modal.dismissing) kotlinx.coroutines.delay(5)
            flow.remove(modal)
        }
        val next = StackNavigator(flow, modal, modal.stack, null).dismissAndAwait()
        host.join()
        next.push("details", emptyMap())
        assertEquals(1, flow.layers.size)
        assertEquals(listOf("home", "details"), flow.layers.single().stack.entries.map { it.screenId })
    }
}
