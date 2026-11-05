package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.decodeTextModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Port of NewPropertyDecodingTests.swift. Skipped: VariableContext overlay tests
// (variable store / expression runtime, ported separately).
class NewPropertyDecodingTest {

    private fun scroll(json: String) = DslDecoder().decode(json, ::decodeScrollViewProperties)
    private fun text(json: String) = DslDecoder().decode(json, ::decodeTextModel)

    @Test
    fun scrollViewPropertiesDecodeWithThreshold() {
        val p = scroll("""{ "direction": "vertical", "scrollThreshold": 120 }""")
        assertEquals(120.0, p.scrollThreshold)
        assertEquals(ScrollViewProperties.Direction.vertical, p.direction)
    }

    @Test
    fun scrollViewPropertiesDecodeWithFractionalThreshold() {
        assertEquals(44.5, scroll("""{ "scrollThreshold": 44.5 }""").scrollThreshold)
    }

    @Test
    fun scrollViewPropertiesDecodeWithoutThreshold() {
        assertNull(scroll("""{ "direction": "vertical" }""").scrollThreshold)
    }

    @Test
    fun textModelDecodesFixedLetterSpacing() {
        val m = text("""{ "fontSize": 16, "letterSpacing": { "type": "fixed", "value": 2.5 } }""")
        assertEquals(TextModel.LetterSpacing.Type.fixed, m.letterSpacing?.type)
        assertEquals(2.5, m.letterSpacing?.value)
    }

    @Test
    fun textModelDecodesNegativeLetterSpacing() {
        assertEquals(-0.5, text("""{ "fontSize": 20, "letterSpacing": { "type": "fixed", "value": -0.5 } }""").letterSpacing?.value)
    }

    @Test
    fun textModelDecodesBareNumberLetterSpacingAsFixed() {
        val m = text("""{ "fontSize": 16, "letterSpacing": -1.5 }""")
        assertEquals(TextModel.LetterSpacing.Type.fixed, m.letterSpacing?.type)
        assertEquals(-1.5, m.letterSpacing?.value)
    }

    @Test
    fun textModelDecodesIntegerLetterSpacingAsFixed() {
        val m = text("""{ "fontSize": 16, "letterSpacing": 2 }""")
        assertEquals(TextModel.LetterSpacing.Type.fixed, m.letterSpacing?.type)
        assertEquals(2.0, m.letterSpacing?.value)
    }

    @Test
    fun textModelDecodesMultiplierLineHeight() {
        val m = text("""{ "fontSize": 15, "lineHeight": { "type": "multiplier", "value": 1.4 } }""")
        assertEquals(TextModel.LineHeight.Type.multiplier, m.lineHeight?.type)
        assertEquals(1.4, m.lineHeight?.value)
    }

    @Test
    fun textModelDecodesFixedLineHeight() {
        val m = text("""{ "fontSize": 15, "lineHeight": { "type": "fixed", "value": 24 } }""")
        assertEquals(TextModel.LineHeight.Type.fixed, m.lineHeight?.type)
        assertEquals(24.0, m.lineHeight?.value)
    }

    @Test
    fun textModelDecodesAllLineHeightTypes() {
        for (type in TextModel.LineHeight.Type.entries) {
            val m = text("""{ "fontSize": 15, "lineHeight": { "type": "${type.name}", "value": 1.0 } }""")
            assertEquals(type, m.lineHeight?.type)
        }
    }

    @Test
    fun textModelDecodesCombinedLetterSpacingAndLineHeight() {
        val m = text(
            """
            {
                "fontSize": 28, "fontWeight": "bold",
                "letterSpacing": { "type": "fixed", "value": -0.5 },
                "lineHeight": { "type": "multiplier", "value": 1.1 }
            }
            """,
        )
        assertEquals(-0.5, m.letterSpacing?.value)
        assertEquals(1.1, m.lineHeight?.value)
        assertEquals(TextModel.LineHeight.Type.multiplier, m.lineHeight?.type)
    }

    @Test
    fun textModelDecodesWithoutLetterSpacingOrLineHeight() {
        val m = text("""{ "fontSize": 14, "color": "#FFFFFF" }""")
        assertNull(m.letterSpacing)
        assertNull(m.lineHeight)
    }
}
