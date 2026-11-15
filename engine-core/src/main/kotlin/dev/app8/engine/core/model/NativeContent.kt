package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ViewStyle
import dev.app8.engine.core.model.style.decodeViewStyle
import kotlinx.serialization.json.JsonElement

/**
 * `native` (DSL 0.6): a host-provided view. [module] names the host's registered component;
 * [props] are passed to it with `{{…}}` strings resolved against the component's scope.
 */
public data class NativeProperties(
    val module: String,
    val props: Map<String, JsonElement> = emptyMap(),
    /** `isHidden` (bool or expression), as on `view`. */
    val isHidden: String? = null,
)

/**
 * Content of a `native` component. [events] maps each event name the module emits to the
 * actions it runs (`content.actions`; the payload is in scope as `event`). [fallback] renders
 * when the host has not registered [NativeProperties.module]; it is the node's only child.
 */
public data class NativeContent(
    override val properties: NativeProperties,
    override val style: ViewStyle? = null,
    override val layout: Layout? = null,
    val events: Map<String, List<Action>> = emptyMap(),
    val fallback: Component? = null,
    override val accessibility: Accessibility? = null,
    /** `content.analytics` keyed by module event name: fired when the module emits the event. */
    val eventAnalytics: Map<String, AnalyticsBinding> = emptyMap(),
) : EntityContent {
    /**
     * Module events are not component triggers: they run through [events] and fire
     * [eventAnalytics].
     */
    override val actions: Map<ActionTrigger, List<Action>>? get() = null
    override val analytics: Map<ActionTrigger, AnalyticsBinding>? get() = null
    override val children: List<Component> get() = listOfNotNull(fallback)
}

internal fun decodeNativeProperties(c: Cursor): NativeProperties {
    c.obj()
    val module = c.reqString("module")
    if (module.isBlank()) c.fail("native `module` is empty")
    return NativeProperties(module = module, props = c.opt("props", ::decodeAnyMap).orEmpty(), isHidden = c.optString("isHidden"))
}

internal fun decodeNativeContent(c: Cursor): NativeContent {
    c.obj()
    return NativeContent(
        properties = c.req("properties", ::decodeNativeProperties),
        style = c.opt("style", ::decodeViewStyle),
        layout = c.opt("layout", ::decodeLayout),
        events = c.opt("actions") { m -> m.map(::decodeActionList) }.orEmpty(),
        fallback = c.optComponent("fallback"),
        accessibility = c.opt("accessibility", ::decodeAccessibility),
        eventAnalytics = c.opt("analytics") { m -> m.map(::decodeAnalyticsBinding) }.orEmpty(),
    )
}
