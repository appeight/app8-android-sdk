package dev.app8.engine.actions

import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.ActionCondition
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.AnalyticsBinding
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.NativeContent
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.vars.AnyCodableValue
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableStoreProtocol
import dev.app8.engine.core.vars.actions.VariableAction
import dev.app8.engine.core.vars.actions.VariableActionHandler
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.string
import dev.app8.engine.runtime.App8Runtime
import dev.app8.engine.runtime.PhotoProcessing
import dev.app8.engine.runtime.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one place DSL actions run (iOS `CBaseViewModel.dispatchTrigger` / `executeAction`).
 * Every trigger goes through [dispatchTrigger] so author analytics and the auto
 * `app8.component.tapped` event fire whatever the component.
 */
internal class ActionDispatcher(
    private val runtime: App8Runtime,
    private val navigator: Navigator,
    private val host: HostEffects,
) {
    private val variableHandler = VariableActionHandler().apply { logger = runtime.diagnostics }

    /**
     * Fires analytics for [trigger] on [node], then runs its actions in order. Collections pass
     * the tapped cell's [context] and [store] so `{{item}}` resolves and variable actions run
     * against the cell scope (iOS `handleSelection` with the cell store).
     */
    fun dispatchTrigger(
        node: RenderNode,
        trigger: ActionTrigger,
        context: VariableContext = node.scope.untracked,
        store: VariableStoreProtocol = node.scope.store,
    ) {
        fireTriggerAnalytics(node, trigger, context)
        run(node, node.content.actions?.get(trigger).orEmpty(), context, store)
    }

    /**
     * Runs [actions] in order, as one action list. Each action finishes before the next starts;
     * the list runs synchronously until something has to wait (`dismiss` followed by more
     * actions waits for the dismissal, `reloadDatasource` for the data). A `request` does not
     * hold the list up: its `onSuccess` / `onError` run when the response arrives.
     */
    fun run(
        node: RenderNode,
        actions: List<Action>,
        context: VariableContext = node.scope.untracked,
        store: VariableStoreProtocol = node.scope.store,
    ) {
        if (actions.isEmpty()) return
        runtime.actionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runList(node, actions, context, store, Exec(navigator))
        }
    }

    /**
     * The navigator an action list talks to. After a `dismiss` with more actions behind it,
     * [pending] resolves to the presenting screen's navigator once the modal is gone; the
     * next `navigation` waits for it, other actions run right away.
     */
    private class Exec(var navigator: Navigator) {
        var pending: kotlinx.coroutines.Deferred<Navigator>? = null

        suspend fun forNavigation(): Navigator {
            pending?.let { navigator = it.await(); pending = null }
            return navigator
        }
    }

    /**
     * `onTextChange` for a text input (`textField` / `textView`): actions see the new text as
     * `$value`; analytics fire through [dispatchTrigger] (iOS `CBaseViewModel.dispatchTextChange`).
     */
    fun dispatchTextChange(node: RenderNode, text: String) {
        dispatchTrigger(node, ActionTrigger.onTextChange, node.scope.untracked.overlaying("\$value", text))
    }

    /**
     * A `tableView` row tap (iOS `didSelectRowAt` + `fireRowTapAnalytics`): the auto
     * `app8.component.tapped` and the row's own binding, anchored on the row id with
     * component type `tableViewRow`, then the row's `tap` actions in the table's scope.
     */
    fun dispatchRowTap(node: RenderNode, rowId: String, actions: List<Action>, binding: AnalyticsBinding?) {
        val context = node.scope.untracked
        if (runtime.analyticsConfig.autoComponentTaps) {
            analytics(node, App8AnalyticsEvent.Auto.COMPONENT_TAPPED, emptyMap(), rowId, "tableViewRow")
        }
        if (binding != null) {
            val props = resolvePayload(node, binding.properties, context)
            for (key in props.keys) {
                if (key in App8AnalyticsEvent.canonicalKeys) {
                    warnOnce("collision:${binding.name}:$key", "analytics binding '${binding.name}' writes canonical key '$key'; the SDK value wins")
                }
            }
            analytics(node, normalizeAuthorAnalyticsName(binding.name, ::warnOnce), props, rowId, "tableViewRow")
        }
        run(node, actions, context)
    }

    /**
     * A `native` component's module event (contract: app8-dsl `docs/components/native.md`).
     * Forwards it to the event bus as `native.<module>.<event>`, fires the author's
     * `analytics[event]` binding, then runs `actions[event]` with [payload] in scope as `event`
     * (the way `onAnnotationTap` exposes `item`). [payload] is normalized to DSL values first.
     */
    fun dispatchNativeEvent(node: RenderNode, event: String, payload: Map<String, Any?>) {
        val content = node.content as? NativeContent ?: return
        if (event == NativeContent.EVENT_APPEAR || event == NativeContent.EVENT_DISAPPEAR) {
            // DSL 0.6.1: reserved for the engine's visibility events.
            runtime.diagnostics.warn("native.event.reserved", "'${content.properties.module}' emitted '$event', a name reserved for visibility; ignored")
            return
        }
        val value: Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            Values.fromJson(Values.toJson(payload)) as Map<String, Any?>
        } catch (e: IllegalArgumentException) {
            runtime.diagnostics.warn("native.payload", "'$event' payload from '${content.properties.module}' is not JSON-like (${e.message}); passed as is")
            payload
        }
        runtime.eventBus.dispatch(
            App8Event(
                name = "native.${content.properties.module}.$event",
                screenId = node.session.screenId,
                componentId = node.id,
                componentType = node.type.raw,
                locale = runtime.translations.activeLocaleTag,
                payload = value,
            ),
        )
        val context = node.scope.untracked.overlaying("event", value)
        content.eventAnalytics[event]?.let { binding -> fireBinding(node, binding, context) }
        run(node, content.events[event].orEmpty(), context)
    }

    /**
     * A native component came on screen or left it (DSL 0.6.1): runs its `actions.appear` /
     * `actions.disappear` and the matching `analytics` binding with an empty `event`. Not a
     * module event, so nothing is posted to the event bus.
     */
    fun dispatchNativeVisibility(node: RenderNode, visible: Boolean) {
        val content = node.content as? NativeContent ?: return
        val event = if (visible) NativeContent.EVENT_APPEAR else NativeContent.EVENT_DISAPPEAR
        val context = node.scope.untracked.overlaying("event", emptyMap<String, Any?>())
        content.eventAnalytics[event]?.let { binding -> fireBinding(node, binding, context) }
        run(node, content.events[event].orEmpty(), context)
    }

    /** Runs one action (a list of one). */
    fun execute(
        node: RenderNode,
        action: Action,
        context: VariableContext = node.scope.untracked,
        store: VariableStoreProtocol = node.scope.store,
    ) = run(node, listOf(action), context, store)

    private suspend fun runList(node: RenderNode, actions: List<Action>, context: VariableContext, store: VariableStoreProtocol, exec: Exec) {
        for ((i, action) in actions.withIndex()) {
            try {
                runOne(node, action, context, store, exec, last = i == actions.lastIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runtime.diagnostics.warn("action", "${action.type} in ${node.key}: ${e.message}")
            }
        }
    }

    private suspend fun runOne(node: RenderNode, action: Action, context: VariableContext, store: VariableStoreProtocol, exec: Exec, last: Boolean) {
        // iOS gives every component its own store; here a component without variables shares
        // its parent's, so a `component`-scope reset from it resets nothing (its own store is empty).
        if (action.type == Action.Type.resetVariables && action.scope == Action.VariableScope.component && !node.ownsVariableScope) return
        val navigator = exec.navigator
        when (action.type) {
            Action.Type.updateVariable, Action.Type.incrementVariable, Action.Type.toggleArrayValue,
            Action.Type.appendToArray, Action.Type.incrementArrayItem, Action.Type.updateMultipleVariables,
            Action.Type.insertArrayItem, Action.Type.upsertArrayItem, Action.Type.removeArrayItem,
            Action.Type.resetVariables,
            -> runCatching { variableHandler.execute(action.toVariableAction(), store, context) }
                .onFailure { runtime.diagnostics.warn("action.variable", "${action.type} in ${node.key}: ${it.message}") }

            Action.Type.forEach -> forEach(node, action, context, store, exec)
            Action.Type.with -> {
                var scoped = context
                for ((name, raw) in action.values.orEmpty()) scoped = scoped.overlaying(name, resolveDeep(raw, context))
                runList(node, action.actions.orEmpty(), scoped, store, exec)
            }
            // The condition once, then exactly one branch in the current scope.
            Action.Type.`if` -> {
                val holds = ActionCondition.holds(action.condition?.let { resolveDeep(it, context) })
                runList(node, (if (holds) action.thenActions else action.elseActions).orEmpty(), context, store, exec)
            }
            Action.Type.request -> request(node, action, context, store, exec)
            Action.Type.reloadDatasource -> reloadDatasource(node, action, context, store, exec)

            Action.Type.setState -> action.stateName?.let { raw -> setState(node, node.string(raw, context) ?: raw) }
            Action.Type.navigation -> navigation(node, action, context, exec.forNavigation())
            Action.Type.completeFlow -> action.destination?.let { navigator.completeFlow(it) }
            // With more actions behind it, a dismiss lets the next `navigation` wait for it to finish.
            Action.Type.dismiss -> if (last) {
                navigator.dismiss()
            } else {
                exec.pending = runtime.actionScope.async(start = CoroutineStart.UNDISPATCHED) { navigator.dismissAndAwait() }
            }
            Action.Type.selectTab -> navigator.selectTab(action.tabIndex, action.tabId)
            Action.Type.showAlert -> showAlert(node, action, context)
            Action.Type.haptic -> host.haptic(action.hapticStyle ?: Action.HapticStyle.medium)
            Action.Type.openURL -> action.url?.let { raw ->
                val url = node.string(raw, context) ?: raw
                if (runtime.analyticsConfig.autoUrlEvents) analytics(node, App8AnalyticsEvent.Auto.URL_OPENED, mapOf("url" to url))
                host.openUrl(url, action.urlPresentation ?: Action.URLPresentation.external)
            }
            Action.Type.emit -> emit(node, action, context)
            Action.Type.focus -> action.target?.let { raw ->
                val focus = node.session.focus
                val target = node.string(raw, context) ?: raw
                val key = focus.resolve(target, node.key)
                if (key == null || !focus.focus(key)) runtime.diagnostics.warn("action.focus", "no text input '$target' to focus (${node.key})")
            }
            Action.Type.focusNext -> node.session.focus.focusNext()
            Action.Type.focusPrevious -> node.session.focus.focusPrevious()
            Action.Type.dismissKeyboard -> node.session.focus.dismissKeyboard()
            // The rest of the list has already run (like iOS); the answer picks onGranted / onDenied.
            Action.Type.requestNotificationPermission -> host.requestNotificationPermission { granted ->
                for (next in (if (granted) action.onGranted else action.onDenied).orEmpty()) execute(node, next, context, store)
            }
            Action.Type.capturePhoto -> capturePhoto(node, action, context, store, exec)
            Action.Type.deletePhoto -> action.ref?.let { raw ->
                PhotoStore.photoId(node.string(raw, context) ?: raw)?.let { id ->
                    runtime.actionScope.launch(kotlinx.coroutines.Dispatchers.IO) { PhotoStore.delete(runtime.appContext, runtime.persistenceId, id) }
                }
            }
            Action.Type.executeFunction, Action.Type.complete -> Unit
            // A newer DSL's action: reported once by name, runs nothing.
            Action.Type.unknown -> runtime.diagnostics.warn("action.unknown", "unknown action type '${action.unknownType}' in ${node.key}: skipped")
        }
    }

    /** `forEach`: the nested actions once per element of `items`, with the element (and index) in scope. */
    private suspend fun forEach(node: RenderNode, action: Action, context: VariableContext, store: VariableStoreProtocol, exec: Exec) {
        val raw = action.items ?: return
        val items = resolveDeep(raw, context) as? List<*> ?: return
        val itemName = action.itemName?.takeIf { it.isNotEmpty() } ?: "item"
        val indexName = (action.index as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        val nested = action.actions.orEmpty()
        for ((i, element) in items.withIndex()) {
            var scoped = context.overlaying(itemName, element ?: Values.Null)
            if (indexName != null) scoped = scoped.overlaying(indexName, i.toLong())
            runList(node, nested, scoped, store, exec)
        }
    }

    /**
     * `request`: sends the HTTP request through the data source's `prepareRequest`, then runs
     * `onSuccess` (2xx) or `onError` with `response` in scope. The action list goes on at once.
     */
    private fun request(node: RenderNode, action: Action, context: VariableContext, store: VariableStoreProtocol, exec: Exec) {
        val method = (action.method ?: "GET").uppercase(java.util.Locale.ROOT)
        val url = action.url?.let { node.string(it, context) }?.trim()
        val headers = LinkedHashMap<String, String>()
        for ((name, raw) in action.headers.orEmpty()) {
            dev.app8.engine.render.Coerce.string(resolveDeep(raw, context))?.let { headers[name] = it }
        }
        var body = action.body?.let { Values.toJson(resolveDeep(it, context)).toString() }
        if (body != null && method == "GET") {
            runtime.diagnostics.warn("action.request", "GET request in ${node.key} has a body; it is not sent")
            body = null
        }
        if (body != null && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) headers["Content-Type"] = "application/json"
        val navigator = exec.navigator
        runtime.actionScope.launch {
            val absolute = url != null && (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))
            val response: Map<String, Any?> = if (!absolute || method !in HTTP_METHODS) {
                runtime.diagnostics.warn("action.request", "request in ${node.key}: ${if (!absolute) "'${url.orEmpty()}' is not an absolute http(s) URL" else "unsupported method $method"}")
                dev.app8.engine.runtime.HttpResponse.failure()
            } else {
                runtime.send(dev.app8.engine.App8HttpRequest(method, url!!, headers, body))
            }
            val alive = node.session.isAlive
            action.resultVariable?.let { name ->
                // Once the screen is gone only an app variable is still written.
                val target = if (alive) store else runtime.appStore.takeIf { it.hasVariable(name) }
                target?.let { t ->
                    runCatching { t.setValue(name, response) }
                        .onFailure { runtime.diagnostics.warn("action.request", "resultVariable '$name' in ${node.key}: ${it.message}") }
                }
            }
            if (!alive) return@launch
            val status = (response["status"] as? Number)?.toInt() ?: 0
            val handlers = if (status in 200..299) action.onSuccess else action.onError
            runList(node, handlers.orEmpty(), context.overlaying("response", response), store, Exec(navigator))
        }
    }

    /**
     * `capturePhoto` (photos.md): the host's photo UI answers; a picked image is decoded upright,
     * cropped, downscaled and stored as JPEG off the main thread. Like `request`, the list goes on
     * at once and the callbacks run only while the screen is alive (an app `resultVariable` is
     * still written).
     */
    private fun capturePhoto(node: RenderNode, action: Action, context: VariableContext, store: VariableStoreProtocol, exec: Exec) {
        val source = when (action.source) { "camera" -> PhotoCaptureRequest.Source.camera; "library" -> PhotoCaptureRequest.Source.library; else -> PhotoCaptureRequest.Source.cameraOrLibrary }
        fun label(key: String, fallback: String): String =
            action.labels?.get(key)?.let { dev.app8.engine.render.Coerce.string(resolveDeep(it, context)) }?.takeIf { it.isNotEmpty() } ?: fallback
        val request = PhotoCaptureRequest(
            source = source,
            front = action.camera == "front",
            cameraLabel = label("camera", "Take Photo"),
            libraryLabel = label("library", "Choose from Library"),
            cancelLabel = label("cancel", "Cancel"),
        )
        val options = PhotoProcessing.Options.of(
            action.maxDimension?.let { resolveDeep(it, context) },
            action.quality?.let { resolveDeep(it, context) },
            action.aspectRatio?.let { resolveDeep(it, context) },
        )
        val navigator = exec.navigator
        host.capturePhoto(request) { pick ->
            runtime.actionScope.launch {
                val outcome: Pair<List<Action>?, Pair<String, Any?>?> = when (pick) {
                    is PhotoPick.Cancel -> action.onCancel to null
                    is PhotoPick.Error -> action.onError to ("error" to mapOf("code" to pick.code, "message" to pick.message))
                    is PhotoPick.Image -> {
                        val photo = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val encoded = PhotoProcessing.encode(runtime.appContext, pick.uri, options)
                                pick.cleanup()
                                val id = PhotoStore.save(runtime.appContext, runtime.persistenceId, encoded.jpeg)
                                val created = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
                                mapOf("ref" to PhotoStore.reference(id), "width" to encoded.width.toLong(), "height" to encoded.height.toLong(), "createdAt" to created, "source" to pick.source)
                            }
                        }.onFailure { pick.cleanup() }.getOrNull()
                        if (photo == null) {
                            action.onError to ("error" to mapOf("code" to "failed", "message" to "the photo could not be read or stored"))
                        } else {
                            val alive = node.session.isAlive
                            action.resultVariable?.let { name ->
                                val target = if (alive) store else runtime.appStore.takeIf { it.hasVariable(name) }
                                target?.let { t ->
                                    runCatching { t.setValue(name, photo["ref"]) }
                                        .onFailure { runtime.diagnostics.warn("action.capturePhoto", "resultVariable '$name' in ${node.key}: ${it.message}") }
                                }
                            }
                            action.onSuccess to ("photo" to photo)
                        }
                    }
                }
                if (!node.session.isAlive) return@launch
                val (handlers, scoped) = outcome
                val ctx = scoped?.let { context.overlaying(it.first, it.second) } ?: context
                runList(node, handlers.orEmpty(), ctx, store, Exec(navigator))
            }
        }
    }

    /**
     * `reloadDatasource`: refetches `source` once (for this screen) and writes it into every
     * bound variable, then runs `onSuccess` / `onError`. The action list goes on at once.
     */
    private fun reloadDatasource(node: RenderNode, action: Action, context: VariableContext, store: VariableStoreProtocol, exec: Exec) {
        val source = action.source?.let { node.string(it, context) }
        if (source.isNullOrEmpty()) {
            runtime.diagnostics.warn("action.reloadDatasource", "reloadDatasource without `source` in ${node.key}")
            return
        }
        val navigator = exec.navigator
        runtime.actionScope.launch {
            val ok = runtime.reloadDatasource(source, node.session.screenId)
            if (!node.session.isAlive) return@launch
            runList(node, (if (ok) action.onSuccess else action.onError).orEmpty(), context, store, Exec(navigator))
        }
    }

    /** iOS `StateManager.setState` + `childStates`. */
    fun setState(node: RenderNode, name: String) {
        node.stateName = name
        @Suppress("UNCHECKED_CAST")
        val state = (node.baseContent as? ComponentContent<Any, Any>)?.states?.get(name) ?: return
        state.childStates?.forEach { (childId, childState) ->
            node.children.firstNotNullOfOrNull { it.find(childId) }?.let { setState(it, childState) }
        }
    }

    private fun emit(node: RenderNode, action: Action, context: VariableContext) {
        val name = action.name
        if (name.isNullOrEmpty()) {
            runtime.diagnostics.warn("action.emit", "emit without `name` dropped in ${node.key}")
            return
        }
        runtime.eventBus.dispatch(
            App8Event(
                name = name,
                screenId = node.session.screenId,
                componentId = node.id,
                componentType = node.type.raw,
                locale = runtime.translations.activeLocaleTag,
                payload = resolvePayload(node, action.payload, context),
            ),
        )
    }

    private fun navigation(node: RenderNode, action: Action, context: VariableContext, navigator: Navigator) {
        var posted = false
        val next = action.nextScreen
        // Leaving the screen ends editing, as on iOS: a field focused on it (the search
        // screen's) would keep the keyboard up over the next screen, and the first back
        // press would only close it.
        node.session.focus.dismissKeyboard()
        if (action.isBack == true) {
            navigator.pop(toRoot = action.toRoot == true)
            posted = true
        } else if (next != null) {
            val params = action.params.orEmpty().mapValues { (_, v) -> resolveValue(v, context) }
            val presentation = action.presentation
            // A registry pointer resolves first: its `mode` decides push vs custom modal.
            val transition = action.transition
            val inline = transition?.inline { id -> runtime.app?.transitions?.firstNotNullOfOrNull { (it as? ScreenTransition.Inline)?.takeIf { t -> t.id == id } } }
            val customModal = inline != null && (inline.mode ?: inline.preset?.defaultMode) == ScreenTransition.Mode.modal &&
                inline.preset != ScreenTransition.Preset.system
            val zoom = inline?.takeIf { it.isNativeZoom }?.let { z -> zoomOrigin(node, z, context) }
            when {
                // A native zoom with a modal presentation (`fullScreen` …) is a custom modal.
                zoom != null && presentation != null && presentation != Action.PresentationStyle.push ->
                    navigator.present(next, params, Action.PresentationStyle.fullScreen, action.detents, action.grabber, inline, zoom)
                presentation != null && presentation != Action.PresentationStyle.push ->
                    navigator.present(next, params, presentation, action.detents, action.grabber)
                customModal -> navigator.present(next, params, Action.PresentationStyle.fullScreen, action.detents, action.grabber, inline, zoom)
                else -> navigator.push(next, params, inline, zoom)
            }
            posted = true
        }
        if (posted && runtime.analyticsConfig.autoNavigationEvents) {
            val props = LinkedHashMap<String, Any?>()
            props["from_screen_alias"] = node.session.screenId
            next?.let { props["to_screen_alias"] = it }
            action.presentation?.let { props["presentation"] = it.name }
            if (action.isBack == true) props["is_back"] = true
            analytics(node, App8AnalyticsEvent.Auto.NAVIGATION_PUSHED, props)
        }
    }

    /**
     * The native zoom's source: `sourceKey` now (the action's scope) and again in the presenting
     * screen's scope when the dismissal starts.
     */
    private fun zoomOrigin(node: RenderNode, t: ScreenTransition.Inline, context: VariableContext): dev.app8.engine.navigation.transitions.ZoomOrigin? {
        val raw = t.sourceKey ?: return null
        val key = node.string(raw, context)?.takeIf { it.isNotEmpty() } ?: return null
        val session = node.session
        return dev.app8.engine.navigation.transitions.ZoomOrigin(
            session = session,
            initialKey = key,
            rekey = { runCatching { session.root.string(raw, session.root.scope.untracked) }.getOrNull() },
            dismissGesture = t.dismissGesture ?: ScreenTransition.DismissGesture.any,
            initialAlignmentKey = t.alignmentKey?.let { node.string(it, context) }?.takeIf { it.isNotEmpty() },
            realign = { t.alignmentKey?.let { raw -> runCatching { session.root.string(raw, session.root.scope.untracked) }.getOrNull() } },
        )
    }

    private fun showAlert(node: RenderNode, action: Action, context: VariableContext) {
        val buttons = action.alertActions?.map { a ->
            AlertRequest.Button(a.title, a.style ?: Action.AlertActionStyle.default) { a.action?.let { run(node, listOf(it), context) } }
        } ?: listOf(AlertRequest.Button("OK", Action.AlertActionStyle.default) {})
        host.alert(AlertRequest(action.alertTitle?.let { node.string(it, context) }, action.alertMessage?.let { node.string(it, context) }, buttons))
    }

    private fun fireTriggerAnalytics(node: RenderNode, trigger: ActionTrigger, context: VariableContext) {
        if (trigger == ActionTrigger.tap && runtime.analyticsConfig.autoComponentTaps) {
            analytics(node, App8AnalyticsEvent.Auto.COMPONENT_TAPPED, emptyMap())
        }
        val binding: AnalyticsBinding = node.content.analytics?.get(trigger) ?: return
        fireBinding(node, binding, context)
    }

    /** An author-declared analytics binding, its properties resolved in [context]. */
    private fun fireBinding(node: RenderNode, binding: AnalyticsBinding, context: VariableContext) {
        val props = resolvePayload(node, binding.properties, context)
        for (key in props.keys) {
            if (key in App8AnalyticsEvent.canonicalKeys) {
                warnOnce("collision:${binding.name}:$key", "analytics binding '${binding.name}' writes canonical key '$key'; the SDK value wins")
            }
        }
        analytics(node, normalizeAuthorAnalyticsName(binding.name, ::warnOnce), props)
    }

    private fun analytics(
        node: RenderNode,
        name: String,
        properties: Map<String, Any?>,
        componentId: String = node.id,
        componentType: String = node.type.raw,
    ) {
        runtime.analyticsBus.dispatch(
            App8AnalyticsEvent(
                name = name,
                screenId = node.session.screenId,
                componentId = componentId,
                componentType = componentType,
                locale = runtime.translations.activeLocaleTag,
                properties = properties,
            ),
        )
    }

    private fun warnOnce(key: String, message: String) {
        if (runtime.warnedNames.add(key)) runtime.diagnostics.warn("analytics", message)
    }

    private fun resolvePayload(node: RenderNode, raw: Map<String, JsonElement>?, context: VariableContext): Map<String, Any?> =
        raw.orEmpty().mapValues { (_, v) -> resolveValue(v, context) }

    /** A JSON value with `{{…}}` resolved everywhere inside it (objects and arrays included). */
    private fun resolveDeep(element: JsonElement, context: VariableContext): Any? {
        val v = Values.fromJson(element)
        if (v === Values.Null) return null
        return runCatching { runtime.propertyResolver.resolveAny(v, context) }
            .onFailure { runtime.diagnostics.warn("expression", "${it.message}") }
            .getOrNull()?.takeUnless { it === Values.Null }
    }

    private companion object {
        val HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    }

    /** iOS `resolvePayload`: strings get `{{…}}` resolution, other JSON passes through. */
    private fun resolveValue(element: JsonElement, context: VariableContext): Any? {
        val v = Values.fromJson(element)
        if (v === Values.Null) return null
        if (v !is String || !runtime.propertyResolver.containsExpression(v)) return v
        return runCatching { runtime.propertyResolver.resolve(v, context) }.getOrNull()?.takeUnless { it === Values.Null }
    }
}

/** The author-name rule of app8-dsl `analytics-events.spec.md`: `foo` → `app8.foo`, idempotent. */
internal fun normalizeAuthorAnalyticsName(raw: String, warn: (String, String) -> Unit = { _, _ -> }): String {
    val prefixed = raw.startsWith("app8.")
    val stripped = if (prefixed) raw.removePrefix("app8.") else raw
    if (stripped.isEmpty()) {
        warn("author-name:$raw", "analytics binding name '$raw' is empty after stripping 'app8.'")
        return raw
    }
    val canonical = "app8.$stripped"
    if (prefixed) warn("author-name:app8-prefix:$raw", "analytics binding name '$raw' has the reserved 'app8.' prefix")
    if (canonical in App8AnalyticsEvent.reservedNames) warn("author-name:reserved:$canonical", "analytics binding '$raw' collides with a reserved event")
    return canonical
}

internal fun Action.toVariableAction(): VariableAction = VariableAction(
    type = type.name,
    variableName = variableName,
    value = value?.let { AnyCodableValue.fromJson(it) },
    by = by,
    byExpression = byExpression,
    matchBy = matchBy,
    field = field,
    updates = updates?.mapValues { (_, v) -> AnyCodableValue.fromJson(v) },
    scope = scope?.name,
    key = key,
    index = index?.let { AnyCodableValue.fromJson(it) },
    position = position,
)
