package dev.app8.engine.actions

import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.AnalyticsBinding
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.vars.AnyCodableValue
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.actions.VariableAction
import dev.app8.engine.core.vars.actions.VariableActionHandler
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.string
import dev.app8.engine.runtime.App8Runtime
import kotlinx.serialization.json.JsonElement

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

    /** Fires analytics for [trigger] on [node], then runs its actions in order. */
    fun dispatchTrigger(node: RenderNode, trigger: ActionTrigger, context: VariableContext = node.scope.untracked) {
        fireTriggerAnalytics(node, trigger, context)
        val actions = node.content.actions?.get(trigger).orEmpty()
        for (action in actions) execute(node, action, context)
    }

    fun execute(node: RenderNode, action: Action, context: VariableContext = node.scope.untracked) {
        when (action.type) {
            Action.Type.updateVariable, Action.Type.incrementVariable, Action.Type.toggleArrayValue,
            Action.Type.appendToArray, Action.Type.incrementArrayItem, Action.Type.updateMultipleVariables,
            Action.Type.resetVariables,
            -> runCatching { variableHandler.execute(action.toVariableAction(), node.scope.store, context) }
                .onFailure { runtime.diagnostics.warn("action.variable", "${action.type} in ${node.key}: ${it.message}") }

            Action.Type.setState -> action.stateName?.let { raw -> setState(node, node.string(raw, context) ?: raw) }
            Action.Type.navigation -> navigation(node, action, context)
            Action.Type.completeFlow -> action.destination?.let { navigator.completeFlow(it) }
            Action.Type.dismiss -> navigator.dismiss()
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
            Action.Type.executeFunction, Action.Type.complete -> Unit
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

    private fun navigation(node: RenderNode, action: Action, context: VariableContext) {
        var posted = false
        val next = action.nextScreen
        if (action.isBack == true) {
            navigator.pop(toRoot = action.toRoot == true)
            posted = true
        } else if (next != null) {
            val params = action.params.orEmpty().mapValues { (_, v) -> resolveValue(v, context) }
            val presentation = action.presentation
            val customModal = (action.transition as? ScreenTransition.Inline)?.mode == ScreenTransition.Mode.modal
            if ((presentation != null && presentation != Action.PresentationStyle.push) || customModal) {
                navigator.present(next, params, presentation ?: Action.PresentationStyle.fullScreen, action.detents, action.grabber)
            } else {
                navigator.push(next, params)
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

    private fun showAlert(node: RenderNode, action: Action, context: VariableContext) {
        val buttons = action.alertActions?.map { a ->
            AlertRequest.Button(a.title, a.style ?: Action.AlertActionStyle.default) { a.action?.let { execute(node, it, context) } }
        } ?: listOf(AlertRequest.Button("OK", Action.AlertActionStyle.default) {})
        host.alert(AlertRequest(action.alertTitle?.let { node.string(it, context) }, action.alertMessage?.let { node.string(it, context) }, buttons))
    }

    private fun fireTriggerAnalytics(node: RenderNode, trigger: ActionTrigger, context: VariableContext) {
        if (trigger == ActionTrigger.tap && runtime.analyticsConfig.autoComponentTaps) {
            analytics(node, App8AnalyticsEvent.Auto.COMPONENT_TAPPED, emptyMap())
        }
        val binding: AnalyticsBinding = node.content.analytics?.get(trigger) ?: return
        val props = resolvePayload(node, binding.properties, context)
        for (key in props.keys) {
            if (key in App8AnalyticsEvent.canonicalKeys) {
                warnOnce("collision:${binding.name}:$key", "analytics binding '${binding.name}' writes canonical key '$key'; the SDK value wins")
            }
        }
        analytics(node, normalizeAuthorAnalyticsName(binding.name, ::warnOnce), props)
    }

    private fun analytics(node: RenderNode, name: String, properties: Map<String, Any?>) {
        runtime.analyticsBus.dispatch(
            App8AnalyticsEvent(
                name = name,
                screenId = node.session.screenId,
                componentId = node.id,
                componentType = node.type.raw,
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
    matchBy = matchBy,
    field = field,
    updates = updates?.mapValues { (_, v) -> AnyCodableValue.fromJson(v) },
)
