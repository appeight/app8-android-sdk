package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.decodeTextModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of iOS UniversalPropsTests.swift (decoding only).
 * Skipped: render application (isUserInteractionEnabled, clipsToBounds, zIndex, accessibility on UIView),
 * reactive variable updates and UIScrollView paging — they need the renderer / variable store.
 */
class UniversalPropsTest {

    private val decoder = DslDecoder()

    @Test
    fun interactionDecodesLiteralAndExpression() {
        val i = decoder.decode("""{ "enabled": "{{tapsOn}}", "clipsToBounds": "true", "zIndex": "2" }""", ::decodeInteraction)
        assertEquals("{{tapsOn}}", i.enabled)
        assertEquals("true", i.clipsToBounds)
        assertEquals("2", i.zIndex)
        assertTrue(i.hasBindings)
    }

    @Test
    fun accessibilityDecodesFieldsAndTraits() {
        val a = decoder.decode(
            """{ "label": "Profile photo", "hint": "Double tap to change", "value": "{{userName}}", "traits": ["button", "image"], "isElement": "true" }""",
            ::decodeAccessibility,
        )
        assertEquals("Profile photo", a.label)
        assertEquals("{{userName}}", a.value)
        assertEquals(listOf(Accessibility.Trait.BUTTON, Accessibility.Trait.IMAGE), a.traits)
        assertEquals("true", a.isElement)
    }

    @Test
    fun accessibilityTraitRawNames() {
        val a = decoder.decode("""{ "traits": ["disabled", "searchField"] }""", ::decodeAccessibility)
        assertEquals(listOf(Accessibility.Trait.NOT_ENABLED, Accessibility.Trait.SEARCH), a.traits)
    }

    private fun viewContent(content: String): ViewContent =
        decoder.decodeComponent("""{ "type": "view", "id": "v", "content": $content }""").contentAs<ViewContent>()!!

    @Test
    fun contentDecodesUniversalBlocks() {
        val c = viewContent("""{ "interaction": { "enabled": "false" }, "accessibility": { "label": "Card" }, "properties": {} }""")
        assertEquals("false", c.interaction?.enabled)
        assertEquals("Card", c.accessibility?.label)
    }

    @Test
    fun contentWithoutUniversalBlocksDecodesNull() {
        val c = viewContent("""{ "properties": {} }""")
        assertNull(c.interaction)
        assertNull(c.accessibility)
    }

    @Test
    fun scrollViewBehaviorPropsDecode() {
        val p = decoder.decode(
            """{ "direction": "horizontal", "bounces": false, "pagingEnabled": true, "keyboardDismissMode": "onDrag", "decelerationRate": "fast" }""",
            ::decodeScrollViewProperties,
        )
        assertEquals(ScrollViewProperties.Direction.horizontal, p.direction)
        assertEquals(false, p.bounces)
        assertEquals(true, p.pagingEnabled)
        assertEquals(ScrollViewProperties.KeyboardDismissMode.onDrag, p.keyboardDismissMode)
        assertEquals(ScrollViewProperties.DecelerationRate.fast, p.decelerationRate)
    }

    @Test
    fun textModelDecorationDecodes() {
        val m = decoder.decode("""{ "fontSize": 16, "lineBreakMode": "truncateMiddle", "underline": true, "strikethrough": true }""", ::decodeTextModel)
        assertEquals(TextModel.LineBreakMode.truncateMiddle, m.lineBreakMode)
        assertEquals(true, m.underline)
        assertEquals(true, m.strikethrough)
    }
}
