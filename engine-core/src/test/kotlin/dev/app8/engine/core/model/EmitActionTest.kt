package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of iOS EmitActionTests.swift. */
class EmitActionTest {

    private fun action(json: String): Action = DslDecoder().decode(json, ::decodeAction)

    @Test
    fun emitDecodesNameOnly() {
        val a = action("""{ "type": "emit", "name": "connect.tapped" }""")
        assertEquals(Action.Type.emit, a.type)
        assertEquals("connect.tapped", a.name)
        assertNull(a.payload)
    }

    @Test
    fun emitDecodesWithPayload() {
        val a = action(
            """{ "type": "emit", "name": "user.selected", "payload": { "displayName": "{{name}}", "followers": 1024, "verified": true } }""",
        )
        assertEquals("user.selected", a.name)
        val p = a.payload!!
        assertEquals(JsonPrimitive("{{name}}"), p["displayName"])
        assertEquals(JsonPrimitive(1024), p["followers"])
        assertEquals(JsonPrimitive(true), p["verified"])
    }

    @Test
    fun emitWithEmptyPayloadDecodes() {
        assertTrue(action("""{ "type": "emit", "name": "foo.bar", "payload": {} }""").payload!!.isEmpty())
    }

    @Test
    fun emitMissingNameStillDecodesButNameIsNull() {
        val a = action("""{ "type": "emit" }""")
        assertEquals(Action.Type.emit, a.type)
        assertNull(a.name)
    }
}
