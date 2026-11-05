package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.IconStyle
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StylePointer
import dev.app8.engine.core.model.style.StyleType
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.decodeIconStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

// Port of iOS IconStylePointerTests.swift. Pointer resolution is not ported (parsing only), so the
// resolution tests assert that the `icon` self-pointer and nested color refs are decoded and kept.
class IconStylePointerTest {

    private fun icon(json: String): IconStyle = DslDecoder().decode(json, ::decodeIconStyle)

    @Test
    fun iconSelfPointerIsKept() {
        val s = icon("""{ "icon": { "id": "onAccentIcon", "type": "icon" } }""")
        assertNull(s.tint.value, "tint is nil before resolution")
        val pointer = assertIs<StylePointer>(s.iconPointer)
        assertEquals("onAccentIcon", pointer.id)
        assertEquals(StyleType.Known(StyleType.Key.icon), pointer.type)
    }

    @Test
    fun registryIconStyleKeepsTintAndColorRefs() {
        val styles = DslDecoder().decodeStyles(
            """[
              { "id": "white", "type": "color", "content": "#FFFFFF" },
              { "id": "textOnAccent", "type": "color", "content": { "light": { "id": "white" }, "dark": { "id": "white" } } },
              { "id": "onAccentIcon", "type": "icon", "content": { "tint": { "id": "textOnAccent", "type": "color" } } },
              { "id": "accentIcon", "type": "icon", "content": { "color": { "id": "accent", "type": "color" } } }
            ]""",
        ).associateBy { it.id }
        val onAccent = assertIs<IconStyle>((styles["onAccentIcon"] as StyleEntity<*>).content)
        assertEquals("textOnAccent", onAccent.tint.pointerId)
        val accent = assertIs<IconStyle>((styles["accentIcon"] as StyleEntity<*>).content)
        assertEquals("accent", accent.color.pointerId)
        val chained = assertIs<ThemedColor>((styles["textOnAccent"] as StyleEntity<*>).content)
        assertEquals("white", chained.lightRef)
        assertEquals("white", chained.darkRef)
        assertEquals("FFFFFF", assertIs<ThemedColor>((styles["white"] as StyleEntity<*>).content).light?.value)
    }

    @Test
    fun inlineTintPointerDecodesNormally() {
        val s = icon("""{ "tint": { "id": "white", "type": "color" } }""")
        assertNull(s.iconPointer)
        assertEquals("white", s.tint.pointerId)
    }

    @Test
    fun inlineTintHexDecodes() {
        assertEquals("FFFFFF", icon("""{ "tint": "#FFFFFF" }""").tint.value?.light?.value)
    }

    @Test
    fun emptyDecodes() {
        val s = icon("{}")
        assertNull(s.tint.value)
        assertNull(s.color.value)
        assertNull(s.iconPointer)
    }

    @Test
    fun invalidIconPointerFailsLikeIos() {
        // `icon` is decodeIfPresent(Style.Any): a present-but-invalid value throws.
        assertFailsWith<DslDecodingException> { icon("""{ "icon": "onAccentIcon" }""") }
    }
}
