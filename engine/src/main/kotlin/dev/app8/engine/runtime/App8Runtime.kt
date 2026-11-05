package dev.app8.engine.runtime

import android.content.Context
import dev.app8.engine.App8Config
import dev.app8.engine.App8DataSource
import dev.app8.engine.App8Error
import dev.app8.engine.core.model.App
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.ComponentTemplate
import dev.app8.engine.core.model.DecodeErrorCollector
import dev.app8.engine.core.model.DecodeOptions
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.Platform
import dev.app8.engine.core.model.TemplateResolver
import dev.app8.engine.core.model.style.StyleAny
import dev.app8.engine.core.vars.PropertyResolver
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.events.App8AnalyticsBus
import dev.app8.engine.events.App8AnalyticsConfig
import dev.app8.engine.events.App8EventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Per-instance engine services (iOS `App8Context` + the loading half of `A8`): the decoded app,
 * style registry, templates, translations, the app-scope variable store and the event buses.
 * Main-thread confined except for the suspend loaders, which hop to IO for data-source calls.
 */
internal class App8Runtime(
    val appContext: Context,
    val dataSource: App8DataSource,
    config: App8Config,
) {
    val diagnostics = Diagnostics(config.logLevel)
    val translations = TranslationStore().apply { setActive(config.locale) }
    val eventBus = App8EventBus()
    val analyticsBus = App8AnalyticsBus()
    var analyticsConfig: App8AnalyticsConfig = config.analytics
    val mapProvider: dev.app8.engine.maps.App8MapProvider? = config.mapProvider

    private val decodeErrors = DecodeErrorCollector()
    private val decoder = DslDecoder(DecodeOptions(platform = Platform.ANDROID, errorCollector = decodeErrors))
    val propertyResolver = PropertyResolver(translations)
    val appStore = VariableStore().apply { logger = diagnostics }

    var app: App? = null
        private set
    var styles: StyleRegistry = StyleRegistry.EMPTY
        private set
    private var templates: TemplateResolver? = null
    private val screens = HashMap<String, Component>()
    private val loadLock = Mutex()

    /** Outstanding async work (screen loads, image requests): the preview host waits for zero. */
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    fun beginWork() = _pending.update { it + 1 }
    fun endWork() = _pending.update { (it - 1).coerceAtLeast(0) }

    val warnedNames = HashSet<String>()

    val solveCache = dev.app8.engine.render.SolveCache()

    val imageLoader: coil3.ImageLoader by lazy { buildImageLoader(this) }

    /** Loads app, styles, translations and templates once (iOS `ensureInfrastructureReady`). */
    suspend fun ensureReady(): App = loadLock.withLock {
        app?.let { return it }
        val appBytes = withContext(Dispatchers.IO) { dataSource.getApp() }
        val model = try {
            decoder.decodeApp(utf8(appBytes, "app.json"))
        } catch (e: App8Error) {
            throw e
        } catch (e: Exception) {
            throw App8Error.AppDecodeFailed(e)
        }
        model.variables?.let { defineAppVariables(it) }

        val styleDocs = withContext(Dispatchers.IO) { dataSource.getStyles() }
        val all = LinkedHashMap<String, StyleAny>()
        for (doc in styleDocs) {
            val list = runCatching { decoder.decodeStyles(doc.decodeToString()) }
                .onFailure { diagnostics.warn("styles.decode", it.message ?: "stylesheet failed to decode") }
                .getOrNull() ?: continue
            for (s in list) all[s.id] = s
        }
        styles = StyleRegistry(all)

        runCatching { translations.load(withContext(Dispatchers.IO) { dataSource.getTranslations() }) }
            .onFailure { diagnostics.warn("i18n", "getTranslations failed: ${it.message}") }

        val templateDocs = withContext(Dispatchers.IO) { dataSource.getComponents() }
        val decoded = templateDocs.mapNotNull { doc ->
            runCatching { decoder.decodeTemplate(doc.decodeToString()) }
                .onFailure { diagnostics.warn("template.decode", it.message ?: "template failed to decode") }
                .getOrNull()
        }
        templates = TemplateResolver(decoded)
        reportDecodeErrors()
        app = model
        model
    }

    /** Decoded screen (templates expanded), cached. */
    suspend fun loadScreen(screenId: String): Component {
        ensureReady()
        screens[screenId]?.let { return it }
        val bytes = try {
            withContext(Dispatchers.IO) { dataSource.getScreen(screenId) }
        } catch (e: Exception) {
            throw App8Error.ScreenNotFound(screenId)
        }
        val component = try {
            decoder.decodeScreen(utf8(bytes, "$screenId.json"), templates)
        } catch (e: Exception) {
            throw App8Error.ScreenDecodeFailed(screenId, e)
        }
        reportDecodeErrors()
        screens[screenId] = component
        return component
    }

    private val templateComponents = HashMap<String, Component?>()

    /**
     * A `components/` template as a component (iOS `App8Service.resolveTemplate(named:)`):
     * collection cell templates referenced by name. Cached; null (with a warning) when unknown.
     */
    fun resolveTemplate(name: String): Component? = templateComponents.getOrPut(name) {
        val resolver = templates
        val content = resolver?.resolvedTemplates?.get(name)
        val type = resolver?.templateType(name)
        if (resolver == null || content == null || type == null) {
            diagnostics.warn("template", "template '$name' not found")
            return@getOrPut null
        }
        val full = kotlinx.serialization.json.JsonObject(
            mapOf(
                "type" to kotlinx.serialization.json.JsonPrimitive(type.raw),
                "id" to kotlinx.serialization.json.JsonPrimitive(name),
                "content" to content,
            ),
        )
        runCatching { decoder.decodeComponent(dev.app8.engine.core.model.TemplatePreprocessor(resolver).process(full)) }
            .onFailure { diagnostics.warn("template.decode", "template '$name': ${it.message}") }
            .getOrNull()
    }

    fun templateNames(): Set<String> = templates?.resolvedTemplates?.keys.orEmpty()

    private var reportedErrors = 0

    private fun reportDecodeErrors() {
        val entries = decodeErrors.entries
        for (e in entries.drop(reportedErrors)) {
            diagnostics.warn("decode.dropped", "${e.typeName} at ${e.jsonPath}: ${e.error.message}")
        }
        reportedErrors = entries.size
    }

    private fun defineAppVariables(raw: Map<String, JsonElement>) {
        val defs = decodeVariableDefinitions(raw, diagnostics)
        appStore.defineVariables(defs)
    }

    private fun utf8(bytes: ByteArray, name: String): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw App8Error.InvalidUtf8(name)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decodeVariableDefinitions(raw: Map<String, JsonElement>, diagnostics: Diagnostics?): Map<String, VariableDefinition> {
            val out = LinkedHashMap<String, VariableDefinition>()
            for ((name, element) in raw) {
                runCatching { json.decodeFromJsonElement(VariableDefinition.serializer(), element) }
                    .onSuccess { out[name] = it }
                    .onFailure { diagnostics?.warn("variables.decode", "'$name': ${it.message}") }
            }
            return out
        }
    }
}
