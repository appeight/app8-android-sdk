package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** capturePhoto / deletePhoto decoding (app8-dsl docs/photos.md). */
class PhotoActionModelTest {
    @Test
    fun decodesBothActions() {
        val actions = DslDecoder().decode(
            """[{"type":"capturePhoto","source":"camera","camera":"front","aspectRatio":1,"maxDimension":"{{size}}","quality":0.7,
                 "labels":{"camera":"Shoot"},"resultVariable":"avatar","onSuccess":{"type":"haptic","hapticStyle":"success"},"onCancel":[],"onError":[]},
                {"type":"deletePhoto","ref":"{{entry.photo}}"}]""",
            ::decodeActionList,
        )
        val c = actions[0]
        assertEquals(Action.Type.capturePhoto, c.type)
        assertEquals("camera", c.source)
        assertEquals("front", c.camera)
        assertEquals(JsonPrimitive(1), c.aspectRatio)
        assertEquals(JsonPrimitive("{{size}}"), c.maxDimension)
        assertEquals(JsonPrimitive("Shoot"), c.labels?.get("camera"))
        assertEquals("avatar", c.resultVariable)
        assertEquals(1, c.onSuccess?.size)
        assertEquals(Action.Type.deletePhoto, actions[1].type)
        assertEquals("{{entry.photo}}", actions[1].ref)
    }
}
