package dev.app8.engine.render

import androidx.compose.ui.graphics.Color
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.components.CollectionKind
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.core.model.CollectionContent
import dev.app8.engine.core.model.style.ToggleStyle
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.6.0 cross-platform parity: component color fields, state-aware isHidden, collections nested in cells. */
class Dsl060ParityTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"on":{"type":"boolean","initialValue":false},"rows":{"type":"array","initialValue":[1,2]}},
      "children":[
        {"type":"toggle","id":"sw","content":{"properties":{},"style":{"onTintColor":"{{on ? '#34C759' : 'nope'}}","thumbTintColor":"#FFFFFF"}}},
        {"type":"button","id":"b","content":{"properties":{"text":"Go"},
          "states":{"gone":{"properties":{"text":"Go","isHidden":true}},"styled":{"style":{"alpha":0.5}}}}},
        {"type":"image","id":"pic","content":{"properties":{"type":"none","isHidden":true},
          "states":{"shown":{"properties":{"type":"none"}}}}},
        {"type":"collection","id":"list","content":{"properties":{"data":"{{rows}}"},"template":
          {"type":"view","id":"cell","content":{"children":[
            {"type":"collection","id":"nested","content":{"properties":{"data":"{{rows}}"},"template":{"type":"view","id":"c","content":{}}}},
            {"type":"collection","id":"nestedTall","content":{"properties":{"data":"{{rows}}"},"layout":{"height":120},"template":{"type":"view","id":"c","content":{}}}}
          ]}}}},
        {"type":"collection","id":"row","content":{"properties":{"data":"{{rows}}"},"layout":{"type":"horizontal"},"template":
          {"type":"view","id":"cell","content":{"children":[
            {"type":"collection","id":"nested","content":{"properties":{"data":"{{rows}}"},"template":{"type":"view","id":"c","content":{}}}}
          ]}}}}
      ]}}"""

    private val rt = runtime(mapOf("s" to screen))
    private val session = session(rt)
    private fun node(id: String) = session.root.find(id)!!

    @Test
    fun `component color fields accept expressions`() {
        val sw = node("sw")
        val style = sw.content.style as ToggleStyle
        assertNull(sw.hexColor(style.onTintColor), "a non-color result leaves the field unset")
        assertTrue(rt.diagnostics.snapshot().any { it.startsWith("style.color.invalid") })
        session.scope.store.setValue("on", true)
        assertEquals(Color(0xFF34C759), sw.hexColor(style.onTintColor))
        assertEquals(Color.White, sw.hexColor(style.thumbTintColor), "hex literals still work")
        assertNull(sw.hexColor(""))
    }

    @Test
    fun `a state toggles isHidden on any component type`() {
        val b = node("b")
        val kind = ComponentRegistry.kind(b)
        assertFalse(kind.isHidden(b))
        b.stateName = "gone"
        assertTrue(kind.isHidden(b))
        b.stateName = "styled"
        assertFalse(kind.isHidden(b), "a state without properties keeps the base value")

        val pic = node("pic")
        assertTrue(ComponentRegistry.kind(pic).isHidden(pic))
        pic.stateName = "shown"
        assertFalse(ComponentRegistry.kind(pic).isHidden(pic), "state properties replace the base, isHidden included")
    }

    @Test
    fun `a collection nested in a vertical collection's cell sizes to its content`() {
        fun cellRoot(listId: String): RenderNode {
            val list = node(listId)
            val template = (list.content as CollectionContent).template!!
            val store = dev.app8.engine.core.vars.ScopedVariableStore(list.scope.store)
            return Cell.create(list, template, store, "item", 1L)!!.root
        }
        val vertical = cellRoot("list")
        assertTrue(CollectionKind.sizesToContent(vertical.find("nested")!!), "the outer collection scrolls instead")
        assertFalse(CollectionKind.sizesToContent(vertical.find("nestedTall")!!), "its own height wins")
        assertFalse(CollectionKind.sizesToContent(cellRoot("row").find("nested")!!), "a horizontal row is not a vertical scroller")
    }
}
