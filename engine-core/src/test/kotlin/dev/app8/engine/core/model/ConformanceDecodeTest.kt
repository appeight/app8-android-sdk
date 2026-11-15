package dev.app8.engine.core.model

import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.core.vars.actions.VariableAction
import dev.app8.engine.core.vars.actions.VariableActionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Decoding rules the conformance fixtures check: literal values, optional ids, image shorthand, action paths. */
class ConformanceDecodeTest {
    private fun component(json: String): ComponentEntity<*> = DslDecoder().decodeComponent(json) as ComponentEntity<*>
    private fun properties(json: String): Any? = component(json).content.properties

    @Test
    fun `boolean and number properties take literals as well as expressions`() {
        assertEquals("true", (properties("""{"type":"toggle","id":"t","content":{"properties":{"isOn":true,"isEnabled":false}}}""") as ToggleProperties).isOn)
        assertEquals("0.6", (properties("""{"type":"slider","id":"s","content":{"properties":{"value":0.6}}}""") as SliderProperties).value)
        val page = properties("""{"type":"pageControl","id":"p","content":{"properties":{"numberOfPages":4,"currentPage":1}}}""") as PageControlProperties
        assertEquals("4", page.numberOfPages)
        assertEquals("1", page.currentPage)
        assertEquals("true", (properties("""{"type":"activityIndicator","id":"a","content":{"properties":{"isAnimating":true}}}""") as ActivityIndicatorProperties).isAnimating)
        assertEquals("{{busy}}", (properties("""{"type":"activityIndicator","id":"a","content":{"properties":{"isAnimating":"{{busy}}"}}}""") as ActivityIndicatorProperties).isAnimating)

        val view = component("""{"type":"view","id":"v","content":{"properties":{"isHidden":true}}}""")
        assertEquals("true", (view.content.properties as ViewProperties).isHidden)
        assertEquals("true", view.isHidden)
        assertEquals("false", (properties("""{"type":"label","id":"l","content":{"properties":{"text":"x","isHidden":false}}}""") as LabelProperties).isHidden)
    }

    @Test
    fun `a component without an id gets a stable one`() {
        val screen = DslDecoder().decodeScreen(
            """{"type":"screen","id":"s","content":{"children":[
              {"type":"label","content":{"properties":{"text":"a"}}},
              {"type":"label","content":{"properties":{"text":"b"}}}]}}""",
        ) as ComponentEntity<*>
        val children = screen.content.children.orEmpty()
        assertEquals(2, children.size)
        assertTrue(children.all { it.id.isNotEmpty() })
        assertNotEquals(children[0].id, children[1].id)
        val again = (DslDecoder().decodeScreen(
            """{"type":"screen","id":"s","content":{"children":[{"type":"label","content":{"properties":{"text":"a"}}}]}}""",
        ) as ComponentEntity<*>).content.children!!.single()
        assertEquals(children[0].id, again.id, "the same position gives the same id")
    }

    @Test
    fun `image infers its type from url or asset`() {
        val remote = properties("""{"type":"image","id":"i","content":{"properties":{"url":"https://example.com/a.jpg"}}}""") as ImageProperties
        assertEquals(ImageProperties.Type.remoteAsset, remote.type)
        assertEquals("https://example.com/a.jpg", (remote.model as ImageProperties.Model.RemoteAsset).asset.url)
        val local = properties("""{"type":"image","id":"i","content":{"properties":{"asset":"hero"}}}""") as ImageProperties
        assertEquals(ImageProperties.Model.LocalAsset("hero"), local.model)
        assertEquals(ImageProperties.Type.none, (properties("""{"type":"image","id":"i","content":{"properties":{}}}""") as ImageProperties).type)
    }

    @Test
    fun `incrementVariable by accepts an expression`() {
        val decoded = DslDecoder().decode("""{"type":"incrementVariable","variableName":"total","by":"{{step * 2}}"}""", ::decodeAction)
        assertEquals("{{step * 2}}", decoded.byExpression)
        val store = VariableStore()
        store.defineVariable("total", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("step", VariableDefinition(VariableType.NUMBER, 3))
        val handler = VariableActionHandler()
        handler.execute(VariableAction("incrementVariable", "total", byExpression = decoded.byExpression), store, VariableContext(store))
        assertEquals(7L, store.getValue("total"))
        handler.execute(VariableAction("incrementVariable", "total", by = 2.0), store, VariableContext(store))
        assertEquals(9L, store.getValue("total"))
    }

    @Test
    fun `a bad action in a list reports the element's path`() {
        val e = assertFailsWith<DslDecodingException> {
            DslDecoder().decode(
                """{"type":"with","values":{},"actions":[{"type":"dismiss"},{"type":"noSuchAction"}]}""",
                ::decodeAction,
            )
        }
        assertTrue(e.path.endsWith("actions[1].type"), "path was ${e.path}")
    }
}
