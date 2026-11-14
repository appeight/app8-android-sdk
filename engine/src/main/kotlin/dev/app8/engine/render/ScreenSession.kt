package dev.app8.engine.render

import androidx.compose.runtime.Stable
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.TabBarScreenContent
import dev.app8.engine.core.vars.DatasourceLoader
import dev.app8.engine.core.vars.DatasourceResolver
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.core.vars.DatasourceDefinition
import dev.app8.engine.runtime.App8Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One presentation of a screen: its decoded tree, the screen variable scope (child of the app
 * store) seeded with datasources and navigation params, and the [RenderNode] tree.
 *
 * @property screenId the id the host or a navigation action asked for (the analytics alias).
 */
@Stable
internal class ScreenSession private constructor(
    val runtime: App8Runtime,
    val screenId: String,
    val component: Component,
    val scope: Scope,
) {
    private val scopes = mutableListOf<Scope>()

    /** Text-input focus for this presentation (focus actions, return-key chaining). */
    val focus = dev.app8.engine.render.controls.FocusManager()

    /** Pre-order index of each node key: the focus order of text inputs. */
    val treeOrder: Map<String, Int> by lazy {
        val out = HashMap<String, Int>()
        fun walk(n: RenderNode) { out[n.key] = out.size; n.children.forEach(::walk) }
        walk(root)
        out
    }

    lateinit var root: RenderNode
        private set

    val tabBar: TabBarScreenContent? get() = (component as? ComponentEntity<*>)?.content as? TabBarScreenContent

    /** `streaming: true` on the screen: live updates through the data source streams. */
    val isStreaming: Boolean get() = (content as? dev.app8.engine.core.model.ComponentContent<*, *>)?.streaming == true

    /** The screen's own variable values (what a streamed update preserves). */
    fun localValues(): Map<String, Any?> =
        (scope.store as? ScopedVariableStore)?.getLocalVariables()?.mapValues { it.value.value }.orEmpty()

    val content: StatefulContent? get() = (component as? ComponentEntity<*>)?.content as? StatefulContent

    fun dispose() {
        scopes.forEach { it.dispose() }
        scope.dispose()
        (scope.store as? ScopedVariableStore)?.detach()
    }

    companion object {
        /** Loads and prepares [screenId] (iOS `renderScreen`: datasources first, then params). */
        suspend fun create(runtime: App8Runtime, appScope: Scope, screenId: String, params: Map<String, Any?>): ScreenSession =
            create(runtime, appScope, screenId, runtime.loadScreen(screenId), params, emptyMap())

        /**
         * A session for [component] (a streamed screen update, iOS `ScreenUpdater.update`):
         * variables are defined fresh, then [preserved] values of variables that still
         * exist are restored, so user state survives the structural update.
         */
        suspend fun create(
            runtime: App8Runtime,
            appScope: Scope,
            screenId: String,
            component: Component,
            params: Map<String, Any?>,
            preserved: Map<String, Any?>,
        ): ScreenSession {
            val store = ScopedVariableStore(runtime.appStore)
            val content = (component as? ComponentEntity<*>)?.content as? StatefulContent
            content?.variables?.let { raw ->
                var defs = App8Runtime.decodeVariableDefinitions(raw, runtime.diagnostics)
                val loader = DatasourceLoader { s, d -> withContext(Dispatchers.IO) { runtime.dataSource.getDatasource(s, d) } }
                if (DatasourceResolver.hasExternalDatasources(defs)) {
                    defs = try {
                        DatasourceResolver.resolveDatasources(screenId, defs, loader)
                    } catch (e: Exception) {
                        runtime.diagnostics.warn("datasource", "screen '$screenId': ${e.message}")
                        defs.mapValues { (_, d) -> if (d.source != null) VariableDefinition(d.type, null, d.computed) else d }
                    }
                }
                defs = applyPreviews(screenId, defs, params, loader, runtime)
                store.defineVariables(defs)
            }
            for ((name, value) in params) {
                val v = Values.normalize(value)
                if (store.getLocalVariables().containsKey(name)) {
                    runCatching { store.setValue(name, v) }
                        .onFailure { runtime.diagnostics.warn("params", "'$name' on '$screenId': ${it.message}") }
                    // An injected param is the variable's definition-time value: `resetVariables` restores it.
                    store.getLocalVariables()[name]?.captureInitialValue()
                } else {
                    store.defineVariable(name, VariableDefinition(VariableType.inferType(v), v))
                }
            }
            for ((name, value) in preserved) {
                if (value == null || !store.getLocalVariables().containsKey(name)) continue
                runCatching { store.setValue(name, value) }
                    .onFailure { runtime.diagnostics.warn("streaming", "can't restore '$name' on '$screenId': ${it.message}") }
            }
            val scope = Scope(store, appScope)
            val session = ScreenSession(runtime, screenId, component, scope)
            session.root = RenderNode.build(session, component, screenId, scope, null, session.scopes)
                ?: throw IllegalStateException("screen '$screenId' has no content")
            return session
        }

        /** `preview` hints: a screen opened without its input gets a sample row (standalone renders). */
        private suspend fun applyPreviews(
            screenId: String,
            defs: Map<String, VariableDefinition>,
            params: Map<String, Any?>,
            loader: DatasourceLoader,
            runtime: App8Runtime,
        ): Map<String, VariableDefinition> {
            if (defs.values.none { it.preview != null }) return defs
            val out = LinkedHashMap(defs)
            for ((name, def) in defs) {
                val preview = def.preview ?: continue
                if (params.containsKey(name) || !Values.isNullish(def.rawInitialValue)) continue
                val value: Any? = preview.value?.value ?: preview.source?.let { src ->
                    runCatching { DatasourceDefinition.decode(loader.getDatasource(screenId, src)).rawData }
                        .onFailure { runtime.diagnostics.warn("preview", "'$name': ${it.message}") }
                        .getOrNull()
                        ?.let { data -> (data as? List<*>)?.getOrNull(preview.index ?: 0) ?: data }
                }
                if (value != null) out[name] = VariableDefinition(def.type, value, def.computed, null, def.streaming, def.schema, def.preview, def.persist)
            }
            return out
        }
    }
}
