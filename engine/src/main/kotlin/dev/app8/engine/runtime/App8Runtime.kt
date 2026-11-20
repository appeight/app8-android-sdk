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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    /**
     * Where action lists run; tests pass an immediate dispatcher. Null: the main thread
     * ([mainDispatcherOrInline]), looked up on the first action.
     */
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /** Data-source and request calls; tests pass an immediate dispatcher. */
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    val diagnostics = Diagnostics(config.logLevel)
    val translations = TranslationStore().apply { setActive(config.locale) }
    val eventBus = App8EventBus()
    val analyticsBus = App8AnalyticsBus()
    var analyticsConfig: App8AnalyticsConfig = config.analytics
    val mapProvider: dev.app8.engine.maps.App8MapProvider? = config.mapProvider
    val dynamicColor: Boolean = config.dynamicColor
    val nativeComponents: Map<String, dev.app8.engine.nativecomponent.App8NativeComponent> = config.nativeComponents

    /**
     * The app's brand accent for system chrome outside any tab bar (sheets, dialogs): the
     * tab bar `tintColor`, published by the tab host when it shows. Null: neutral.
     */
    var chromeAccent: androidx.compose.ui.graphics.Color? by androidx.compose.runtime.mutableStateOf(null)

    private val decodeErrors = DecodeErrorCollector()
    private val decoder = DslDecoder(DecodeOptions(platform = Platform.ANDROID, errorCollector = decodeErrors))
    val propertyResolver = PropertyResolver(translations)
    val appStore = VariableStore().apply { logger = diagnostics }

    /**
     * Where action lists run (main thread). Outlives screens, so a `request` answered after its
     * screen closed still runs its handlers. Lives as long as the instance. Created on the first
     * action, so building an instance never touches `Dispatchers.Main` (a host JVM test that
     * calls `Dispatchers.setMain` before running actions gets its dispatcher).
     */
    val actionScope: kotlinx.coroutines.CoroutineScope by lazy {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + (mainDispatcher ?: mainDispatcherOrInline()))
    }

    /** The `request` action's HTTP client. */
    var http: HttpTransport = OkHttpTransport()

    /**
     * Bumped on screen appear and on every `onEvent` timer tick. Expressions that read the clock
     * (`now()`, `today()`, …) read it too, so they re-evaluate then (there is no continuous clock).
     */
    private val clockEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    fun tickClock() {
        clockEpoch.intValue++
    }

    init {
        propertyResolver.clockObserver = { clockEpoch.intValue }
    }

    /**
     * Sends a DSL `request` through [App8DataSource.prepareRequest] and [http]: the `response`
     * object, status 0 when the host rejects the request or the network fails.
     */
    suspend fun send(request: dev.app8.engine.App8HttpRequest): Map<String, Any?> {
        beginWork()
        return try {
            val prepared = withContext(ioDispatcher) { dataSource.prepareRequest(request) }
            http.send(prepared).toValue()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.warn("action.request", "${request.method} ${request.url}: ${e.message ?: e.javaClass.simpleName}")
            HttpResponse.failure()
        } finally {
            endWork()
        }
    }

    /** A variable whose value comes from a datasource (`source`), for `reloadDatasource`. */
    private class DatasourceBinding(
        val screenId: String,
        val store: java.lang.ref.WeakReference<dev.app8.engine.core.vars.VariableStoreProtocol>,
        val name: String,
        val definition: VariableDefinition,
    )

    private val datasourceBindings = ArrayList<DatasourceBinding>()

    /** Remembers the `source` variables of [store] (a screen's or the app's). Main thread. */
    fun registerDatasources(screenId: String, store: dev.app8.engine.core.vars.VariableStoreProtocol, definitions: Map<String, VariableDefinition>) {
        for ((name, def) in definitions) {
            if (def.source != null) datasourceBindings += DatasourceBinding(screenId, java.lang.ref.WeakReference(store), name, def)
        }
    }

    fun unregisterDatasources(store: dev.app8.engine.core.vars.VariableStoreProtocol) {
        datasourceBindings.removeAll { it.store.get().let { s -> s == null || s === store } }
    }

    /**
     * `reloadDatasource`: fetches [source] once through the data source (for [screenId], the
     * screen running the action) and writes it into every variable bound to it, in the app
     * scope and in every live screen. False (with `datasource.reload.failed`) when the fetch fails.
     */
    suspend fun reloadDatasource(source: String, screenId: String): Boolean {
        datasourceBindings.removeAll { it.store.get() == null }
        beginWork()
        try {
            val bytes = try {
                withContext(ioDispatcher) { dataSource.reloadDatasource(screenId, source) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.warn("datasource.reload.failed", "'$source': ${e.message}")
                return false
            }
            for (binding in datasourceBindings.filter { it.definition.source == source }) {
                val store = binding.store.get() ?: continue
                runCatching { dev.app8.engine.core.vars.DatasourceResolver.decodeValue(source, binding.definition, bytes) }
                    .onSuccess { store.setExternalValue(binding.name, it) }
                    .onFailure { diagnostics.warn("datasource.reload.failed", "'${binding.name}' ($source): ${it.message}") }
            }
            return true
        } finally {
            endWork()
        }
    }

    /** Keyed elements' frames, for the native zoom transition (`zoom` + `sourceKey`). */
    val zoomSources = dev.app8.engine.navigation.transitions.ZoomSources()

    var app: App? = null
        private set
    /** Snapshot state: a streamed style update (`streamStyles`) recomposes what reads it. */
    var styles: StyleRegistry by androidx.compose.runtime.mutableStateOf(StyleRegistry.EMPTY)
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

    /**
     * `layout.conflict` (UIKit's "Unable to simultaneously satisfy constraints"): a required
     * constraint that contradicts the others is broken, and the views it relates are named.
     */
    val solveCache = dev.app8.engine.render.SolveCache { conflicts ->
        for (c in conflicts) {
            diagnostics.warn(
                "layout.conflict",
                "Unable to simultaneously satisfy constraints between [${c.views.joinToString(", ")}]; breaking `${c.constraint}`",
            )
        }
    }

    val imageLoader: coil3.ImageLoader by lazy { buildImageLoader(this) }

    /**
     * Where persisted variables and photos of this app live: the host's
     * [App8DataSource.persistenceId], else `app.id`, `bundleId`, title, package (blank values skipped).
     */
    var persistenceId: String = "default"
        private set

    /** Loads app, styles, translations and templates once (iOS `ensureInfrastructureReady`). */
    suspend fun ensureReady(): App = loadLock.withLock {
        app?.let { return it }
        val appBytes = withContext(Dispatchers.IO) { dataSource.getApp() }
        val model = try {
            val element = decoder.parse(utf8(appBytes, "app.json"))
            DegradingDecode.decode(element, { e -> diagnostics.warn("value.ignored", "'${e.path}' in app.json: ${e.reason}; using the default") }) { decoder.decodeApp(it) }
        } catch (e: App8Error) {
            throw e
        } catch (e: Exception) {
            throw App8Error.AppDecodeFailed(e)
        }
        persistenceId = listOf(dataSource.persistenceId, model.id, model.bundleId, model.title)
            .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: appContext.packageName
        var storedValues: Collection<String> = emptyList()
        if (model.variables?.values?.any { persistFlag(it) } == true) {
            // Stored values load before the first screen renders.
            val persistence = DataStoreVariablePersistence(appContext, persistenceId)
            persistence.preload()
            appStore.persistence = persistence
            storedValues = persistence.storedValues()
        }
        // Photos no persisted variable mentions any more (photos.md, Cleanup).
        val photoAppId = persistenceId
        actionScope.launch(Dispatchers.IO) { runCatching { PhotoStore.sweep(appContext, photoAppId, storedValues) } }
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
            decodeScreen(screenId, bytes)
        } catch (e: Exception) {
            throw App8Error.ScreenDecodeFailed(screenId, e)
        }
        reportDecodeErrors()
        screens[screenId] = component
        return component
    }

    /**
     * A streamed screen document (`streamScreen`): decoded like [loadScreen] and cached, so a
     * later push of the same screen shows the latest version.
     */
    fun decodeScreenUpdate(screenId: String, bytes: ByteArray): Component {
        val component = decodeScreen(screenId, bytes)
        reportDecodeErrors()
        screens[screenId] = component
        return component
    }

    /** A screen document, templates expanded; a bad value on the screen's own fields is dropped (see [DegradingDecode]). */
    private fun decodeScreen(screenId: String, bytes: ByteArray): Component {
        val raw = decoder.parse(utf8(bytes, "$screenId.json"))
        val element = templates?.let { dev.app8.engine.core.model.TemplatePreprocessor(it).process(raw) } ?: raw
        return DegradingDecode.decode(element, { e -> diagnostics.warn("value.ignored", "'${e.path}' in screen '$screenId': ${e.reason}; using the default") }) {
            decoder.decodeComponent(it)
        }
    }

    /** A streamed stylesheet (`streamStyles`): its styles replace the ones with the same id. */
    fun applyStyleUpdate(bytes: ByteArray) {
        val list = runCatching { decoder.decodeStyles(bytes.decodeToString()) }
            .onFailure { diagnostics.warn("styles.decode", "style stream: ${it.message}") }
            .getOrNull() ?: return
        styles = styles.merging(list)
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
            diagnostics.warn("value.ignored", "'${e.jsonPath}' in ${e.typeName}: ${(e.error as? dev.app8.engine.core.model.DslDecodingException)?.reason ?: e.error.message}")
        }
        reportedErrors = entries.size
    }

    private fun persistFlag(element: JsonElement): Boolean =
        ((element as? kotlinx.serialization.json.JsonObject)?.get("persist") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"

    /**
     * App variables. A `source` variable loads through the data source in the background
     * (`screenId` ""): until it arrives it has its `initialValue`, or its stored value with
     * `persist`; the loaded value then replaces it.
     */
    private fun defineAppVariables(raw: Map<String, JsonElement>) {
        val defs = decodeVariableDefinitions(raw, diagnostics)
        appStore.defineVariables(defs) { name, e -> diagnostics.warn("variable", "app variable '$name': ${e.message}") }
        val sourced = defs.filterValues { it.source != null }
        registerDatasources(APP_SCOPE_ID, appStore, sourced)
        for ((name, def) in sourced) {
            actionScope.launch {
                loadAppSource(name, def)?.let { appStore.setExternalValue(name, it) }
            }
        }
    }

    private suspend fun loadAppSource(name: String, def: VariableDefinition): Any? {
        val source = def.source ?: return null
        return try {
            val bytes = withContext(ioDispatcher) { dataSource.getDatasource(APP_SCOPE_ID, source) }
            dev.app8.engine.core.vars.DatasourceResolver.decodeValue(source, def, bytes)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.warn("datasource.load.failed", "app variable '$name' ($source): ${e.message}")
            null
        }
    }

    private fun utf8(bytes: ByteArray, name: String): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        throw App8Error.InvalidUtf8(name)
    }

    companion object {
        /** The `screenId` passed to [App8DataSource.getDatasource] for app-scope variables. */
        const val APP_SCOPE_ID = ""

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

/**
 * `Dispatchers.Main.immediate`, or [Dispatchers.Unconfined] where there is no main dispatcher
 * (a host JVM without a main looper or `Dispatchers.setMain`): action lists then run on the
 * calling thread, as on the main thread of an app, instead of every action failing with
 * "Dispatchers.Main was accessed …". On a device the main dispatcher always exists.
 */
internal fun mainDispatcherOrInline(): kotlinx.coroutines.CoroutineDispatcher = try {
    // A missing main dispatcher throws on `immediate` or on first use; probe both here.
    Dispatchers.Main.immediate.also { it.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) }
} catch (e: IllegalStateException) {
    Dispatchers.Unconfined
}
