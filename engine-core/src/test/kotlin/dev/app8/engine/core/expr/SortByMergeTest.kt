package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * sortBy and merge (app8-dsl docs/variables.md): the shared input/output table from
 * app8-conformance (fixtures/functions/sortBy-merge.json), kept as test resource
 * functions/sortBy-merge.json.
 */
class SortByMergeTest {
    private val text = javaClass.classLoader!!.getResource("functions/sortBy-merge.json")!!.readText()
    private val table = Json.parseToJsonElement(text).jsonObject

    /** Numbers compare by value (1 == 1.0). */
    private fun normalized(v: Any?): Any? = when (v) {
        null, Values.Null -> null
        is Boolean -> v
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to normalized(x) }
        is List<*> -> v.map(::normalized)
        else -> v
    }

    @Test
    fun sharedTable() {
        val store = VariableStore()
        for ((name, value) in table["variables"]!!.jsonObject) {
            val v = Values.fromJson(value)
            val type = when (v) { is List<*> -> VariableType.ARRAY; is String -> VariableType.STRING; else -> VariableType.OBJECT }
            store.defineVariable(name, VariableDefinition(type, v))
        }
        val evaluator = ExpressionEvaluator()
        val parser = ExpressionParser()
        for (c in table["cases"]!!.jsonArray) {
            val expression = c.jsonObject["expression"]!!.jsonPrimitive.content
            val result = evaluator.evaluate(parser.parse(expression), VariableContext(store))
            assertEquals(normalized(Values.fromJson(c.jsonObject["expected"] as JsonElement)), normalized(result), expression)
        }
    }

    @Test
    fun vendoredTableMatchesConformance() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (name in listOf("app8-conformance", "app8-conformance-wt-dsl-gaps")) {
                val f = File(dir, "$name/fixtures/functions/sortBy-merge.json")
                if (f.isFile) {
                    assertEquals(Json.parseToJsonElement(f.readText()), Json.parseToJsonElement(text), "${f.path} differs: copy it to src/test/resources/functions/")
                    return
                }
            }
            dir = dir.parentFile
        }
    }
}
