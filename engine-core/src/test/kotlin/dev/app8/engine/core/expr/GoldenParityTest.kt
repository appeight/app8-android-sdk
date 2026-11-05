package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Differential test against the iOS engine: `golden-expected.txt` was produced by compiling the
 * real Swift sources (see `resources/vars/swift-oracle/regenerate.sh`) and evaluating every line
 * of `golden-corpus.txt` against `golden-vars.json` with locale en_US and TZ=UTC.
 */
class GoldenParityTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/vars/$name")) { "missing $name" }.readText()

    @Test
    fun everyCorpusExpressionMatchesSwift() {
        val defs = Json.decodeFromString(MapSerializer(String.serializer(), VariableDefinition.serializer()), resource("golden-vars.json"))
        val store = VariableStore()
        store.defineVariables(defs)
        val parser = ExpressionParser()
        val evaluator = ExpressionEvaluator().apply {
            locale = Locale.US
            clock = Clock.system(ZoneOffset.UTC)
        }
        val corpus = resource("golden-corpus.txt").lines().filter { it.isNotEmpty() }
        val expected = resource("golden-expected.txt").lines().filter { it.isNotEmpty() }
        assertEquals(corpus.size, expected.size, "corpus / expected line count")

        val failures = corpus.indices.mapNotNull { i ->
            val actual = try {
                repr(evaluator.evaluate(parser.parse(corpus[i]), VariableContext(store)))
            } catch (e: Exception) {
                "ERR:" + e.message
            }
            if (actual == expected[i]) null else "  ${corpus[i]}\n    swift:  ${expected[i]}\n    kotlin: $actual"
        }
        if (failures.isNotEmpty()) fail("${failures.size}/${corpus.size} mismatches:\n" + failures.joinToString("\n"))
    }

    private fun repr(v: Any?): String = when {
        v == null -> "N"
        v === Values.Null -> "NULL"
        v is Boolean -> "B:$v"
        v is Long -> "I:$v"
        v is Double -> "D:" + SwiftCompat.doubleDescription(v)
        v is String -> "S:\"" + v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t").replace("\"", "\\\"") + "\""
        v is List<*> -> v.joinToString(",", "[", "]") { repr(it) }
        v is Map<*, *> -> v.keys.map { it as String }.sorted().joinToString(",", "{", "}") { "$it=" + repr(v[it]) }
        else -> "?${v::class.simpleName}"
    }
}
