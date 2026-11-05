package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of TemplatePreprocessorTests.swift. Skipped: testExpressionVarEvaluatesFromParentScope's
// runtime half (ScopedVariableStore evaluation); its preprocessing precondition is kept.
class TemplatePreprocessorTest {

    private val decoder = DslDecoder()

    private fun makePreprocessor(): TemplatePreprocessor {
        val template = decoder.decodeTemplate(
            """
            {
                "id": "ValueDisplay", "type": "view",
                "content": {
                    "variables": { "value": { "type": "string", "initialValue": "" } },
                    "children": [ { "id": "lbl", "type": "label", "content": { "properties": { "text": "{{value}}" } } } ]
                }
            }
            """,
        )
        return TemplatePreprocessor(TemplateResolver(listOf(template)))
    }

    private fun preprocess(screenJSON: String): JsonElement = makePreprocessor().process(decoder.parse(screenJSON))

    private fun screen(variables: String) = """
        {
            "id": "s", "type": "screen",
            "content": { "children": [{ "id": "c", "type": "view", "variables": $variables, "templateId": "ValueDisplay" }] }
        }
    """

    /** result.content.children[0].content.variables.<name> */
    private fun extractVar(name: String, result: JsonElement): JsonObject {
        val child = ((result as JsonObject)["content"] as JsonObject)["children"] as JsonArray
        val content = (child[0] as JsonObject)["content"] as JsonObject
        return (content["variables"] as JsonObject)[name] as JsonObject
    }

    @Test
    fun plainStringBecomesInitialValue() {
        val v = extractVar("value", preprocess(screen("""{ "value": "Hello" }""")))
        assertEquals(JsonPrimitive("Hello"), v["initialValue"])
        assertNull(v["computed"])
    }

    @Test
    fun bareExpressionBecomesComputed() {
        val v = extractVar("value", preprocess(screen("""{ "value": "{{count}}" }""")))
        assertEquals(JsonPrimitive("{{count}}"), v["computed"])
        assertNull(v["initialValue"])
    }

    @Test
    fun interpolatedStringWithPrefixBecomesComputed() {
        val v = extractVar("value", preprocess(screen("""{ "value": "Count: {{count}}" }""")))
        assertEquals(JsonPrimitive("Count: {{count}}"), v["computed"])
        assertNull(v["initialValue"])
    }

    @Test
    fun compoundExpressionBecomesComputed() {
        val v = extractVar("value", preprocess(screen("""{ "value": "{{a.x}} / {{b.y}}" }""")))
        assertEquals(JsonPrimitive("{{a.x}} / {{b.y}}"), v["computed"])
        assertNull(v["initialValue"])
    }

    @Test
    fun fullObjectFormPassesThroughUnchanged() {
        val v = extractVar("value", preprocess(screen("""{ "value": { "type": "string", "computed": "{{x}}" } }""")))
        assertEquals(JsonPrimitive("{{x}}"), v["computed"])
        assertNull(v["initialValue"])
    }

    @Test
    fun expressionVarIsStoredAsComputedAndScreenDecodes() {
        val result = preprocess(screen("""{ "value": "{{count}}" }"""))
        assertEquals(JsonPrimitive("{{count}}"), extractVar("value", result)["computed"])
        // Template children merged in; templateId / instance variables dropped; whole screen decodes.
        val child = ((result as JsonObject)["content"] as JsonObject)["children"]!!.let { (it as JsonArray)[0] as JsonObject }
        assertNull(child["templateId"])
        assertNull(child["variables"])
        val decoded = decoder.decodeComponent(result).content!!.children.single()
        assertEquals("lbl", decoded.content!!.children.single().id)
        assertTrue(decoded.type.isOneOf(ComponentType.Key.view))
    }
}
