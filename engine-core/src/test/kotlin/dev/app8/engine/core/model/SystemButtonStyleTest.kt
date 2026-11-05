package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.StylePointer
import dev.app8.engine.core.model.style.StyleType
import dev.app8.engine.core.model.style.SystemButtonStyle
import dev.app8.engine.core.model.style.decodeButtonStyle
import dev.app8.engine.core.model.style.decodeSystemButtonStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Port of iOS SystemButtonStyleTests.swift. Skipped: makeConfiguration (UIKit) and pointer resolution
// (not ported; the pointer is asserted to be kept instead).
class SystemButtonStyleTest {

    private fun decode(json: String): SystemButtonStyle = DslDecoder().decode(json, ::decodeSystemButtonStyle)

    @Test
    fun fullConfigurationDecodes() {
        val s = decode(
            """{ "variant": "glass", "cornerStyle": "capsule", "size": "large", "role": "primary",
                 "tint": "#007AFF", "foreground": "#FFFFFF", "image": { "type": "symbol", "name": "star.fill" },
                 "imagePlacement": "trailing", "imagePadding": 8, "subtitle": "Tap to continue",
                 "showsActivityIndicator": "{{loading}}" }""",
        )
        assertEquals(SystemButtonStyle.Variant.glass, s.variant)
        assertEquals(SystemButtonStyle.CornerStyle.capsule, s.cornerStyle)
        assertEquals(SystemButtonStyle.Size.large, s.size)
        assertEquals(SystemButtonStyle.Role.primary, s.role)
        assertEquals("007AFF", s.tint.value?.light?.value)
        assertEquals("FFFFFF", s.foreground.value?.light?.value)
        assertEquals(SystemButtonStyle.Image(SystemButtonStyle.Image.Kind.symbol, "star.fill"), s.image)
        assertEquals(SystemButtonStyle.ImagePlacement.trailing, s.imagePlacement)
        assertEquals(8.0, s.imagePadding)
        assertEquals("Tap to continue", s.subtitle)
        assertEquals("{{loading}}", s.showsActivityIndicator)
    }

    @Test
    fun emptyDecodes() {
        val s = decode("{}")
        assertNull(s.variant)
        assertNull(s.tint.value)
        assertNull(s.tint.base)
    }

    @Test
    fun unknownEnumDegradesToDefault() {
        val s = decode("""{ "variant": "neonHologram", "cornerStyle": "warp", "size": "huge", "role": "x", "imagePlacement": "y" }""")
        assertEquals(SystemButtonStyle.Variant.filled, s.variant)
        assertEquals(SystemButtonStyle.CornerStyle.dynamic, s.cornerStyle)
        assertEquals(SystemButtonStyle.Size.medium, s.size)
        assertEquals(SystemButtonStyle.Role.normal, s.role)
        assertEquals(SystemButtonStyle.ImagePlacement.leading, s.imagePlacement)
    }

    @Test
    fun imageTypeIsRequiredButUnknownDegrades() {
        assertEquals(SystemButtonStyle.Image.Kind.symbol, decode("""{ "image": { "type": "sticker", "name": "x" } }""").image?.type)
        assertEquals(SystemButtonStyle.Image.Kind.asset, decode("""{ "image": { "type": "asset", "name": "x" } }""").image?.type)
        assertFailsWith<DslDecodingException> { decode("""{ "image": { "name": "x" } }""") }
    }

    @Test
    fun tintPointerIsKeptUnresolved() {
        val s = decode("""{ "variant": "filled", "tint": { "id": "brand", "type": "color" } }""")
        assertNull(s.tint.value, "pointer is unresolved before resolution")
        val pointer = assertIs<StylePointer>(s.tint.base)
        assertEquals("brand", pointer.id)
        assertEquals(StyleType.Known(StyleType.Key.color), pointer.type)
        assertEquals("brand", s.tint.pointerId)
    }

    @Test
    fun buttonStyleSystemBlockDecodes() {
        val style = DslDecoder().decode("""{ "system": { "variant": "tinted" }, "text": { "fontSize": 15 } }""", ::decodeButtonStyle)
        assertEquals(SystemButtonStyle.Variant.tinted, assertNotNull(style.system.value).variant)
        assertEquals(15.0, style.text.value?.fontSize)
    }
}
