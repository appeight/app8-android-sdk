package dev.app8.engine.actions

import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.AnalyticsBinding
import dev.app8.engine.core.vars.AnyCodableValue
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.actions.VariableAction
import dev.app8.engine.core.vars.actions.VariableActionHandler
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool
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

            Action.Type.setState -> action.stateName?.let { raw ->
                val name = node.string(raw, context) ?: raw
                setState(node, name)
            }

            Action.Type.navigation -> navigation(node, action, context)
            Action.Type.completeFlow -> action.destination?.let { navigator.completeFlow(it) }
            Action.Type.dismiss -> navigator.dismiss()
            Action.Type.selectTab -> navigator.selectTab(action.tabIndex, action.tabId)
            Action.Type.showAlert -> showAlert(node, action, context)
            Action.Type.haptic -> host.haptic(action.hapticStyle ?: Action.HapticStyle.medium)
            Action.Type.openURL -> action.url?.let { raw ->
                val url = node.string(raw, context) ?: raw
                if (runtime.analyticsConfig.autoUrlEvents) {
                    analytics(node, App8AnalyticsEvent.Auto.URL_OPENED, mapOf("url" to url))
                }
                host.openUrl(url, action.urlPresentation ?: Action.URLPresentation.external)
            }

            Action.Type.emit -> {
                val name = action.name
                if (name.isNullOrEmpty()) {
                    runtime.diagnostics.warn("action.emit", "emit without `name` dropped in ${node.key}")
                } else {
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
            }

            // Focus and keyboard actions belong to text inputs (not rendered yet).
            Action.Type.focus, Action.Type.focusNext, Action.Type.focusPrevious, Action.Type.dismissKeyboard -> Unit
            Action.Type.executeFunction, Action.Type.complete -> Unit
        }
    }

    /** iOS `StateManager.setState` + `childStates`. */
    fun setState(node: RenderNode, name: String) {
        node.stateName = name
        val state = (node.baseContent as? dev.app8.engine.core.model.ComponentContent<*, *>)?.states?.get(name) ?: return
        state.childStates?.forEach { (childId, childState) -> node.children.firstNotNullOfOrNull { it.find(childId) }?.let { setState(it, childState) } }
    }

    private fun navigation(node: RenderNode, action: Action, context: VariableContext) {
        var posted = false
        if (action.isBack == true) {
            navigator.pop(toRoot = action.toRoot == true)
            posted = true
        } else if (action.nextScreen != null) {
            val params = action.params.orEmpty().mapValues { (_, v) -> resolveValue(node, v, context) }
            val presentation = action.presentation
            val modal = action.transition?.let { it as? dev.app8.engine.core.model.ScreenTransition.Inline }?.mode ==
                dev.app8.engine.core.model.ScreenTransition.Mode.modal
            if ((presentation != null && presentation != Action.PresentationStyle.push) || modal) {
                navigator.present(action.nextScreen!!, params, presentation ?: Action.PresentationStyle.fullScreen, action.detents, action.grabber)
            } else {
                navigator.push(action.nextScreen!!, params)
            }
            posted = true
        }
        if (posted && runtime.analyticsConfig.autoNavigationEvents) {
            val props = LinkedHashMap<String, Any?>()
            props["from_screen_alias"] = node.session.screenId
            action.nextScreen?.let { props["to_screen_alias"] = it }
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

    // region analytics

    private fun fireTriggerAnalytics(node: RenderNode, trigger: ActionTrigger, context: VariableContext) {
        if (trigger == ActionTrigger.tap && runtime.analyticsConfig.autoComponentTaps) {
            analytics(node, App8AnalyticsEvent.Auto.COMPONENT_TAPPED, emptyMap())
        }
        val binding: AnalyticsBinding = node.content.analytics?.get(trigger) ?: return
        val props = resolvePayload(node, binding.properties, context)
        for (key in props.keys) {
            if (key in App8AnalyticsEvent.canonicalKeys) warnOnce("collision:${binding.name}:$key", "analytics binding '${binding.name}' writes canonical key '$key'; the SDK value wins")
        }
        analytics(node, normalizeAuthorName(binding.name), props)
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

    /** `stripeConnectClicked` → `app8.stripeConnectClicked`; idempotent for `app8.`-prefixed names. */
    fun normalizeAuthorName(raw: String): String = normalizeAuthorAnalyticsName(raw) { key, msg -> warnOnce(key, msg) }

    private fun warnOnce(key: String, message: String) {
        if (runtime.warnedNames.add(key)) runtime.diagnostics.warn("analytics", message)
    }

    // endregion

    private fun resolvePayload(node: RenderNode, raw: Map<String, JsonElement>?, context: VariableContext): Map<String, Any?> =
        raw.orEmpty().mapValues { (_, v) -> resolveValue(node, v, context) }

    private fun resolveValue(node: RenderNode, element: JsonElement, context: VariableContext): Any? {
        val v = Values.fromJson(element)
        if (v === Values.Null) return null
        if (v !is String) return v
        if (!runtime.propertyResolver.containsExpression(v)) return v
        return runCatching { runtime.propertyResolver.resolve(v, context) }.getOrNull()?.takeUnless { it === Values.Null }
    }

    @Suppress("unused")
    private fun truthy(node: RenderNode, raw: String) = node.bool(raw)
}

/** Pure part of the author-name rule (app8-dsl analytics-events.spec.md), shared with tests. */
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
