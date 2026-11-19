package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.decodeStyleAny

/** Edge insets for padding / margins. */
public data class EdgeInsets(
    val top: Double? = null,
    val left: Double? = null,
    val bottom: Double? = null,
    val right: Double? = null,
) {
    public companion object {
        public fun uniform(value: Double): EdgeInsets = EdgeInsets(value, value, value, value)
    }
}

internal fun decodeEdgeInsets(c: Cursor): EdgeInsets {
    c.obj()
    return EdgeInsets(c.optDouble("top"), c.optDouble("left"), c.optDouble("bottom"), c.optDouble("right"))
}

/** User-definable state-change triggers (`content.triggers` keys). */
@Suppress("EnumEntryName")
public enum class Trigger { touchDown, touchUp, focus, blur, hover, hoverEnd }

/** A component state override. `null` fields keep the base value. */
public data class ComponentState<P, S>(
    val properties: P? = null,
    val style: S? = null,
    val layout: Layout? = null,
    val childStates: Map<String, String>? = null,
    val animation: Animation? = null,
    /**
     * The state's `properties.isHidden` (bool or expression) whatever the component type, so a
     * state can hide or show any component. Read only when the state has `properties`.
     */
    val isHidden: String? = null,
    /** The `animation` of a wrapped `properties.isHidden` on the state. */
    val isHiddenAnimation: Animation? = null,
)

internal inline fun <P, S> decodeState(c: Cursor, decodeProps: (Cursor) -> P, decodeStyle: (Cursor) -> S): ComponentState<P, S> {
    c.obj()
    return ComponentState(
        properties = c.opt("properties", decodeProps),
        style = c.opt("style", decodeStyle),
        layout = c.opt("layout", ::decodeLayout),
        childStates = c.opt("childStates") { m -> m.map { it.string() } },
        animation = c.opt("animation", ::decodeAnimation),
        isHidden = rawPropertiesIsHidden(c.element),
        isHiddenAnimation = rawPropertiesHiddenAnimation(c),
    )
}

internal fun Cursor.decodeTriggersField(): Map<Trigger, String>? =
    opt("triggers") { m -> m.map { it.string() } }?.keyedByEnum<Trigger, String>()

/** A tab of a `tabBarScreen`. */
public data class Tab(
    val id: String,
    val label: String,
    /** SF Symbol name (mapped to a Material Symbol on Android). */
    val icon: String,
    val screen: String,
    /** Badge, usually an expression (`"{{length(cart)}}"`). Empty, `0` or null hides it. */
    val badge: String? = null,
    /** This tab's badge background; overrides the bar's `badgeColor`. */
    val badgeColor: String? = null,
)

internal fun decodeTab(c: Cursor): Tab {
    c.obj()
    return Tab(c.reqString("id"), c.reqString("label"), c.reqString("icon"), c.reqString("screen"), c.optString("badge"), c.optString("badgeColor"))
}

/** Navigation bar of a screen. */
public data class NavigationBar(
    val title: String? = null,
    val titleView: Component? = null,
    val titleAlignment: String? = null,
    val backLabel: String? = null,
    val leftAction: BarAction? = null,
    val rightAction: BarAction? = null,
    val rightActions: List<BarAction>? = null,
    val hidden: Boolean? = null,
    /** Hide the back button on a pushed screen (a search screen whose field takes its place). */
    val hidesBackButton: Boolean? = null,
    val titleColor: String? = null,
    val tintColor: String? = null,
    val topEdgeEffect: EdgeEffect? = null,
    val search: SearchBar? = null,
    /**
     * Android only (iOS ignores it): the Material 3 top app bar variant. `small` (default,
     * 64dp, title at the start), `centerAligned` (64dp, centered title), `medium` (112dp,
     * collapses to 64dp on scroll) or `large` (152dp, collapses to 64dp).
     */
    val topAppBar: TopAppBarStyle? = null,
) {
    @Suppress("EnumEntryName")
    public enum class EdgeEffect { automatic, soft, hard, hidden }

    @Suppress("EnumEntryName")
    public enum class TopAppBarStyle { small, centerAligned, medium, large }
}

/** System search field attached to the navigation bar. */
public data class SearchBar(
    val placeholder: String? = null,
    val bindVariable: String? = null,
    val placement: Placement? = null,
    val hidesWhenScrolling: Boolean? = null,
    val tintColor: String? = null,
    val fillsBar: Boolean? = null,
    val onSubmit: List<Action>? = null,
    /** `field` (default) or `button`: the same field drawn as a button that runs [onTap]. */
    val mode: Mode? = null,
    val onTap: List<Action>? = null,
    /** Focus the field (keyboard up) when the screen appears. */
    val autoFocus: Boolean? = null,
    /** Runs when the user taps the ✕ (after the field clears and unfocuses). */
    val onCancel: List<Action>? = null,
) {
    val isButton: Boolean get() = mode == Mode.button

    @Suppress("EnumEntryName")
    public enum class Mode { field, button }

    @Suppress("EnumEntryName")
    public enum class Placement { automatic, stacked, inline, integrated, integratedButton, integratedCentered }
}

/** Navigation bar button. */
public data class BarAction(
    /** Button text; an expression follows its variables. */
    val label: String? = null,
    /** SF Symbol / Material Symbol name; an expression follows its variables. */
    val icon: String? = null,
    /** The first action. */
    val action: Action,
    val style: String? = null,
    /** Tint: a hex color or a color expression. */
    val color: String? = null,
    /** Every action, in order: `action` is a single action or an array. */
    val actions: List<Action> = listOf(action),
)

internal fun decodeNavigationBar(c: Cursor): NavigationBar {
    c.obj()
    return NavigationBar(
        title = c.optString("title"),
        titleView = c.optComponent("titleView"),
        titleAlignment = c.optString("titleAlignment"),
        backLabel = c.optString("backLabel"),
        leftAction = c.opt("leftAction", ::decodeBarAction),
        rightAction = c.opt("rightAction", ::decodeBarAction),
        rightActions = c.opt("rightActions") { a -> a.list(::decodeBarAction) },
        hidden = c.optBool("hidden"),
        hidesBackButton = c.optBool("hidesBackButton"),
        titleColor = c.optString("titleColor"),
        tintColor = c.optString("tintColor"),
        topEdgeEffect = c.optSafeEnum("topEdgeEffect", NavigationBar.EdgeEffect.automatic),
        search = c.opt("search", ::decodeSearchBar),
        topAppBar = c.optSafeEnum("topAppBar", NavigationBar.TopAppBarStyle.small),
    )
}

private fun decodeBarAction(c: Cursor): BarAction {
    c.obj()
    // A single action or an array, like every other trigger.
    val actions = c.attempt { it.req("action") { a -> a.list(::decodeAction) } }?.takeIf { it.isNotEmpty() }
        ?: listOf(c.req("action", ::decodeAction))
    return BarAction(c.optString("label"), c.optString("icon"), actions.first(), c.optString("style"), c.optString("color"), actions)
}

internal fun decodeSearchBar(c: Cursor): SearchBar {
    c.obj()
    // A single action or an array; anything undecodable is dropped (never fails the bar).
    fun actions(key: String) = c.attempt { it.opt(key) { a -> a.list(::decodeAction) } }
        ?: c.attempt { it.opt(key, ::decodeAction) }?.let { listOf(it) }
    return SearchBar(
        placeholder = c.optString("placeholder"),
        bindVariable = c.optString("bindVariable"),
        placement = c.optSafeEnum("placement", SearchBar.Placement.automatic),
        hidesWhenScrolling = c.optBool("hidesWhenScrolling"),
        tintColor = c.optString("tintColor"),
        fillsBar = c.optBool("fillsBar"),
        onSubmit = actions("onSubmit"),
        mode = c.optSafeEnum("mode", SearchBar.Mode.field),
        onTap = actions("onTap"),
        autoFocus = c.optBool("autoFocus"),
        onCancel = actions("onCancel"),
    )
}

/**
 * `screen.content.bottomBar` (DSL 0.6.1, app8-dsl `docs/navigation.md#bottom-bar`): [component]
 * pinned at full width above the bottom safe area (above the tab bar when one is shown). The
 * bar's height is added to the screen's bottom safe area, so scroll content gets the matching
 * bottom inset and nothing rests under it. Not a property of `tabBarScreen`.
 *
 * @property component the bar's content (usually a `stackView` of buttons), pinned to [insets];
 *   its height is its fitting height.
 * @property insets space around [component]; a missing edge keeps its default (12 top, 16 left
 *   and right, 8 bottom, measured from the bottom safe area).
 * @property style the bar's background, see [Style].
 * @property edgeEffect iOS 26 scroll-edge effect under a `system` background; Android ignores it.
 * @property hidden bool or `{{expression}}`: hides the bar (slide down and fade, 0.33 s).
 */
public data class BottomBar(
    val component: Component,
    val insets: EdgeInsets? = null,
    val style: Style? = null,
    val edgeEffect: EdgeEffect? = null,
    val hidden: String? = null,
) {
    /**
     * @property background null for `"system"` (the default: on Android the Material 3 bottom
     *   app bar surface, transparent at rest and `surfaceContainer` once content scrolls under
     *   the bar); otherwise a `material` or `fill` style entity (inline or pointer) drawn behind
     *   the bar down to the screen's bottom edge (or the tab bar).
     */
    public data class Style(val background: dev.app8.engine.core.model.style.StyleAny? = null)

    @Suppress("EnumEntryName")
    public enum class EdgeEffect { soft, hard }

    public companion object {
        public const val DEFAULT_INSET_TOP: Double = 12.0
        public const val DEFAULT_INSET_SIDE: Double = 16.0
        public const val DEFAULT_INSET_BOTTOM: Double = 8.0
    }
}

internal fun decodeBottomBar(c: Cursor): BottomBar {
    c.obj()
    return BottomBar(
        component = c.req("component", ::decodeComponent),
        insets = c.opt("insets", ::decodeEdgeInsets),
        style = c.opt("style", ::decodeBottomBarStyle),
        edgeEffect = c.optSafeEnum("edgeEffect", BottomBar.EdgeEffect.soft),
        hidden = c.optScalar("hidden"),
    )
}

/** `background`: `"system"` (or any other string, leniently) is the system treatment; an object is a style entity. */
private fun decodeBottomBarStyle(c: Cursor): BottomBar.Style {
    c.obj()
    val raw = (c.element as kotlinx.serialization.json.JsonObject)["background"]
    val background = if (raw is kotlinx.serialization.json.JsonObject) c.req("background", ::decodeStyleAny) else null
    return BottomBar.Style(background)
}
