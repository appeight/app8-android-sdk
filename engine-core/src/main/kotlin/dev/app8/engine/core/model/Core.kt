package dev.app8.engine.core.model

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
)

internal inline fun <P, S> decodeState(c: Cursor, decodeProps: (Cursor) -> P, decodeStyle: (Cursor) -> S): ComponentState<P, S> {
    c.obj()
    return ComponentState(
        properties = c.opt("properties", decodeProps),
        style = c.opt("style", decodeStyle),
        layout = c.opt("layout", ::decodeLayout),
        childStates = c.opt("childStates") { m -> m.map { it.string() } },
        animation = c.opt("animation", ::decodeAnimation),
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
)

internal fun decodeTab(c: Cursor): Tab {
    c.obj()
    return Tab(c.reqString("id"), c.reqString("label"), c.reqString("icon"), c.reqString("screen"), c.optString("badge"))
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
) {
    @Suppress("EnumEntryName")
    public enum class Placement { automatic, stacked, inline, integrated, integratedButton, integratedCentered }
}

/** Navigation bar button. */
public data class BarAction(
    val label: String? = null,
    val icon: String? = null,
    val action: Action,
    val style: String? = null,
    val color: String? = null,
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
        titleColor = c.optString("titleColor"),
        tintColor = c.optString("tintColor"),
        topEdgeEffect = c.optSafeEnum("topEdgeEffect", NavigationBar.EdgeEffect.automatic),
        search = c.opt("search", ::decodeSearchBar),
        topAppBar = c.optSafeEnum("topAppBar", NavigationBar.TopAppBarStyle.small),
    )
}

private fun decodeBarAction(c: Cursor): BarAction {
    c.obj()
    return BarAction(c.optString("label"), c.optString("icon"), c.req("action", ::decodeAction), c.optString("style"), c.optString("color"))
}

internal fun decodeSearchBar(c: Cursor): SearchBar {
    c.obj()
    // A single action or an array; anything undecodable is dropped (never fails the bar).
    val onSubmit = c.attempt { it.opt("onSubmit") { a -> a.list(::decodeAction) } }
        ?: c.attempt { it.opt("onSubmit", ::decodeAction) }?.let { listOf(it) }
    return SearchBar(
        placeholder = c.optString("placeholder"),
        bindVariable = c.optString("bindVariable"),
        placement = c.optSafeEnum("placement", SearchBar.Placement.automatic),
        hidesWhenScrolling = c.optBool("hidesWhenScrolling"),
        tintColor = c.optString("tintColor"),
        fillsBar = c.optBool("fillsBar"),
        onSubmit = onSubmit,
    )
}
