package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonElement

@Suppress("EnumEntryName")
public enum class ActionTrigger {
    tap, longPress,
    onTextChange,
    onItemTap, onRefresh, onLoadMore, onSelectionChange,
    onAnnotationTap, onRegionChange, onUserLocationUpdate,
    onScrollThreshold,
    onVideoReady, onVideoStart, onVideoPause, onVideoComplete, onVideoLoop, onVideoStall, onVideoError, onTimeMark,
}

/**
 * One DSL action (iOS `DSL.Model.Action`). Expression strings (`"{{…}}"`) stay raw.
 * Free-form values (`params`, `value`, `updates`, `payload`) are kept as JSON.
 */
public data class Action(
    val type: Type,
    val target: String? = null,
    val function: String? = null,
    val destination: String? = null,
    val nextScreen: String? = null,
    val isFinal: Boolean? = null,
    val isBack: Boolean? = null,
    val toRoot: Boolean? = null,
    val params: Map<String, JsonElement>? = null,
    val presentation: PresentationStyle? = null,
    val detents: List<SheetDetent>? = null,
    val grabber: Boolean? = null,
    val transition: ScreenTransition? = null,
    val stateName: String? = null,
    val animated: Boolean? = null,
    val variableName: String? = null,
    val value: JsonElement? = null,
    val by: Double? = null,
    val matchBy: String? = null,
    val field: String? = null,
    val updates: Map<String, JsonElement>? = null,
    val scope: VariableScope? = null,
    val tabIndex: Int? = null,
    val tabId: String? = null,
    val alertTitle: String? = null,
    val alertMessage: String? = null,
    val alertActions: List<AlertAction>? = null,
    val hapticStyle: HapticStyle? = null,
    val url: String? = null,
    val urlPresentation: URLPresentation? = null,
    val name: String? = null,
    val payload: Map<String, JsonElement>? = null,
) {
    @Suppress("EnumEntryName")
    public enum class Type {
        navigation, dismiss, executeFunction, complete, completeFlow, setState, selectTab,
        updateVariable, incrementVariable, toggleArrayValue, appendToArray, incrementArrayItem,
        updateMultipleVariables, resetVariables,
        focus, focusNext, focusPrevious, dismissKeyboard,
        showAlert, haptic, openURL, emit,
    }

    @Suppress("EnumEntryName")
    public enum class HapticStyle { light, medium, heavy, success, warning, error, selection }

    @Suppress("EnumEntryName")
    public enum class URLPresentation { external, sheet, fullScreen }

    @Suppress("EnumEntryName")
    public enum class PresentationStyle { push, sheet, fullScreen, formSheet, pageSheet, crossDissolve }

    @Suppress("EnumEntryName")
    public enum class VariableScope { component, screen, app, all }

    public data class AlertAction(val title: String, val style: AlertActionStyle? = null, val action: Action? = null)

    @Suppress("EnumEntryName")
    public enum class AlertActionStyle { default, cancel, destructive }

    /** Native sheet resting height: `"medium"`, `"large"`, a number (points) or `"NN%"`. Never fails to decode. */
    public sealed interface SheetDetent {
        public data object Medium : SheetDetent
        public data object Large : SheetDetent
        public data class Fixed(val points: Double) : SheetDetent
        public data class Fraction(val fraction: Double) : SheetDetent
    }
}

internal fun decodeAction(c: Cursor): Action {
    c.obj()
    return Action(
        type = c.req("type") { it.strictEnum<Action.Type>() },
        target = c.optString("target"),
        function = c.optString("function"),
        destination = c.optString("destination"),
        nextScreen = c.optString("nextScreen"),
        isFinal = c.optBool("isFinal"),
        isBack = c.optBool("isBack"),
        toRoot = c.optBool("toRoot"),
        params = c.opt("params", ::decodeAnyMap),
        presentation = c.optEnum<Action.PresentationStyle>("presentation"),
        detents = c.opt("detents") { a -> a.list(::decodeSheetDetent) },
        grabber = c.optBool("grabber"),
        transition = c.opt("transition", ::decodeScreenTransition),
        stateName = c.optString("stateName"),
        animated = c.optBool("animated"),
        variableName = c.optString("variableName"),
        value = c.opt("value") { it.element },
        by = c.optDouble("by"),
        matchBy = c.optString("matchBy"),
        field = c.optString("field"),
        updates = c.opt("updates", ::decodeAnyMap),
        scope = c.optEnum<Action.VariableScope>("scope"),
        tabIndex = c.optInt("tabIndex"),
        tabId = c.optString("tabId"),
        alertTitle = c.optString("alertTitle"),
        alertMessage = c.optString("alertMessage"),
        alertActions = c.opt("alertActions") { a -> a.list(::decodeAlertAction) },
        hapticStyle = c.optEnum<Action.HapticStyle>("hapticStyle"),
        url = c.optString("url"),
        urlPresentation = c.optEnum<Action.URLPresentation>("urlPresentation"),
        name = c.optString("name"),
        payload = c.opt("payload", ::decodeAnyMap),
    )
}

/** `[String: AnyCodableValue]`: any JSON values, object required. */
internal fun decodeAnyMap(c: Cursor): Map<String, JsonElement> = LinkedHashMap(c.obj())

private fun decodeAlertAction(c: Cursor): Action.AlertAction {
    c.obj()
    return Action.AlertAction(
        title = c.reqString("title"),
        style = c.optEnum<Action.AlertActionStyle>("style"),
        action = c.opt("action", ::decodeAction),
    )
}

internal fun decodeSheetDetent(c: Cursor): Action.SheetDetent {
    c.doubleOrNull()?.let { return Action.SheetDetent.Fixed(it) }
    val s = (c.stringOrNull() ?: "large").trim(' ')
    return when (s.lowercase()) {
        "medium" -> Action.SheetDetent.Medium
        "large" -> Action.SheetDetent.Large
        else -> {
            val pct = if (s.endsWith("%")) s.dropLast(1).toDoubleOrNull() else null
            when {
                pct != null -> Action.SheetDetent.Fraction(pct / 100)
                s.toDoubleOrNull() != null -> Action.SheetDetent.Fixed(s.toDouble())
                else -> Action.SheetDetent.Large
            }
        }
    }
}

/** A single action or an array of actions under one trigger key. Every action must decode. */
internal fun decodeActionList(c: Cursor): List<Action> {
    if (c.element is kotlinx.serialization.json.JsonArray) {
        c.attempt { a -> a.list(::decodeAction) }?.let { return it }
    }
    return listOf(decodeAction(c))
}

/** Author-declared analytics binding: `"event_name"` or `{ "name": …, "properties": {…} }`. */
public data class AnalyticsBinding(val name: String, val properties: Map<String, JsonElement>? = null)

internal fun decodeAnalyticsBinding(c: Cursor): AnalyticsBinding {
    c.stringOrNull()?.let { return AnalyticsBinding(it) }
    c.obj()
    return AnalyticsBinding(name = c.reqString("name"), properties = c.opt("properties", ::decodeAnyMap))
}

/** `content.actions`: `[String: ActionList]`, unknown triggers dropped, empty → `null`. */
internal fun Cursor.decodeActionsField(key: String = "actions"): Map<ActionTrigger, List<Action>>? =
    opt(key) { m -> m.map(::decodeActionList) }?.keyedByEnum<ActionTrigger, List<Action>>()

internal fun Cursor.decodeAnalyticsField(key: String = "analytics"): Map<ActionTrigger, AnalyticsBinding>? =
    opt(key) { m -> m.map(::decodeAnalyticsBinding) }?.keyedByEnum<ActionTrigger, AnalyticsBinding>()

/** Event trigger in a component's `onEvent` array. */
public data class EventTrigger(
    val event: EventType,
    val delay: Double? = null,
    val interval: Double? = null,
    val repeats: Boolean? = null,
    val action: Action,
) {
    @Suppress("EnumEntryName")
    public enum class EventType { timer, appear, disappear }
}

internal fun decodeEventTrigger(c: Cursor): EventTrigger {
    c.obj()
    return EventTrigger(
        event = c.req("event") { it.strictEnum<EventTrigger.EventType>() },
        delay = c.optDouble("delay"),
        interval = c.optDouble("interval"),
        repeats = c.optBool("repeats"),
        action = c.req("action", ::decodeAction),
    )
}

/** Universal interaction overrides (`content.interaction`). Each field is a literal or `{{expression}}` string. */
public data class Interaction(
    val enabled: String? = null,
    val clipsToBounds: String? = null,
    val zIndex: String? = null,
) {
    public val hasBindings: Boolean get() = enabled != null || clipsToBounds != null || zIndex != null
}

internal fun decodeInteraction(c: Cursor): Interaction {
    c.obj()
    return Interaction(c.optString("enabled"), c.optString("clipsToBounds"), c.optString("zIndex"))
}

/** Universal accessibility metadata (`content.accessibility`). */
public data class Accessibility(
    val label: String? = null,
    val hint: String? = null,
    val value: String? = null,
    val traits: List<Trait>? = null,
    val isElement: String? = null,
    val hidden: String? = null,
) {
    public val hasBindings: Boolean
        get() = label != null || hint != null || value != null || traits != null || isElement != null || hidden != null

    public enum class Trait(public val raw: String) {
        BUTTON("button"), LINK("link"), HEADER("header"), IMAGE("image"), SELECTED("selected"),
        NOT_ENABLED("disabled"), ADJUSTABLE("adjustable"), SEARCH("searchField"), STATIC_TEXT("staticText"),
        SUMMARY_ELEMENT("summaryElement"), UPDATES_FREQUENTLY("updatesFrequently"),
        STARTS_MEDIA_SESSION("startsMediaSession"), ALLOWS_DIRECT_INTERACTION("allowsDirectInteraction"),
    }
}

internal fun decodeAccessibility(c: Cursor): Accessibility {
    c.obj()
    return Accessibility(
        label = c.optString("label"),
        hint = c.optString("hint"),
        value = c.optString("value"),
        traits = c.opt("traits") { a -> a.list { it.strictEnum<Accessibility.Trait> { t -> t.raw } } },
        isElement = c.optString("isElement"),
        hidden = c.optString("hidden"),
    )
}

/** Continuous interaction → variable bindings (`content.gestures`). Values are bare variable names. */
public data class Gestures(val pan: Pan? = null) {
    public data class Pan(
        val translationX: String? = null,
        val translationY: String? = null,
        val velocityX: String? = null,
        val velocityY: String? = null,
        val locationX: String? = null,
        val locationY: String? = null,
    ) {
        public val hasBindings: Boolean
            get() = translationX != null || translationY != null || velocityX != null ||
                velocityY != null || locationX != null || locationY != null
    }
}

internal fun decodeGestures(c: Cursor): Gestures {
    c.obj()
    return Gestures(
        pan = c.opt("pan") { p ->
            p.obj()
            Gestures.Pan(
                p.optString("translationX"), p.optString("translationY"),
                p.optString("velocityX"), p.optString("velocityY"),
                p.optString("locationX"), p.optString("locationY"),
            )
        },
    )
}

/** A remote or bundled asset reference. */
public data class Asset(val id: String? = null, val name: String? = null, val url: String? = null)

internal fun decodeAsset(c: Cursor): Asset {
    c.obj()
    return Asset(c.optString("id"), c.optString("name"), c.optString("url"))
}

/**
 * Variable definitions are ported separately; here each entry is kept as raw JSON but validated
 * the way iOS `VariableDefinition` decoding would reject it (object with a known `type`), so a
 * component with a broken variable still fails like on iOS.
 * TODO: decode as `dev.app8.engine.core.vars.VariableDefinition` once that package lands.
 */
internal fun decodeVariables(c: Cursor): Map<String, JsonElement> = c.map { v ->
    v.obj()
    v.req("type") { t -> t.string().takeIf { it in VARIABLE_TYPES } ?: t.fail("unknown variable type") }
    v.element
}

private val VARIABLE_TYPES = setOf("boolean", "string", "number", "array", "object")
