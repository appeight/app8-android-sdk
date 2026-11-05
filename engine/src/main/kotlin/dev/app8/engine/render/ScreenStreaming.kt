package dev.app8.engine.render

import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.vars.DatasourceDefinition
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.runtime.App8Runtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Live updates of a `streaming: true` screen (iOS `StreamingSession`), run for as long as the
 * screen is shown:
 *
 * - `streamDatasource` for every `streaming` variable with a `source`: each emission is
 *   injected into the screen store (`setExternalValue`). Only the nodes that read the
 *   variable recompose; nothing is rebuilt. Throttled to one update per 50 ms.
 * - `streamScreen`: a structural update. A new [ScreenSession] is built from the document
 *   with the old screen variables restored and [publish]ed; Compose diffs the new node tree
 *   against the old one by key, so unchanged parts keep their state. Datasource streams then
 *   restart against the new store.
 * - `streamStyles`: replaces the matching styles in the runtime registry; everything that
 *   reads a changed style recomposes and re-lays-out.
 */
internal suspend fun runScreenStreams(
    runtime: App8Runtime,
    appScope: Scope,
    screenId: String,
    params: Map<String, Any?>,
    initial: ScreenSession,
    publish: (ScreenSession) -> Unit,
) = coroutineScope {
    var current = initial
    var datasourceJobs: List<Job> = emptyList()
    val source = runtime.dataSource

    fun startDatasources(session: ScreenSession) {
        datasourceJobs.forEach { it.cancel() }
        val content = session.component.let { (it as? dev.app8.engine.core.model.ComponentEntity<*>)?.content as? ComponentContent<*, *> }
        val raw = content?.variables ?: return
        val defs = App8Runtime.decodeVariableDefinitions(raw, null)
        datasourceJobs = defs.filter { it.value.isStreaming }.mapNotNull { (name, def) ->
            val id = def.source
            if (id == null) {
                runtime.diagnostics.warn("streaming", "'$name' on '$screenId' has streaming=true but no source")
                return@mapNotNull null
            }
            val flow = runCatching { source.streamDatasource(screenId, id, null) }.getOrNull()
            if (flow == null) {
                runtime.diagnostics.warn("streaming", "no stream for '$name' ($id) on '$screenId'")
                return@mapNotNull null
            }
            launch {
                var last = 0L
                flow.flowOn(Dispatchers.IO).collect { bytes ->
                    val wait = 50 - (System.currentTimeMillis() - last)
                    if (wait > 0) delay(wait)
                    last = System.currentTimeMillis()
                    runCatching { DatasourceDefinition.decode(bytes).rawData }
                        .onSuccess { value -> (session.scope.store as? ScopedVariableStore)?.setExternalValue(name, value) }
                        .onFailure { runtime.diagnostics.warn("streaming", "'$name' update failed to decode: ${it.message}") }
                }
            }
        }
    }

    suspend fun rebuild(bytes: ByteArray?) {
        val component = try {
            if (bytes != null) runtime.decodeScreenUpdate(screenId, bytes) else current.component
        } catch (e: Exception) {
            runtime.diagnostics.warn("streaming", "screen update for '$screenId' failed to decode: ${e.message}")
            return
        }
        val next = try {
            ScreenSession.create(runtime, appScope, screenId, component, params, current.localValues())
        } catch (e: Exception) {
            runtime.diagnostics.warn("streaming", "screen update for '$screenId' failed: ${e.message}")
            return
        }
        current = next
        publish(next)
        startDatasources(next)
    }

    startDatasources(initial)
    collect(runCatching { source.streamScreen(screenId) }.getOrNull()) { rebuild(it) }
    collect(runCatching { source.streamStyles() }.getOrNull()) { bytes ->
        runtime.applyStyleUpdate(bytes)
    }
}

private fun kotlinx.coroutines.CoroutineScope.collect(flow: Flow<ByteArray>?, block: suspend (ByteArray) -> Unit) {
    if (flow == null) return
    launch { flow.flowOn(Dispatchers.IO).collect { block(it) } }
}
