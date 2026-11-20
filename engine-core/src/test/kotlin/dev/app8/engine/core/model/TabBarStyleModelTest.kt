package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.StylePointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `tabBarScreen.content.barStyle` (tab-bar-screen.md, "Bar style"). */
class TabBarStyleModelTest {

    private fun tabBar(barStyle: String?): TabBarScreenContent {
        val style = barStyle?.let { """, "barStyle": $it""" }.orEmpty()
        val json = """
            {
              "type": "tabBarScreen", "id": "tabs",
              "content": {
                "tabs": [
                  { "id": "home", "label": "HOME", "icon": "house", "screen": "homeScreen" },
                  { "id": "bag", "label": "BAG", "icon": "bag", "screen": "bagScreen", "badge": "{{n}}" }
                ],
                "tintColor": "#000000"$style
              }
            }
        """
        val entity = assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent(json))
        return assertIs<TabBarScreenContent>(entity.content)
    }

    @Test
    fun noBarStyleIsNull() {
        val c = tabBar(null)
        assertNull(c.barStyle)
        assertEquals(2, c.tabs.size)
    }

    @Test
    fun emptyBarStyleIsTheSystemBar() {
        val s = assertNotNull(tabBar("{}").barStyle)
        assertEquals(TabBarStyle.Variant.system, s.variant)
        assertNull(s.selection)
        assertEquals(TabBarStyle.Selection.capsule, s.effectiveSelection)
        assertEquals(0.5, s.unselectedOpacity)
        assertNull(s.background)
        assertNull(s.height)
        assertNull(s.component)
        assertTrue(s.isSystem)
    }

    @Test
    fun decodesTextVariantWithAllKeys() {
        val s = assertNotNull(
            tabBar(
                """
                {
                  "variant": "text", "background": "glass", "selection": "opacity",
                  "text": { "id": "tabLabel" }, "selectedText": { "fontSize": 13, "fontWeight": "bold" },
                  "unselectedOpacity": 0.45, "indicatorColor": "#11111122", "height": 52
                }
                """,
            ).barStyle,
        )
        assertEquals(TabBarStyle.Variant.text, s.variant)
        assertEquals("glass", s.background)
        assertEquals(TabBarStyle.Selection.opacity, s.selection)
        assertEquals("tabLabel", assertIs<StylePointer>(s.text.base).id)
        assertEquals(13.0, s.selectedText.value?.fontSize)
        assertEquals(0.45, s.unselectedOpacity)
        assertEquals("#11111122", s.indicatorColor)
        assertEquals(52.0, s.height)
        assertFalse(s.isSystem)
    }

    @Test
    fun decodesEverySelection() {
        for (sel in TabBarStyle.Selection.entries) {
            assertEquals(sel, tabBar("""{ "variant": "text", "selection": "${sel.name}" }""").barStyle?.selection)
        }
    }

    @Test
    fun backgroundStyleIdIsKept() {
        assertEquals("tabBarFill", tabBar("""{ "variant": "text", "background": "tabBarFill" }""").barStyle?.background)
        assertEquals("solid", tabBar("""{ "variant": "text", "background": "solid" }""").barStyle?.background)
    }

    @Test
    fun componentBarIgnoresVariant() {
        val s = assertNotNull(tabBar("""{ "component": "BarCustom", "background": "solid" }""").barStyle)
        assertEquals("BarCustom", s.component)
        assertEquals(TabBarStyle.Variant.system, s.variant)
        assertNull(s.selection)
        assertFalse(s.isSystem)
        assertEquals(
            TabBarStyle.Selection.underline,
            tabBar("""{ "component": "BarCustom", "selection": "underline" }""").barStyle?.selection,
        )
    }

    @Test
    fun heightIsAtLeast44AndOpacityClamped() {
        val s = assertNotNull(tabBar("""{ "variant": "text", "height": 20, "unselectedOpacity": 3 }""").barStyle)
        assertEquals(TabBarStyle.MIN_HEIGHT, s.height)
        assertEquals(1.0, s.unselectedOpacity)
        assertEquals(0.0, tabBar("""{ "variant": "text", "unselectedOpacity": -1 }""").barStyle?.unselectedOpacity)
    }

    @Test
    fun unknownValuesFallBackToDefaults() {
        val s = assertNotNull(tabBar("""{ "variant": "floating", "selection": "glow", "height": "tall", "unselectedOpacity": "x" }""").barStyle)
        assertEquals(TabBarStyle.Variant.system, s.variant)
        assertNull(s.selection)
        assertEquals(TabBarStyle.Selection.capsule, s.effectiveSelection)
        assertNull(s.height)
        assertEquals(0.5, s.unselectedOpacity)
    }

    @Test
    fun malformedBarStyleKeepsTheTabBar() {
        val c = tabBar("\"text\"")
        assertNull(c.barStyle)
        assertEquals(2, c.tabs.size)
    }

    @Test
    fun selectTabActionDecodesIdAndIndex() {
        val byId = decodeActionJson("""{ "type": "selectTab", "tabId": "{{item.id}}" }""")
        assertEquals(Action.Type.selectTab, byId.type)
        assertEquals("{{item.id}}", byId.tabId)
        assertEquals(2, decodeActionJson("""{ "type": "selectTab", "tabIndex": 2 }""").tabIndex)
    }

    private fun decodeActionJson(json: String): Action {
        val c = DslDecoder().decodeComponent(
            """{ "type": "view", "id": "v", "content": { "actions": { "tap": [ $json ] } } }""",
        )
        val entity = assertIs<ComponentEntity<*>>(c)
        return assertNotNull(entity.content.actions?.get(ActionTrigger.tap)?.single())
    }

    @Test
    fun selectionIndicatorColorDecodes() {
        // 0.7.9: the platform bar's selected-item highlight.
        val json = """
            { "type": "tabBarScreen", "id": "tabs", "content": {
              "tabs": [{ "id": "home", "label": "HOME", "icon": "house", "screen": "homeScreen" }],
              "selectionIndicatorColor": "#E0A10040" } }
        """
        val c = assertIs<TabBarScreenContent>(assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent(json)).content)
        assertEquals("#E0A10040", c.selectionIndicatorColor)
        assertNull(tabBar(null).selectionIndicatorColor)
    }

    @Test
    fun collectionItemWidthAuto() {
        val json = """
            { "type": "collection", "id": "chips", "content": { "properties": { "data": "{{chips}}",
              "layout": { "type": "horizontal", "itemWidth": "auto", "itemHeight": 36 } } } }
        """
        val entity = assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent(json))
        val layout = assertNotNull((entity.content.properties as? CollectionProperties)?.layout)
        assertTrue(layout.itemWidthAuto)
        assertNull(layout.itemWidth)
        assertFalse(layout.itemWidthFill)
    }
}
