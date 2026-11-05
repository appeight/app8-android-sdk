package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port of the decode-related parts of iOS HardeningTests.swift: JSON depth limit, deep-merge depth
 * limit, payload size. Skipped: expression recursion / substring / match() ReDoS (expr package),
 * ImageLoader URL allow-list and UIFont fallback (platform layer).
 */
class HardeningTest {

    @Test
    fun deeplyNestedJsonDecodeThrows() {
        val depth = ModelLimits.MAX_JSON_DEPTH + 200
        val json = "[".repeat(depth) + "]".repeat(depth)
        assertFailsWith<DslDecodingException> { DslDecoder().parse(json) }
    }

    @Test
    fun shallowJsonStillDecodes() {
        assertNotNull(DslDecoder().parse("""{"a":{"b":[1,2,3]}}"""))
    }

    @Test
    fun bracketsInsideStringsDoNotCountTowardDepth() {
        val s = "[".repeat(ModelLimits.MAX_JSON_DEPTH + 10)
        assertNotNull(DslDecoder().parse("""{"text":"$s"}"""))
    }

    @Test
    fun deepMergeStopsAtDepthLimitWithoutCrashing() {
        fun nested(depth: Int): JsonElement {
            var v: JsonElement = JsonPrimitive("leaf")
            repeat(depth) { v = JsonObject(mapOf("child" to v)) }
            return v
        }
        val depth = ModelLimits.MAX_DEEP_MERGE_DEPTH + 100
        val merged = DeepMerge.merge(nested(depth) as JsonObject, nested(depth))
        assertNotNull(merged["child"])
    }

    @Test
    fun platformOverrideMergeStopsAtDepthLimit() {
        fun nested(depth: Int): JsonObject {
            var v = JsonObject(mapOf("leaf" to JsonPrimitive(1)))
            repeat(depth) { v = JsonObject(mapOf("child" to v)) }
            return v
        }
        val depth = ModelLimits.MAX_DEEP_MERGE_DEPTH + 100
        assertNotNull(PlatformOverrides.merge(nested(depth), nested(depth))["child"])
    }

    @Test
    fun oversizedPayloadIsRejected() {
        val big = "\"" + "a".repeat(ModelLimits.MAX_DSL_PAYLOAD_BYTES) + "\""
        val e = assertFailsWith<DslDecodingException> { DslDecoder().parse(big) }
        assertTrue(e.reason.contains("exceeds"))
        assertFailsWith<DslDecodingException> { DslDecoder().parse(big.encodeToByteArray()) }
    }

    @Test
    fun componentNestingBeyondLimitIsDropped() {
        fun nested(depth: Int): String {
            var s = """{"type":"view","id":"leaf","content":{}}"""
            repeat(depth) { i -> s = """{"type":"view","id":"v$i","content":{"children":[$s]}}""" }
            return s
        }
        // Within the limit every node survives.
        var c: Component = DslDecoder().decodeComponent(nested(ModelLimits.MAX_COMPONENT_DEPTH))
        var count = 1
        while (c.content!!.children.isNotEmpty()) { c = c.content!!.children.single(); count++ }
        assertEquals(ModelLimits.MAX_COMPONENT_DEPTH + 1, count)
        // Beyond it the too-deep subtree is dropped instead of crashing.
        c = DslDecoder().decodeComponent(nested(ModelLimits.MAX_COMPONENT_DEPTH + 5))
        count = 1
        while (c.content!!.children.isNotEmpty()) { c = c.content!!.children.single(); count++ }
        assertEquals(ModelLimits.MAX_COMPONENT_DEPTH + 1, count)
    }
}
