package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Port of iOS CShapeModelTests.swift.
class CShapeModelTest {

    private fun shape(properties: String): ShapeProperties {
        val component = DslDecoder().decodeComponent(
            """{ "type": "shape", "id": "test-shape", "content": { "properties": $properties, "layout": {} } }""",
        )
        return assertNotNull(component.contentAs<ShapeContent>()).properties
    }

    @Test
    fun arcParsesProperties() {
        val p = shape(
            """{ "kind": "arc", "progress": "{{p}}", "startAngle": -90, "lineWidth": 18, "lineCap": "round",
                 "strokeColor": "#FF2D55", "trackColor": "#FF2D5533", "trackLineWidth": 14,
                 "animationDuration": 1.0, "animationCurve": "easeOut" }""",
        )
        assertEquals(ShapeProperties.Kind.arc, p.kind)
        assertEquals(-90.0, p.startAngle)
        assertEquals(18.0, p.lineWidth)
        assertEquals(ShapeProperties.LineCap.round, p.lineCap)
        assertEquals("#FF2D55", p.strokeColor)
        assertEquals("#FF2D5533", p.trackColor)
        assertEquals(14.0, p.trackLineWidth)
        assertEquals("{{p}}", p.progress?.value)
        assertEquals(1.0, p.animationDuration)
        assertEquals("easeOut", p.animationCurve)
        assertNull(p.fillColor)
    }

    @Test
    fun barParsesProperties() {
        val p = shape("""{ "kind": "bar", "progress": "{{p}}", "lineWidth": 20, "lineCap": "round", "strokeColor": "#FF9500", "trackColor": "#FF950033" }""")
        assertEquals(ShapeProperties.Kind.bar, p.kind)
        assertEquals(20.0, p.lineWidth)
        assertEquals(ShapeProperties.LineCap.round, p.lineCap)
        assertEquals("#FF9500", p.strokeColor)
        assertEquals("#FF950033", p.trackColor)
        assertEquals("{{p}}", p.progress?.value)
    }

    @Test
    fun circleWithFillColor() {
        val p = shape("""{ "kind": "circle", "fillColor": "#30D158", "lineWidth": 0 }""")
        assertEquals(ShapeProperties.Kind.circle, p.kind)
        assertEquals("#30D158", p.fillColor)
        assertEquals(0.0, p.lineWidth)
        assertNull(p.strokeColor)
    }

    @Test
    fun circleAsRingWithProgress() {
        val p = shape("""{ "kind": "circle", "strokeColor": "#30D158", "progress": "{{p}}" }""")
        assertNull(p.fillColor)
        assertEquals("#30D158", p.strokeColor)
        assertEquals("{{p}}", p.progress?.value)
    }

    @Test
    fun lineParsesProperties() {
        val p = shape("""{ "kind": "line", "lineWidth": 1, "lineCap": "butt", "strokeColor": "#FFFFFF33" }""")
        assertEquals(ShapeProperties.Kind.line, p.kind)
        assertEquals(1.0, p.lineWidth)
        assertEquals(ShapeProperties.LineCap.butt, p.lineCap)
        assertEquals("#FFFFFF33", p.strokeColor)
        assertNull(p.trackColor)
        assertNull(p.progress)
    }

    @Test
    fun optionalPropertiesAreNullWhenAbsent() {
        assertEquals(ShapeProperties(kind = ShapeProperties.Kind.arc), shape("""{ "kind": "arc" }"""))
    }

    @Test
    fun progressLiteralPreservedAsString() {
        assertEquals("0.75", shape("""{ "kind": "arc", "progress": "0.75" }""").progress?.value)
    }

    @Test
    fun polylinePoints() {
        val p = shape("""{ "kind": "polyline", "points": [ { "x": "0", "y": "{{a}}" }, { "x": "1", "y": "0.5" } ], "smooth": true, "closed": false }""")
        assertEquals(listOf(ShapeProperties.Point("0", "{{a}}"), ShapeProperties.Point("1", "0.5")), p.points)
        assertEquals(true, p.smooth)
        assertEquals(false, p.closed)
    }

    @Test
    fun invalidKindFailsDecoding() {
        assertFailsWith<DslDecodingException> { shape("""{ "kind": "rectangle" }""") }
    }

    @Test
    fun missingPropertiesFailsBecauseKindIsRequired() {
        assertFailsWith<DslDecodingException> {
            DslDecoder().decodeComponent("""{ "type": "shape", "id": "s", "content": { "layout": {} } }""")
        }
    }
}
