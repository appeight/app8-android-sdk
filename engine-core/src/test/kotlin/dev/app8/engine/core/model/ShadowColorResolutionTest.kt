package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.Shadow
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.ThemedColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of iOS ShadowColorResolutionTests.swift. Pointer RESOLUTION is not ported (parsing only):
// the reference test asserts that `{ "id": … }` color refs inside a shadow layer are kept for later resolution.
class ShadowColorResolutionTest {

    private fun shadow(stylesJson: String, id: String): Shadow {
        val style = DslDecoder().decodeStyles(stylesJson).first { it.id == id }
        return assertIs<Shadow>(assertIs<StyleEntity<*>>(style).content)
    }

    @Test
    fun shadowKeepsColorReference() {
        val s = shadow(
            """[
              { "id": "shadowGray", "type": "color", "content": "#444444" },
              { "id": "testShadow", "type": "shadow", "content": { "layers": [{
                  "isHollow": true,
                  "color": { "light": { "id": "shadowGray" }, "dark": { "id": "shadowGray" } },
                  "radius": 12, "offset": { "x": 0, "y": 6 }, "opacity": 0.2 }] } }
            ]""",
            "testShadow",
        )
        assertEquals(1, s.layers.size)
        val color = s.layers[0].color
        assertFalse(color.isResolved)
        assertEquals("shadowGray", color.lightRef)
        assertEquals("shadowGray", color.darkRef)
        assertEquals(listOf("shadowGray", "shadowGray"), color.unresolvedPointerIds)
        assertEquals(12.0, s.layers[0].radius)
        assertEquals(Shadow.Offset(0.0, 6.0), s.layers[0].offset)
    }

    @Test
    fun colorStyleWithStringContentDecodes() {
        val style = DslDecoder().decodeStyles("""[ { "id": "shadowGray", "type": "color", "content": "#444444" } ]""").single()
        val color = assertIs<ThemedColor>(assertIs<StyleEntity<*>>(style).content)
        assertEquals("444444", color.light?.value)
    }

    @Test
    fun shadowWorksWithDirectHexColor() {
        val s = shadow(
            """[ { "id": "directShadow", "type": "shadow", "content": { "layers": [{
                  "isHollow": true, "color": "#000000", "radius": 10, "offset": { "x": 0, "y": 4 }, "opacity": 0.1 }] } } ]""",
            "directShadow",
        )
        assertEquals(1, s.layers.size)
        val layer = s.layers[0]
        assertTrue(layer.color.isResolved)
        assertEquals("000000", layer.color.light?.value)
        assertNull(layer.color.dark)
    }

    @Test
    fun badShadowLayerIsDroppedNotTheShadow() {
        // `color` is required; the SafeArray drops the bad layer, not the shadow.
        val s = shadow(
            """[ { "id": "s", "type": "shadow", "content": { "layers": [
                  { "radius": 10, "offset": { "x": 0, "y": 4 }, "opacity": 0.1 },
                  { "isHollow": false, "color": "#000000", "radius": 2, "offset": { "x": 0, "y": 1 }, "opacity": 0.3 } ] } } ]""",
            "s",
        )
        assertEquals(1, s.layers.size)
        assertEquals(2.0, s.layers[0].radius)
    }
}
