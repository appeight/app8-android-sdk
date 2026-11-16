package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StylePointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Decoding of the DSL 0.6.1 additions: screen `bottomBar` and native `appear` / `disappear`. */
class Dsl061ModelTest {
    private fun screen(bottomBar: String): ViewContent {
        val entity = assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent("""{"type":"screen","id":"s","content":{"bottomBar":$bottomBar,"children":[]}}"""))
        @Suppress("UNCHECKED_CAST")
        return entity.content as ViewContent
    }

    @Test
    fun `bottomBar decodes its component, insets, style, edge effect and hidden`() {
        val bar = screen(
            """{"component":{"id":"actions","type":"stackView","content":{"children":[]}},
                "insets":{"top":4,"left":20},"style":{"background":"system"},"edgeEffect":"hard","hidden":"{{isEditing}}"}""",
        ).bottomBar!!
        assertEquals("actions", bar.component.id)
        assertEquals(EdgeInsets(top = 4.0, left = 20.0), bar.insets)
        assertNull(bar.style!!.background, "system is the absence of a custom background")
        assertEquals(BottomBar.EdgeEffect.hard, bar.edgeEffect)
        assertEquals("{{isEditing}}", bar.hidden)
    }

    @Test
    fun `bottomBar defaults and boolean hidden`() {
        val bar = screen("""{"component":{"id":"a","type":"button","content":{"properties":{"text":"Go"}}},"hidden":true,"edgeEffect":"wobbly"}""").bottomBar!!
        assertNull(bar.insets)
        assertNull(bar.style)
        assertEquals("true", bar.hidden)
        // An unknown edge effect is soft.
        assertEquals(BottomBar.EdgeEffect.soft, bar.edgeEffect)
        assertEquals(12.0, BottomBar.DEFAULT_INSET_TOP)
        assertEquals(16.0, BottomBar.DEFAULT_INSET_SIDE)
        assertEquals(8.0, BottomBar.DEFAULT_INSET_BOTTOM)
    }

    @Test
    fun `bottomBar background takes a material or fill style entity, inline or pointer`() {
        val inline = screen(
            """{"component":{"id":"a","type":"view","content":{}},
                "style":{"background":{"id":"barMaterial","type":"material","content":[{"id":"barFill","type":"fill","content":{"solid":"#FFFFFF"}}]}}}""",
        ).bottomBar!!.style!!.background
        val material = assertIs<StyleEntity<*>>(inline).content
        assertEquals(1, assertIs<Material>(material).layers.size)

        val pointer = screen("""{"component":{"id":"a","type":"view","content":{}},"style":{"background":{"id":"barFill"}}}""").bottomBar!!.style!!.background
        assertEquals("barFill", assertIs<StylePointer>(pointer).id)
    }

    @Test
    fun `a malformed bottomBar is dropped without rejecting the screen`() {
        assertNull(screen("""{"insets":{"top":4}}""").bottomBar)
        assertNull(screen("\"oops\"").bottomBar)
    }

    @Test
    fun `native appear and disappear are ordinary actions keys`() {
        val entity = assertIs<ComponentEntity<*>>(
            DslDecoder().decodeComponent(
                """{"id":"player","type":"native","content":{"properties":{"module":"videoPlayer"},
                    "actions":{"appear":{"type":"dismiss"},"disappear":[{"type":"dismiss"},{"type":"dismiss"}]},
                    "analytics":{"appear":"playerShown"}}}""",
            ),
        )
        val content = assertIs<NativeContent>(entity.content)
        assertEquals(1, content.events[NativeContent.EVENT_APPEAR]!!.size)
        assertEquals(2, content.events[NativeContent.EVENT_DISAPPEAR]!!.size)
        assertEquals(setOf("appear"), content.eventAnalytics.keys)
    }
}
