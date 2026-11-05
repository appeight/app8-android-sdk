package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Port of iOS AnalyticsBindingTests.swift. */
class AnalyticsBindingTest {

    private fun decode(json: String): AnalyticsBinding = DslDecoder().decode(json, ::decodeAnalyticsBinding)

    @Test
    fun shorthandDecodesNameOnly() {
        val b = decode("\"stripeConnectClicked\"")
        assertEquals("stripeConnectClicked", b.name)
        assertNull(b.properties)
    }

    @Test
    fun fullFormDecodesNameAndProperties() {
        val b = decode("""{ "name": "userCardClicked", "properties": { "displayName": "{{name}}", "followers": 42 } }""")
        assertEquals("userCardClicked", b.name)
        assertEquals(JsonPrimitive("{{name}}"), b.properties!!["displayName"])
        assertEquals(42, (b.properties!!["followers"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun fullFormWithoutPropertiesDecodes() {
        val b = decode("""{ "name": "simpleEvent" }""")
        assertEquals("simpleEvent", b.name)
        assertNull(b.properties)
    }

    @Test
    fun malformedShapeThrows() {
        assertFailsWith<DslDecodingException> { decode("""{ "name": 123 }""") }
    }
}
