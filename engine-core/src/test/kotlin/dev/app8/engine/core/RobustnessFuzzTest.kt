package dev.app8.engine.core

import dev.app8.engine.core.expr.ExpressionException
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.DecodeErrorCollector
import dev.app8.engine.core.model.DecodeOptions
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.DslDecodingException
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.TemplateResolver
import dev.app8.engine.core.vars.PropertyResolver
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableException
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.robustness.JsonMutator
import dev.app8.engine.robustness.RobustnessCorpus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The engine never crashes on DSL it cannot fully handle (robustness fuzz). Every corpus document
 * and seeded mutations of it (dropped keys, wrong types, unknown types and enum values, empty
 * arrays, out-of-range numbers, broken expressions) go through decoding, template expansion,
 * variable definition and expression evaluation. A [DslDecodingException] for the document
 * itself is a clean rejection (the host shows the screen as failed); any other throwable, or a
 * dropped element whose cause is not a decoding error, is a crash path.
 */
class RobustnessFuzzTest {
    @Test
    fun `corpus documents and their mutations never crash the model layer`() {
        val units = RobustnessCorpus.builtIn()
        assertTrue(units.size >= 10, "built-in corpus too small: ${units.size}")
        val report = run(units, VARIANTS, SEED)
        println("RobustnessFuzzTest built-in: $report")
        if (report.failures.isNotEmpty()) fail(report.describe())
    }

    @Test
    fun `conformance fixtures and their mutations never crash the model layer`() {
        val units = RobustnessCorpus.conformance()
        assumeTrue("${RobustnessCorpus.FIXTURES_ENV} not set", units.isNotEmpty())
        val report = run(units, CONFORMANCE_VARIANTS, SEED)
        println("RobustnessFuzzTest conformance: $report")
        if (report.failures.isNotEmpty()) fail(report.describe())
    }

    class Report {
        var documents = 0
        var rejected = 0
        val failures = mutableListOf<String>()
        override fun toString() = "$documents documents, $rejected rejected cleanly, ${failures.size} crash paths"
        fun describe() = "${failures.size} crash paths:\n" + failures.distinct().take(40).joinToString("\n")
    }

    private fun run(units: List<RobustnessCorpus.Unit>, variants: Int, seed: Long): Report {
        val report = Report()
        val started = System.nanoTime()
        for ((u, unit) in units.withIndex()) {
            check(unit, null, report)
            for (v in 0 until variants) {
                val mutator = JsonMutator(seed * 1_000_003 + u * 7919L + v)
                check(unit, mutator, report)
            }
        }
        println("RobustnessFuzzTest: ${(System.nanoTime() - started) / 1_000_000} ms")
        return report
    }

    /** Runs one unit; with a [mutator], one of its documents is mutated 1-4 times first. */
    private fun check(unit: RobustnessCorpus.Unit, mutator: JsonMutator?, report: Report) {
        val docs = unit.docs.toMutableList()
        var label = unit.name
        if (mutator != null) {
            val index = mutator.pick(docs.size)
            val doc = docs[index]
            docs[index] = RobustnessCorpus.Doc(doc.name, doc.kind, mutator.mutate(doc.json, 1 + mutator.pick(4)))
            label = "${unit.name} / ${doc.name} ${mutator.applied}"
        }
        val collector = DecodeErrorCollector()
        val decoder = DslDecoder(DecodeOptions(errorCollector = collector))
        fun guard(stage: String, block: () -> Unit) {
            report.documents++
            try {
                block()
            } catch (e: DslDecodingException) {
                report.rejected++
            } catch (e: Throwable) {
                report.failures += "$label: $stage threw ${describe(e)}"
            }
        }
        val templates = mutableListOf<dev.app8.engine.core.model.ComponentTemplate>()
        for (d in docs.filter { it.kind == RobustnessCorpus.Kind.TEMPLATE }) guard("template") { templates += decoder.decodeTemplate(d.json.toString()) }
        var resolver: TemplateResolver? = null
        guard("template resolver") { resolver = TemplateResolver(templates) }
        for (d in docs.filter { it.kind == RobustnessCorpus.Kind.STYLES }) guard("styles") { decoder.decodeStyles(d.json.toString()) }
        for (d in docs.filter { it.kind == RobustnessCorpus.Kind.APP }) guard("app") { decoder.decodeApp(d.json.toString()) }
        for (d in docs.filter { it.kind == RobustnessCorpus.Kind.SCREEN }) {
            guard("screen") {
                val screen = decoder.decodeScreen(d.json.toString(), resolver)
                val variables = ((screen as? ComponentEntity<*>)?.content as? StatefulContent)?.variables.orEmpty()
                val store = VariableStore()
                val defs = LinkedHashMap<String, VariableDefinition>()
                for ((name, element) in variables) {
                    runCatching { LENIENT.decodeFromJsonElement(VariableDefinition.serializer(), element) }.onSuccess { defs[name] = it }
                }
                store.defineVariables(defs) { _, _ -> }
                evaluateAll(d.json, store, label, report)
            }
        }
        for (entry in collector.entries) {
            val cause = entry.error
            if (cause !is DslDecodingException) report.failures += "$label: dropped ${entry.typeName} at ${entry.jsonPath} on ${describe(cause)}"
        }
    }

    /** Resolves every `{{…}}` string like the renderer does; expression and variable errors are expected. */
    private fun evaluateAll(json: JsonElement, store: VariableStore, label: String, report: Report) {
        val resolver = PropertyResolver()
        val context = VariableContext(store)
        fun visit(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach(::visit)
                is JsonArray -> e.forEach(::visit)
                is JsonPrimitive -> if (e.isString && e.content.contains("{{")) {
                    try {
                        resolver.resolve(e.content, context)
                        resolver.resolveToString(e.content, context)
                    } catch (x: ExpressionException) {
                    } catch (x: VariableException) {
                    } catch (x: Throwable) {
                        report.failures += "$label: expression '${e.content.take(60)}' threw ${describe(x)}"
                    }
                }
                else -> {}
            }
        }
        visit(json)
    }

    private companion object {
        const val SEED = 20261120L
        const val VARIANTS = 600
        const val CONFORMANCE_VARIANTS = 60
        val LENIENT = Json { ignoreUnknownKeys = true }

        fun describe(e: Throwable): String {
            val at = e.stackTrace.firstOrNull { it.className.startsWith("dev.app8") }
            return "${e::class.simpleName}: ${e.message?.take(120)} at ${at?.fileName}:${at?.lineNumber}"
        }
    }
}
