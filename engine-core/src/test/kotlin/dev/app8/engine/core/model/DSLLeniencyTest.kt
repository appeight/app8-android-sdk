package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.decodeTextModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Port of iOS DSLLeniencyTests.swift: documented DSL shapes must decode. */
class DSLLeniencyTest {

    private val decoder = DslDecoder()

    @Test
    fun textAlignmentAcceptsNamesAndIntegers() {
        assertEquals(TextModel.Alignment.center, decoder.decode("""{"fontSize": 13, "alignment": "center"}""", ::decodeTextModel).alignment)
        assertEquals(TextModel.Alignment.right, decoder.decode("""{"fontSize": 13, "alignment": 2}""", ::decodeTextModel).alignment)
        assertFailsWith<DslDecodingException> { decoder.decode("""{"fontSize": 13, "alignment": "middle"}""", ::decodeTextModel) }
    }

    @Test
    fun siblingTargetWrappedAndBare() {
        assertEquals(Layout.Target.Sibling("title"), decoder.decode("\"sibling(title)\"", ::decodeTarget))
        assertEquals(Layout.Target.Sibling("title"), decoder.decode("\"title\"", ::decodeTarget))
        assertEquals(Layout.Target.SafeArea("header"), decoder.decode("\"safeArea(header)\"", ::decodeTarget))
    }

    @Test
    fun collectionLayoutAtContentLevelAndTemplateWithoutId() {
        val c = decoder.decodeComponent(
            """
            { "type": "collection", "id": "list", "content": {
                "properties": { "data": "{{items}}" },
                "layout": { "type": "horizontal", "itemWidth": 240, "itemHeight": 430 },
                "template": { "type": "label", "content": { "properties": { "text": "{{item.title}}" } } } } }
            """,
        )
        val content = assertNotNull(c.contentAs<CollectionContent>())
        assertEquals(CollectionLayout.LayoutType.horizontal, content.properties.layout?.type)
        assertEquals(240.0, content.properties.layout?.itemWidth)
        assertEquals("template", assertNotNull(content.template).id)
    }

    @Test
    fun positioningLayoutDoesNotBecomeCollectionLayout() {
        val c = decoder.decodeComponent(
            """
            { "type": "collection", "id": "list", "content": {
                "properties": { "data": "{{items}}", "layout": { "type": "vertical" } },
                "layout": { "constraints": [ { "type": "top", "target": "superview" } ] },
                "template": { "id": "row", "type": "label", "content": { "properties": { "text": "x" } } } } }
            """,
        )
        val content = assertNotNull(c.contentAs<CollectionContent>())
        assertEquals(CollectionLayout.LayoutType.vertical, content.properties.layout?.type)
        assertNotNull(content.layout)
    }

    @Test
    fun navigationBarSearch() {
        val bar = decoder.decode(
            """{ "search": { "placeholder": "Search", "bindVariable": "q", "placement": "integrated", "onSubmit": { "type": "dismissKeyboard" } } }""",
            ::decodeNavigationBar,
        )
        val search = assertNotNull(bar.search)
        assertEquals("Search", search.placeholder)
        assertEquals("q", search.bindVariable)
        assertEquals(SearchBar.Placement.integrated, search.placement)
        assertEquals(1, search.onSubmit?.size)
        val unknown = decoder.decode("""{ "search": { "placement": "floating" } }""", ::decodeNavigationBar)
        assertEquals(SearchBar.Placement.automatic, unknown.search?.placement)
    }
}
