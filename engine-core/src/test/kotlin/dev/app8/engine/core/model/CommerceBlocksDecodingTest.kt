package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.decodePickerStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.5.0 "commerce building blocks": decoding of the new spec JSON. */
class CommerceBlocksDecodingTest {

    @Test
    fun contextMenuDecodesPreviewItemsAndPreviewTap() {
        val c = DslDecoder().decodeComponent(
            """
            {"type":"view","id":"card","content":{"contextMenu":{
              "preview":{"component":{"type":"label","content":{"properties":{"text":"Hi"}}},"width":340},
              "items":[
                {"id":"add","title":"Add to bag","icon":"bag","actions":[{"type":"toggleArrayValue","variableName":"wishlist","value":"{{item.handle}}"}]},
                {"id":"del","title":"Remove","subtitle":"{{item.title}}","destructive":true,"isEnabled":"{{ok}}","hidden":false,"actions":[]}
              ],
              "onPreviewTap":[{"type":"navigation","nextScreen":"product"}],
              "isEnabled":true
            }}}
            """,
        )
        val menu = assertNotNull(c.contentAs<ViewContent>()?.contextMenu)
        val preview = assertNotNull(menu.preview)
        assertEquals("contextMenuPreview", (preview.component as ComponentEntity<*>).id)
        assertEquals(340.0, preview.width)
        assertNull(preview.height)
        assertEquals(listOf("add", "del"), menu.items.map { it.id })
        assertEquals(1, menu.items[0].actions.size)
        assertTrue(menu.items[1].destructive)
        assertEquals("{{ok}}", menu.items[1].isEnabled)
        assertEquals("false", menu.items[1].hidden)
        assertEquals("{{item.title}}", menu.items[1].subtitle)
        assertEquals(1, menu.onPreviewTap?.size)
        assertEquals("true", menu.isEnabled)
    }

    @Test
    fun malformedContextMenuNeverRejectsTheComponent() {
        val c = DslDecoder().decodeComponent("""{"type":"view","id":"v","content":{"contextMenu":42}}""")
        assertNull(c.contentAs<ViewContent>()?.contextMenu)
    }

    @Test
    fun pickerMenuLookBindKeyAndExpressionOptions() {
        val p = DslDecoder().decode(
            """{"displayMode":"menu","options":"{{item.variants}}","bindVariable":"sel","bindKey":"{{item.handle}}"}""",
            ::decodePickerProperties,
        )
        assertNull(p.options)
        assertEquals("{{item.variants}}", p.optionsExpression)
        assertEquals("{{item.handle}}", p.bindKey)

        val literal = DslDecoder().decode("""{"options":[{"value":"a","label":"A"}]}""", ::decodePickerProperties)
        assertEquals("a", literal.options?.single()?.value)
        assertNull(literal.optionsExpression)

        val s = DslDecoder().decode(
            """{"label":{"fontSize":13,"color":"#8A8A8A"},"indicator":"chevron.up.chevron.down","indicatorColor":"#000","indicatorSize":10,"indicatorSpacing":4}""",
            ::decodePickerStyle,
        )
        assertTrue(s.isPullDown)
        assertEquals(13.0, s.label.value?.fontSize)
        assertEquals("chevron.up.chevron.down", s.indicator)
        assertEquals(10.0, s.indicatorSize)
        assertEquals(4.0, s.indicatorSpacing)
        assertFalse(DslDecoder().decode("""{}""", ::decodePickerStyle).isPullDown)
        assertTrue(DslDecoder().decode("""{"indicator":""}""", ::decodePickerStyle).isPullDown)
    }

    @Test
    fun tabBarBadgeColors() {
        val c = DslDecoder().decodeComponent(
            """
            {"type":"tabBarScreen","id":"tabs","content":{
              "tabs":[{"id":"bag","label":"Bag","icon":"bag","screen":"bag","badge":"{{n}}","badgeColor":"#FF0000"},
                      {"id":"home","label":"Home","icon":"house","screen":"home"}],
              "badgeColor":"#2D4D3A","badgeTextColor":"#FFFFFF"}}
            """,
        )
        val content = assertNotNull(c.contentAs<TabBarScreenContent>())
        assertEquals("#2D4D3A", content.badgeColor)
        assertEquals("#FFFFFF", content.badgeTextColor)
        assertEquals("#FF0000", content.tabs[0].badgeColor)
        assertNull(content.tabs[1].badgeColor)
    }

    @Test
    fun scrollViewZoomProperties() {
        val p = DslDecoder().decode(
            """{"minimumZoomScale":1,"maximumZoomScale":4,"doubleTapZoom":false,"stretchesTopChild":true}""",
            ::decodeScrollViewProperties,
        )
        assertEquals(1.0, p.minimumZoomScale)
        assertEquals(4.0, p.maximumZoomScale)
        assertEquals(false, p.doubleTapZoom)
        assertEquals(true, p.stretchesTopChild)
        assertTrue(p.isZoomEnabled)
        assertFalse(DslDecoder().decode("""{}""", ::decodeScrollViewProperties).isZoomEnabled)
    }

    @Test
    fun nativeZoomTransition() {
        val t = DslDecoder().decode(
            """{"preset":"zoom","sourceKey":"{{'card-' + item.handle}}","alignmentKey":"hero","dismissGesture":"down"}""",
            ::decodeScreenTransition,
        ) as ScreenTransition.Inline
        assertTrue(t.isNativeZoom)
        assertEquals("{{'card-' + item.handle}}", t.sourceKey)
        assertEquals("hero", t.alignmentKey)
        assertEquals(ScreenTransition.DismissGesture.down, t.dismissGesture)
        val plain = DslDecoder().decode("""{"preset":"zoom"}""", ::decodeScreenTransition) as ScreenTransition.Inline
        assertFalse(plain.isNativeZoom)
        assertNull(plain.dismissGesture)
    }

    @Test
    fun appIdDecodes() {
        val app = DslDecoder().decodeApp("""{"id":"facetheory","title":"T"}""")
        assertEquals("facetheory", app.id)
    }
}
