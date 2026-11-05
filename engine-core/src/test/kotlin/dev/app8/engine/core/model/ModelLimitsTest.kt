package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** iOS `EngineLimits` as enforced by the model layer. */
class ModelLimitsTest {

    @Test
    fun payloadOverEightMegabytesIsRejected() {
        val big = "\"" + "a".repeat(ModelLimits.MAX_DSL_PAYLOAD_BYTES) + "\""
        val e = assertFailsWith<DslDecodingException> { DslDecoder().parse(big) }
        assertTrue(e.reason.contains("exceeds"))
        assertFailsWith<DslDecodingException> { DslDecoder().parse(big.encodeToByteArray()) }
    }

    @Test
    fun multiByteCharactersCountAsUtf8Bytes() {
        // 3 bytes per char in UTF-8: just over the limit although the char count is well below it.
        val s = "\"" + "€".repeat(ModelLimits.MAX_DSL_PAYLOAD_BYTES / 3 + 1) + "\""
        assertFailsWith<DslDecodingException> { DslDecoder().parse(s) }
    }

    @Test
    fun jsonNestedDeeperThanLimitIsRejected() {
        val depth = ModelLimits.MAX_JSON_DEPTH + 200
        val json = "[".repeat(depth) + "]".repeat(depth)
        assertFailsWith<DslDecodingException> { DslDecoder().parse(json) }
        // Brackets inside strings do not count.
        DslDecoder().parse("""{ "a": "${"[".repeat(depth)}" }""")
    }

    @Test
    fun shallowJsonStillParses() {
        DslDecoder().parse("""{"a":{"b":[1,2,3]}}""")
    }

    private fun nested(levels: Int): String {
        var json = """{ "id": "leaf", "type": "view", "content": {} }"""
        repeat(levels) { i -> json = """{ "id": "v$i", "type": "view", "content": { "children": [ $json ] } }""" }
        return json
    }

    private fun depthOf(c: Component): Int = 1 + (c.content?.children?.maxOfOrNull { depthOf(it) } ?: 0)

    @Test
    fun componentTreeWithinLimitDecodesFully() {
        val root = DslDecoder().decodeComponent(nested(ModelLimits.MAX_COMPONENT_DEPTH))
        assertEquals(ModelLimits.MAX_COMPONENT_DEPTH + 1, depthOf(root))
    }

    @Test
    fun componentsDeeperThanLimitAreDroppedNotFatal() {
        val collector = DecodeErrorCollector()
        val root = DslDecoder(DecodeOptions(errorCollector = collector)).decodeComponent(nested(ModelLimits.MAX_COMPONENT_DEPTH + 10))
        assertEquals(ModelLimits.MAX_COMPONENT_DEPTH + 1, depthOf(root))
        assertEquals(1, collector.entries.size)
        assertTrue(collector.entries.single().error.message!!.contains("nested too deeply"))
    }

    @Test
    fun invalidJsonIsADecodingError() {
        assertFailsWith<DslDecodingException> { DslDecoder().parse("{ not json") }
    }
}
