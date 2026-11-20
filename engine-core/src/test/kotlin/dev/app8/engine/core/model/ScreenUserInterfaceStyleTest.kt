package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Screen `userInterfaceStyle` (DSL 0.7.10): `light` / `dark`, anything else follows the app. */
class ScreenUserInterfaceStyleTest {
    private fun style(field: String): App.UserInterfaceStyle? {
        val json = """{"type":"screen","id":"s","content":{$field"navigationBar":{"title":"T"},"children":[]}}"""
        val entity = assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent(json))
        return assertIs<ComponentContent<*, *>>(entity.content).userInterfaceStyle
    }

    @Test
    fun `light and dark decode`() {
        assertEquals(App.UserInterfaceStyle.light, style(""""userInterfaceStyle":"light","""))
        assertEquals(App.UserInterfaceStyle.dark, style(""""userInterfaceStyle":"dark","""))
    }

    @Test
    fun `absent or null follows the app`() {
        assertNull(style(""))
        assertNull(style(""""userInterfaceStyle":null,"""))
    }

    @Test
    fun `an unknown value or type is ignored and the screen still decodes`() {
        assertNull(style(""""userInterfaceStyle":"sepia","""))
        assertNull(style(""""userInterfaceStyle":"Dark","""))
        assertNull(style(""""userInterfaceStyle":1,"""))
        assertNull(style(""""userInterfaceStyle":{"light":"dark"},"""))
    }
}
