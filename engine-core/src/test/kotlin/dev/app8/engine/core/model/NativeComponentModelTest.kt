package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The DSL 0.6 `native` component (contract: app8-dsl `docs/components/native.md`). */
class NativeComponentModelTest {
    private val android = DslDecoder(DecodeOptions(platform = Platform.ANDROID))

    private fun decode(json: String): NativeContent {
        val entity = assertIs<ComponentEntity<*>>(android.decodeComponent(json))
        assertEquals(ComponentType.Known(ComponentType.Key.native), entity.type)
        return assertIs<NativeContent>(entity.content)
    }

    @Test
    fun decodesModulePropsEventActionsAndFallback() {
        val c = decode(
            """
            { "id": "pad", "type": "native", "content": {
                "properties": { "module": "signaturePad",
                  "props": { "tint": "#1E88E5", "colors": ["black", "blue"], "appId": "{{appId}}" }, "isHidden": "{{hidePad}}" },
                "layout": { "constraints": [ { "type": "top", "target": "superview" }, { "type": "bottom", "target": "superview" } ] },
                "actions": {
                  "completed": [
                    { "type": "updateVariable", "variableName": "lastSignature", "value": "{{event.signature}}" },
                    { "type": "navigation", "nextScreen": "receipt", "params": { "signatureId": "{{event.signature.id}}" } }
                  ],
                  "cancelled": { "type": "dismiss" }
                },
                "fallback": { "id": "padFallback", "type": "view", "content": {
                  "children": [ { "id": "msg", "type": "label", "content": { "properties": { "text": "Not available" } } } ] } }
            } }
            """,
        )
        assertEquals("signaturePad", c.properties.module)
        assertEquals(JsonPrimitive("{{appId}}"), c.properties.props["appId"])
        assertEquals(setOf("tint", "colors", "appId"), c.properties.props.keys)
        assertEquals("{{hidePad}}", c.properties.isHidden)
        assertEquals(listOf(Action.Type.updateVariable, Action.Type.navigation), c.events["completed"]?.map { it.type })
        assertEquals(listOf(Action.Type.dismiss), c.events["cancelled"]?.map { it.type }, "a single action object is a one-element list")
        assertNull(c.actions, "module events are not component triggers")
        assertEquals("padFallback", c.fallback?.id)
        assertEquals(listOf("padFallback"), c.children.map { it.id }, "the fallback is the only child")
        assertTrue(c.layout != null)
    }

    @Test
    fun moduleOnlyIsEnough() {
        val c = decode("""{ "id": "n", "type": "native", "content": { "properties": { "module": "signaturePad" } } }""")
        assertTrue(c.properties.props.isEmpty())
        assertTrue(c.events.isEmpty())
        assertNull(c.fallback)
        assertTrue(c.children.isEmpty())
    }

    @Test
    fun missingOrEmptyModuleIsADecodeError() {
        assertFailsWith<Exception> { android.decodeComponent("""{ "id": "n", "type": "native", "content": { "properties": { "props": {} } } }""") }
        assertFailsWith<Exception> { android.decodeComponent("""{ "id": "n", "type": "native", "content": { "properties": { "module": " " } } }""") }
    }

    @Test
    fun aFallbackExcludedOnAndroidIsDropped() {
        val c = decode(
            """{ "id": "n", "type": "native", "content": { "properties": { "module": "signaturePad" },
               "fallback": { "id": "iosOnly", "type": "view", "platforms": ["ios"], "content": {} } } }""",
        )
        assertNull(c.fallback)
    }
}
