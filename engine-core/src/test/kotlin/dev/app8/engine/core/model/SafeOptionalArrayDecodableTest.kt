package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of iOS SafeOptionalArrayDecodableTests.swift. */
class SafeOptionalArrayDecodableTest {

    private fun layout(json: String, collector: DecodeErrorCollector? = null): Layout =
        DslDecoder(DecodeOptions(errorCollector = collector)).decode(json, ::decodeLayout)

    @Test
    fun layoutDecodesWhenConstraintsKeyMissing() {
        assertNull(layout("""{ "top": 0, "bottom": 0, "leading": 0, "trailing": 0 }""").constraints)
    }

    @Test
    fun layoutDecodesWhenConstraintsKeyNull() {
        assertNull(layout("""{ "top": 0, "constraints": null }""").constraints)
    }

    @Test
    fun layoutDecodesWhenConstraintsPresent() {
        val l = layout("""{ "top": 0, "constraints": [ { "type": "bottom", "target": "keyboard", "constant": 8 } ] }""")
        assertEquals(1, l.constraints?.size)
        assertEquals(Layout.Attribute.bottom, l.constraints!!.first().type)
    }

    @Test
    fun layoutDecodesWhenConstraintsEmpty() {
        val l = layout("""{ "leading": 0, "trailing": 0, "constraints": [] }""")
        assertNotNull(l.constraints)
        assertTrue(l.constraints!!.isEmpty())
    }

    @Test
    fun badConstraintIsDroppedAndRecorded() {
        val collector = DecodeErrorCollector()
        val l = layout("""{ "constraints": [ { "type": "bogus" }, { "type": "top", "target": "superview" } ] }""", collector)
        assertEquals(listOf(Layout.Attribute.top), l.constraints!!.map { it.type })
        assertEquals(1, collector.entries.size)
    }

    @Test
    fun noCollectorErrorWhenConstraintsKeyMissing() {
        val collector = DecodeErrorCollector()
        assertNull(layout("""{ "top": 0, "bottom": 0, "leading": 0, "trailing": 0 }""", collector).constraints)
        assertTrue(collector.entries.isEmpty())
    }

    private fun componentDecodesCleanly(json: String, id: String) {
        val collector = DecodeErrorCollector()
        val c = DslDecoder(DecodeOptions(errorCollector = collector)).decodeComponent(json)
        assertEquals(id, c.id)
        assertTrue(collector.entries.isEmpty(), collector.entries.toString())
    }

    @Test
    fun componentWithoutStyleKeyDecodesCleanly() = componentDecodesCleanly(
        """{ "type": "button", "id": "test-button", "content": { "properties": { "text": "Tap me" } } }""",
        "test-button",
    )

    @Test
    fun collectionWithoutStyleKeyDecodesCleanly() = componentDecodesCleanly(
        """{ "type": "collection", "id": "test-collection", "content": { "layout": { "leading": 0, "trailing": 0 },
            "properties": { "data": "{{items}}", "layout": { "type": "vertical", "itemSpacing": 10 } } } }""",
        "test-collection",
    )

    @Test
    fun componentWithLayoutNoConstraintsDecodesCleanly() = componentDecodesCleanly(
        """{ "type": "view", "id": "test-view", "content": { "properties": {}, "style": {},
            "layout": { "top": 0, "bottom": 0, "leading": 0, "trailing": 0 } } }""",
        "test-view",
    )
}
