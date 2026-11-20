package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unknown or mistyped values are ignored, never fatal: an element of a lenient list loses the value, not itself. */
class IgnoredValuesTest {
    private fun screen(children: String, collector: DecodeErrorCollector = DecodeErrorCollector()) =
        DslDecoder(DecodeOptions(errorCollector = collector))
            .decodeComponent("""{"type":"screen","id":"s","content":{"children":[$children]}}""") as ComponentEntity<*>

    @Test
    fun `a child with one mistyped property keeps the rest`() {
        val collector = DecodeErrorCollector()
        val root = screen("""{"type":"label","id":"l","content":{"properties":{"text":"Hello","numberOfLines":"two"}}}""", collector)
        val label = root.content.children.single() as ComponentEntity<*>
        val p = label.content.properties as LabelProperties
        assertEquals(LocalizedString.Literal("Hello"), p.text)
        assertNull(p.numberOfLines)
        assertEquals("content.children[0].content.properties.numberOfLines", collector.entries.single().jsonPath)
    }

    @Test
    fun `an unknown component type is still skipped`() {
        val collector = DecodeErrorCollector()
        val root = screen(
            """{"type":"noSuchThing","id":"x","content":{}},{"type":"label","id":"l","content":{"properties":{"text":"ok"}}}""",
            collector,
        )
        assertEquals(listOf("l"), root.content.children.map { it.id })
        assertEquals("content.children[0]", collector.entries.single().jsonPath)
    }

    @Test
    fun `several bad values in one element are all ignored`() {
        val root = screen(
            """{"type":"view","id":"v","content":{"properties":{"isHidden":[1]},"layout":{"height":"tall"},
               "actions":{"tap":[{"type":"incrementVariable","variableName":5},{"type":"dismiss"}]},
               "children":[{"type":"label","id":"in","content":{"properties":{"text":"x","numberOfLines":{}}}}]}}""",
        )
        val view = root.content.children.single() as ComponentEntity<*>
        assertEquals(listOf("in"), view.content.children.map { it.id })
        // The mistyped variableName is ignored; both actions stay (the first now targets nothing).
        assertEquals(2, view.content.actions?.get(ActionTrigger.tap)?.size)
    }

    @Test
    fun `paths are relative to the element`() {
        assertEquals("content.style", JsonPathEdit.relative("content.children[2].content.style", "content.children[2]"))
        assertEquals("", JsonPathEdit.relative("content.children[2]", "content.children[2]"))
        assertNull(JsonPathEdit.relative("content.children[20].x", "content.children[2]"))
        assertEquals("[1].a", JsonPathEdit.relative("tabs[1].a", "tabs"))
        assertTrue(JsonPathEdit.remove(kotlinx.serialization.json.JsonObject(emptyMap()), "") == null)
    }
}
