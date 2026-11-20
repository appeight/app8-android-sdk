package dev.app8.engine.render

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.app8.engine.App8DataSource
import dev.app8.engine.App8Error
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.robustness.JsonMutator
import dev.app8.engine.robustness.RobustnessCorpus
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8FontProvider
import dev.app8.engine.style.TextSizer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The renderer pipeline never crashes on DSL it cannot fully handle (robustness fuzz, the
 * renderer half of `engine-core`'s RobustnessFuzzTest). Each corpus unit and seeded mutations
 * of it load through the runtime (app, styles, templates), build a screen session (variables,
 * datasources, render tree), lay out (component traits, layout mapping, the solver at two
 * sizes) and dispatch every trigger's actions. Composition itself needs a device or
 * Robolectric and is not run here.
 *
 * App8Error (a screen or app the host shows as failed) is a clean rejection; any other throwable
 * is a crash path. Text measurement needs the Android text stack, so a measure that fails inside
 * the platform stubs is replaced by a fixed size.
 */
class RobustnessFuzzRenderTest {
    @Test
    fun `corpus screens and their mutations never crash the render pipeline`() {
        val units = RobustnessCorpus.builtIn()
        val report = run(units, VARIANTS)
        println("RobustnessFuzzRenderTest built-in: $report")
        assertTrue(report.screens > 0)
        if (report.failures.isNotEmpty()) fail(report.describe())
    }

    @Test
    fun `conformance fixtures and their mutations never crash the render pipeline`() {
        val units = RobustnessCorpus.conformance()
        assumeTrue("${RobustnessCorpus.FIXTURES_ENV} not set", units.isNotEmpty())
        val report = run(units, CONFORMANCE_VARIANTS)
        println("RobustnessFuzzRenderTest conformance: $report")
        if (report.failures.isNotEmpty()) fail(report.describe())
    }

    class Report {
        var screens = 0
        var rejected = 0
        val failures = mutableListOf<String>()
        val nonFinite = mutableListOf<String>()
        override fun toString() = "${nonFinite.size} non-finite frames, $screens screens built, $rejected rejected cleanly, ${failures.size} crash paths"
        fun describe() = "${failures.size} crash paths:\n" + failures.distinct().take(40).joinToString("\n")
    }

    private fun run(units: List<RobustnessCorpus.Unit>, variants: Int): Report {
        val report = Report()
        val started = System.nanoTime()
        for ((u, unit) in units.withIndex()) {
            check(unit, null, report)
            for (v in 0 until variants) check(unit, JsonMutator(SEED * 1_000_003 + u * 7919L + v), report)
        }
        println("RobustnessFuzzRenderTest: ${(System.nanoTime() - started) / 1_000_000} ms")
        return report
    }

    private fun check(unit: RobustnessCorpus.Unit, mutator: JsonMutator?, report: Report) {
        val docs = unit.docs.toMutableList()
        var label = unit.name
        if (mutator != null) {
            val index = mutator.pick(docs.size)
            val doc = docs[index]
            docs[index] = RobustnessCorpus.Doc(doc.name, doc.kind, mutator.mutate(doc.json, 1 + mutator.pick(4)))
            label = "${unit.name} / ${doc.name} ${mutator.applied}"
        }
        val screens = LinkedHashMap<String, String>()
        for (d in docs.filter { it.kind == RobustnessCorpus.Kind.SCREEN }) screens[screenId(d)] = d.json.toString()
        val app = docs.firstOrNull { it.kind == RobustnessCorpus.Kind.APP }?.json?.toString()
            ?: """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"${screens.keys.firstOrNull() ?: "s"}"}]}}"""
        val source = Source(
            app,
            screens,
            docs.filter { it.kind == RobustnessCorpus.Kind.STYLES }.map { it.json.toString() },
            docs.filter { it.kind == RobustnessCorpus.Kind.TEMPLATE }.map { it.json.toString() },
        )
        val rt = dev.app8.engine.runtime.App8Runtime(TestContext, source, dev.app8.engine.App8Config(), kotlinx.coroutines.Dispatchers.Unconfined, kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            runBlocking { rt.ensureReady() }
        } catch (e: App8Error) {
            report.rejected++
            return
        } catch (e: Throwable) {
            report.failures += "$label: app load threw ${describe(e)}"
            return
        }
        val env = env(rt)
        val dispatcher = ActionDispatcher(rt, RecordingNavigator(), RecordingHost())
        for (id in screens.keys) {
            val session = try {
                runBlocking { ScreenSession.create(rt, Scope(rt.appStore, null), id, emptyMap()) }
            } catch (e: App8Error) {
                report.rejected++
                continue
            } catch (e: Throwable) {
                report.failures += "$label: screen '$id' session threw ${describe(e)}"
                continue
            }
            report.screens++
            val nonFinite = report.nonFinite
            try {
                val (tree, signature) = LayoutTree.build(session.root, env, isRoot = true)
                val cache = SolveCache()
                for (bounds in listOf(LayoutBounds(393.0, 852.0), LayoutBounds(0.0, 0.0))) {
                    val solved = cache.solve(withSafeMeasure(tree), signature, bounds, EdgeInsets(top = 59.0, bottom = 34.0), 3.0)
                    // Frames become pixel constraints: they must be finite and representable.
                    for ((key, f) in solved.local) {
                        val w = pxSize(f.width, 3f)
                        val h = pxSize(f.height, 3f)
                        fixedConstraints(w, h)
                        px(f.x, 3f) + px(f.y, 3f)
                        if (!f.width.isFinite() || !f.height.isFinite()) nonFinite += "$label: '$key' frame $f"
                    }
                }
            } catch (e: Throwable) {
                report.failures += "$label: screen '$id' layout threw ${describe(e)}"
            }
            visit(session.root) { node ->
                try {
                    ComponentRegistry.kind(node).isHidden(node)
                    node.content.actions?.keys?.forEach { trigger -> dispatcher.dispatchTrigger(node, trigger) }
                    dispatcher.dispatchTrigger(node, ActionTrigger.tap)
                } catch (e: Throwable) {
                    report.failures += "$label: '${node.key}' actions threw ${describe(e)}"
                }
            }
            session.dispose()
        }
    }

    private fun screenId(d: RobustnessCorpus.Doc): String {
        val file = d.name.substringAfterLast('/').removeSuffix(".json")
        // Conformance apps navigate by file name; single screens by their own id.
        if (d.name.contains("screens/")) return file
        return ((d.json as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: file
    }

    private fun visit(node: RenderNode, block: (RenderNode) -> Unit) {
        block(node)
        node.children.forEach { visit(it, block) }
    }

    /** Intrinsic measures that fail inside the Android text stubs report a fixed size instead. */
    private fun withSafeMeasure(node: LayoutNode): LayoutNode = LayoutNode(
        key = node.key, id = node.id, layout = node.layout, children = node.children.map(::withSafeMeasure),
        stack = node.stack, scroll = node.scroll, hidden = node.hidden,
        intrinsicSize = node.intrinsicSize?.let { measure ->
            IntrinsicMeasure { w ->
                try {
                    measure.measure(w)
                } catch (e: Throwable) {
                    if (e.stackTrace.any { it.className.startsWith("androidx.compose.ui.text") || it.className.startsWith("android.") }) Size(40.0, 17.0) else throw e
                }
            }
        },
        hugging = node.hugging, compressionResistance = node.compressionResistance, opaque = node.opaque,
    )

    private class Source(
        private val app: String,
        private val screens: Map<String, String>,
        private val styles: List<String>,
        private val templates: List<String>,
    ) : App8DataSource {
        override suspend fun getApp() = app.encodeToByteArray()
        override suspend fun getStyles() = styles.map { it.encodeToByteArray() }
        override suspend fun getComponents() = templates.map { it.encodeToByteArray() }
        override suspend fun getScreen(screenId: String) = screens[screenId]?.encodeToByteArray() ?: throw IllegalArgumentException("no screen $screenId")
        override suspend fun getComponent(componentId: String): ByteArray = throw IllegalArgumentException("no component $componentId")
        override suspend fun getAsset(assetId: String?, assetName: String?): ByteArray? = null
        override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray = """{"data":[]}""".encodeToByteArray()
    }

    /** Enough of a Context for app loading on the JVM: a package name and a files directory (persisted variables). */
    private object TestContext : android.content.ContextWrapper(null) {
        private val dir by lazy { kotlin.io.path.createTempDirectory("app8-robustness").toFile().apply { deleteOnExit() } }
        override fun getPackageName() = "dev.app8.robustness"
        override fun getApplicationContext(): android.content.Context = this
        override fun getFilesDir(): java.io.File = dir
        override fun getCacheDir(): java.io.File = dir
        override fun getDataDir(): java.io.File = dir
        override fun getNoBackupFilesDir(): java.io.File = dir
    }

    private companion object {
        const val SEED = 20261120L
        const val VARIANTS = 120
        const val CONFORMANCE_VARIANTS = 15

        fun env(rt: dev.app8.engine.runtime.App8Runtime): RenderEnv {
            val density = Density(3f)
            val fonts = createFontFamilyResolver(android.content.ContextWrapper(null))
            return RenderEnv(rt, App8Appearance.Light, density, TextMeasurer(fonts, density, LayoutDirection.Ltr), App8FontProvider.System, TextSizer.FollowSystem)
        }

        fun describe(e: Throwable): String {
            val at = e.stackTrace.firstOrNull { it.className.startsWith("dev.app8") }
            return "${e::class.simpleName}: ${e.message?.take(140)} at ${at?.fileName}:${at?.lineNumber}"
        }
    }
}
