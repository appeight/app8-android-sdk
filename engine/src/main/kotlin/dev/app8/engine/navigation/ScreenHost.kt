package dev.app8.engine.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.actions.HostEffects
import dev.app8.engine.core.layout.EdgeInsets
import dev.app8.engine.core.model.BarAction
import dev.app8.engine.core.model.NavigationBar
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.icons.MaterialSymbols
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.Scope
import dev.app8.engine.render.ScreenCanvas
import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.string
import dev.app8.engine.style.App8BackdropSource
import dev.app8.engine.style.IosSystemColors
import dev.app8.engine.style.rememberApp8Backdrop

internal val LocalAppScope = staticCompositionLocalOf<Scope> { error("No app scope") }
internal val LocalHostEffects = staticCompositionLocalOf<HostEffects> { error("No HostEffects") }

/** Material 3 small / center-aligned top app bar height (AppBarSmallTokens.ContainerHeight). */
internal const val TOP_BAR_HEIGHT = 64.0

/** Material 3 medium top app bar expanded height (AppBarMediumTokens.ContainerHeight). */
internal const val MEDIUM_TOP_BAR_HEIGHT = 112.0

/** Material 3 large top app bar expanded height (AppBarLargeTokens.ContainerHeight). */
internal const val LARGE_TOP_BAR_HEIGHT = 152.0

/**
 * Loads a screen (decode, datasources, params) and draws it: a `tabBarScreen` as a tab
 * host, any other screen with its top app bar.
 */
@Composable
internal fun ScreenHost(route: Route, navigator: StackNavigator, flow: FlowController, layer: Layer, stack: StackState, chrome: Chrome) {
    val env = LocalRenderEnv.current
    val runtime = env.runtime
    val appScope = LocalAppScope.current
    val result by produceState<Result<ScreenSession>?>(null, route) {
        runtime.beginWork()
        try {
            value = runCatching { ScreenSession.create(runtime, appScope, route.screenId, route.params) }
                .onFailure { runtime.diagnostics.warn("screen", "'${route.screenId}': ${it.message}") }
        } finally {
            runtime.endWork()
        }
        // `streaming: true`: live datasource / screen / style updates while shown.
        val initial = value?.getOrNull()
        if (initial != null && initial.isStreaming) {
            dev.app8.engine.render.runScreenStreams(runtime, appScope, route.screenId, route.params, initial) { value = Result.success(it) }
        }
    }
    val session = result?.getOrNull()
    DisposableEffect(session) { onDispose { session?.dispose() } }
    val background = IosSystemColors.of("systemBackground", env.appearance)
    when {
        result == null -> Box(Modifier.fillMaxSize().background(background))
        session == null -> Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
            BasicText(result?.exceptionOrNull()?.message ?: "Failed to load screen", style = TextStyle(color = Color.Red))
        }
        session.tabBar != null -> TabBarHost(session, flow, layer)
        else -> {
            val host = LocalHostEffects.current
            val dispatcher = remember(session, navigator) { ActionDispatcher(runtime, navigator, host) }
            val hides = chrome.inTabs && (session.content as? dev.app8.engine.core.model.ComponentContent<*, *>)?.hidesTabBar == true
            DisposableEffect(route.id, hides) {
                if (hides) stack.hidesTabBar[route.id] = true
                onDispose { stack.hidesTabBar.remove(route.id) }
            }
            // iOS hidesBottomBarWhenPushed: the screen's safe area ends at the window edge.
            val screenChrome = if (hides) chrome.copy(bottom = null) else chrome
            CompositionLocalProvider(LocalDispatcher provides dispatcher) {
                Screen(session, screenChrome, canGoBack = stack.entries.size > 1 && stack.entries.lastOrNull() == route, background)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(session: ScreenSession, chrome: Chrome, canGoBack: Boolean, background: Color) {
    val env = LocalRenderEnv.current
    val content = session.content
    val bar = content?.navigationBar?.takeIf { it.hidden != true }
    val window = WindowInsets.safeDrawing.asPaddingValues()
    val ld = LocalLayoutDirection.current
    val extra = (content as? dev.app8.engine.core.model.ComponentContent<*, *>)?.additionalSafeAreaInsets
    val barTitle = bar?.title?.let { session.root.string(it, session.scope.untracked) }
    val searchRow = bar != null && searchSlot(bar, barTitle) == SearchSlot.Row
    // Material 3 scroll behavior: small bars change color when content scrolls under them;
    // medium / large bars collapse to the small height first (exitUntilCollapsed).
    val style = bar?.topAppBar ?: NavigationBar.TopAppBarStyle.small
    val barState = androidx.compose.material3.rememberTopAppBarState()
    // `hidesWhenScrolling: true` on a search app bar (search in place of the title): it
    // scrolls away with the content and comes back on any upward scroll (M3 enterAlways,
    // Gmail / Photos / Play). Default false, as on iOS: pinned, with the on-scroll color.
    val hidesOnScroll = bar != null && style == NavigationBar.TopAppBarStyle.small && bar.search?.hidesWhenScrolling == true &&
        searchSlot(bar, barTitle) == SearchSlot.Title
    val behavior = when {
        style.collapses -> TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
        hidesOnScroll -> TopAppBarDefaults.enterAlwaysScrollBehavior(barState)
        else -> TopAppBarDefaults.pinnedScrollBehavior(barState)
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // A collapsing bar's current height feeds the safe area (like a Scaffold's content
    // padding), in whole dp so the layout re-solves at most once per dp of collapse.
    val barHeight = when {
        bar == null -> 0.0
        style.collapses -> kotlin.math.round(style.expandedHeight + barState.heightOffset / density).coerceAtLeast(TOP_BAR_HEIGHT)
        hidesOnScroll -> kotlin.math.round(TOP_BAR_HEIGHT + barState.heightOffset / density).coerceAtLeast(0.0)
        else -> style.expandedHeight
    }
    val top = (chrome.top ?: window.calculateTopPadding().value.toDouble()) +
        barHeight + (if (searchRow) SEARCH_ROW_HEIGHT else 0.0)
    val bottom = chrome.bottom ?: window.calculateBottomPadding().value.toDouble()
    val safe = EdgeInsets(
        top = top + (extra?.top ?: 0.0),
        left = window.calculateLeftPadding(ld).value.toDouble() + (extra?.left ?: 0.0),
        bottom = bottom + (extra?.bottom ?: 0.0),
        right = window.calculateRightPadding(ld).value.toDouble() + (extra?.right ?: 0.0),
    )
    // Content under the bar (`topEdgeEffect: hidden`, e.g. a hero image): the bar stays
    // transparent until the hero has scrolled out from under it (Play Store app details).
    val overContent = bar?.topEdgeEffect == NavigationBar.EdgeEffect.hidden
    var heroScroll by remember { androidx.compose.runtime.mutableDoubleStateOf(0.0) }
    val solid = if (overContent) -barState.contentOffset / density >= heroScroll.coerceAtLeast(1.0)
    else barState.overlappedFraction > 0.01f
    val flow = LocalFlow.current
    LaunchedEffect(solid) { flow.chromeEpoch++ }
    ScreenAnalytics(session, bar)
    val backdrop = rememberApp8Backdrop()
    // A navigation bar or search tint re-themes this screen's chrome (pickers, dialogs).
    val accent = (bar?.tintColor ?: bar?.search?.tintColor)
        ?.let { session.root.string(it, session.scope.untracked) }?.takeIf { it.isNotEmpty() }
        ?.let { Color(ColorHex.parseOrFallback(it).argb) }
    dev.app8.engine.style.App8ChromeTheme(accent, env.appearance.isDark) {
        var m = Modifier.fillMaxSize().background(background)
        if (bar != null) m = m.nestedScroll(behavior.nestedScrollConnection)
        Box(m) {
            App8BackdropSource(backdrop) { mm -> ScreenCanvas(session, safe, mm, onSolved = if (overContent) ({ heroScroll = heroScrollDistance(it, safe.top) }) else null) }
            if (bar != null) TopBar(session, bar, style, behavior, canGoBack, chrome.top == null, screenBackground(session, background), overContent, solid)
        }
    }
}

/**
 * How far content must scroll before the top bar turns opaque over a hero: the bottom of
 * the tallest full-width view that starts at the top edge, minus the bar's bottom (0 when
 * there is none: opaque as soon as anything scrolls under).
 */
internal fun heroScrollDistance(solved: dev.app8.engine.render.Solved, barBottom: Double): Double {
    val frames = solved.absolute.values
    val width = frames.maxOfOrNull { it.width } ?: return 0.0
    val height = frames.maxOfOrNull { it.height } ?: return 0.0
    val hero = frames.filter { it.y <= 0.5 && it.x <= 0.5 && it.width >= width - 1 && it.height > barBottom && it.height < height * 0.9 }
        .maxOfOrNull { it.y + it.height } ?: return 0.0
    return (hero - barBottom).coerceAtLeast(0.0)
}

/** The color under the top app bar at rest: the screen root's opaque background, else the system background. */
private fun screenBackground(session: ScreenSession, fallback: Color): Color {
    val props = session.root.content.properties as? dev.app8.engine.core.model.ViewProperties
    val hex = props?.backgroundColor?.let { session.root.string(it.value, session.scope.untracked) }?.takeIf { it.isNotEmpty() }
    val c = hex?.let { Color(ColorHex.parseOrFallback(it).argb) }
    return if (c != null && c.alpha >= 0.99f) c else fallback
}

/** Material 3 top app bar heights (AppBarSmallTokens / AppBarMediumTokens / AppBarLargeTokens). */
internal val NavigationBar.TopAppBarStyle.expandedHeight: Double
    get() = when (this) {
        NavigationBar.TopAppBarStyle.small, NavigationBar.TopAppBarStyle.centerAligned -> TOP_BAR_HEIGHT
        NavigationBar.TopAppBarStyle.medium -> MEDIUM_TOP_BAR_HEIGHT
        NavigationBar.TopAppBarStyle.large -> LARGE_TOP_BAR_HEIGHT
    }

internal val NavigationBar.TopAppBarStyle.collapses: Boolean
    get() = this == NavigationBar.TopAppBarStyle.medium || this == NavigationBar.TopAppBarStyle.large

/** `app8.screen.appeared` / `app8.screen.dismissed` (with `dwell_ms`). */
@Composable
private fun ScreenAnalytics(session: ScreenSession, bar: NavigationBar?) {
    val runtime = session.runtime
    DisposableEffect(session) {
        val start = System.currentTimeMillis()
        if (runtime.analyticsConfig.autoScreenEvents) {
            val title = bar?.title?.let { session.root.string(it, session.scope.untracked) }
            runtime.analyticsBus.dispatch(
                App8AnalyticsEvent(
                    name = App8AnalyticsEvent.Auto.SCREEN_APPEARED,
                    screenId = session.screenId,
                    locale = runtime.translations.activeLocaleTag,
                    properties = if (title != null) mapOf("title" to title) else emptyMap(),
                ),
            )
        }
        onDispose {
            if (runtime.analyticsConfig.autoScreenEvents) {
                runtime.analyticsBus.dispatch(
                    App8AnalyticsEvent(
                        name = App8AnalyticsEvent.Auto.SCREEN_DISMISSED,
                        screenId = session.screenId,
                        locale = runtime.translations.activeLocaleTag,
                        properties = mapOf("dwell_ms" to (System.currentTimeMillis() - start)),
                    ),
                )
            }
        }
    }
}

/**
 * The DSL `navigationBar` as a Material 3 top app bar (app8-dsl platforms.md): title or
 * `titleView`, arrow back button when the stack can pop, left / right bar items.
 * `topAppBar` picks the variant. At rest the bar is transparent over the screen, as on
 * iOS; when content scrolls under it the container turns `surfaceContainer` (a neutral
 * step from the screen's own background), never a shadow, per Material 3. With
 * `topEdgeEffect: hidden` (content under the bar, e.g. a hero image) the bar stays
 * transparent with its icons on legibility circles and the title hidden until the hero
 * has scrolled out from under it, then turns opaque with the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    session: ScreenSession,
    bar: NavigationBar,
    style: NavigationBar.TopAppBarStyle,
    behavior: TopAppBarScrollBehavior,
    canGoBack: Boolean,
    statusBarInset: Boolean,
    under: Color,
    overContent: Boolean,
    solid: Boolean,
) {
    val env = LocalRenderEnv.current
    val dispatcher = LocalDispatcher.current
    val root = session.root
    fun hex(s: String?) = s?.let { root.string(it) }?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }
    val titleColor = hex(bar.titleColor) ?: IosSystemColors.of("label", env.appearance)
    val tint = hex(bar.tintColor) ?: titleColor
    val title = bar.title?.let { root.string(it) }.orEmpty()
    val search = bar.search
    val slot = searchSlot(bar, title)
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val dark = env.appearance.isDark
    val scrolled = remember(under, dark) { dev.app8.engine.style.systemColorScheme(null, dark, under).surfaceContainer }
    // Over a hero: transparent, icons on legibility circles, title hidden; opaque with the
    // title once the hero has scrolled away.
    val overlay by androidx.compose.animation.core.animateFloatAsState(
        if (overContent && !solid) 1f else 0f,
        androidx.compose.animation.core.tween(dev.app8.engine.navigation.transitions.MaterialTransitions.COLOR_MS), label = "barOverlay",
    )
    val overContentColor = androidx.compose.ui.graphics.lerp(scrolled, Color.Transparent, overlay)
    val iconContainer = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = OVER_CONTENT_ICON_ALPHA * overlay)
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = if (overContent) overContentColor else Color.Transparent,
        scrolledContainerColor = if (overContent) overContentColor else scrolled,
        navigationIconContentColor = tint,
        titleContentColor = titleColor,
        actionIconContentColor = tint,
    )
    val titleSlot: @Composable () -> Unit = {
        val titleView = bar.titleView
        when {
            search != null && slot == SearchSlot.Title ->
                NavSearchField(session, search, Modifier.fillMaxWidth().padding(end = 16.dp))
            search != null && slot == SearchSlot.Button && searchOpen ->
                NavSearchField(session, search, Modifier.fillMaxWidth().padding(end = 16.dp), autoFocus = true, onClose = { searchOpen = false })
            titleView != null -> TitleView(session, titleView)
            else -> Text(
                title, color = titleColor, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = 1f - overlay },
            )
        }
    }
    val navigationIcon: @Composable () -> Unit = {
        // A left-aligned titleView replaces the left item (iOS); the back arrow stays: it is chrome.
        val left = bar.leftAction?.takeIf { bar.titleAlignment != "left" || bar.titleView == null }
        when {
            left != null -> BarItem(left, tint, iconContainer) { dispatcher.execute(root, left.action) }
            canGoBack -> IconButton(onClick = { dispatcher.execute(root, BACK) }, colors = iconColors(iconContainer)) { Symbol("arrow_back", tint) }
        }
    }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        val items = bar.rightActions ?: listOfNotNull(bar.rightAction)
        for (item in items) BarItem(item, tint, iconContainer) { dispatcher.execute(root, item.action) }
        if (slot == SearchSlot.Button && !searchOpen) IconButton(onClick = { searchOpen = true }) { Symbol("magnifyingglass", tint) }
    }
    val insets = if (statusBarInset) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0)
    androidx.compose.foundation.layout.Column {
        when {
            search != null && slot == SearchSlot.Title && style == NavigationBar.TopAppBarStyle.small ->
                SearchAppBar(session, search, behavior, statusBarInset, scrolled, navigationIcon, actions)
            else -> when (style) {
            NavigationBar.TopAppBarStyle.small ->
                TopAppBar(titleSlot, navigationIcon = navigationIcon, actions = actions, windowInsets = insets, colors = colors, scrollBehavior = behavior)
            NavigationBar.TopAppBarStyle.centerAligned ->
                CenterAlignedTopAppBar(titleSlot, navigationIcon = navigationIcon, actions = actions, windowInsets = insets, colors = colors, scrollBehavior = behavior)
            NavigationBar.TopAppBarStyle.medium ->
                MediumTopAppBar(titleSlot, navigationIcon = navigationIcon, actions = actions, windowInsets = insets, colors = colors, scrollBehavior = behavior)
            NavigationBar.TopAppBarStyle.large ->
                LargeTopAppBar(titleSlot, navigationIcon = navigationIcon, actions = actions, windowInsets = insets, colors = colors, scrollBehavior = behavior)
            }
        }
        if (search != null && slot == SearchSlot.Row) {
            // The search row continues the bar: same container color as it scrolls.
            val fraction = if (overContent) (if (solid) 1f else 0f) else if (style.collapses) behavior.state.collapsedFraction else behavior.state.overlappedFraction
            val rowColor by androidx.compose.animation.animateColorAsState(
                if (fraction > 0.01f) scrolled else Color.Transparent, label = "searchRow",
            )
            Box(Modifier.background(rowColor)) { NavSearchRow(session, search) }
        }
    }
}

/** M3 app bar with search: vertical padding around the 56dp field (SearchBar.kt `AppBarWithSearchVerticalPadding`). */
internal const val SEARCH_APP_BAR_VERTICAL_PADDING = 4

/** Horizontal padding around the field (`SearchBarAsTopBarPadding` 8dp + the bar's 4dp edge inset). */
internal const val SEARCH_APP_BAR_FIELD_INSET = 12

/**
 * `navigationBar.search` with no title (`integrated`, `inline`, `automatic` without a
 * title): the Material 3 app bar with search (Compose `AppBarWithSearch`). A 56dp
 * full-pill field with 4dp above and below (a 64dp bar) and 12dp from the screen edges,
 * the back arrow and actions beside it. Transparent at rest; once content scrolls under
 * it the bar turns `surfaceContainer` and the field `surfaceContainerHighest`. With an
 * enterAlways [behavior] the whole bar slides away with the content and returns on any
 * upward scroll; the status bar strip keeps the bar color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAppBar(
    session: ScreenSession,
    search: dev.app8.engine.core.model.SearchBar,
    behavior: TopAppBarScrollBehavior,
    statusBarInset: Boolean,
    scrolled: Color,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val state = behavior.state
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heightPx = with(density) { TOP_BAR_HEIGHT.toFloat().dp.toPx() }
    // The bar owns its offset range, like TopAppBar does in its own layout.
    androidx.compose.runtime.SideEffect { if (state.heightOffsetLimit != -heightPx) state.heightOffsetLimit = -heightPx }
    val isScrolled = state.overlappedFraction > 0.01f
    val motion = androidx.compose.animation.core.tween<Color>(dev.app8.engine.navigation.transitions.MaterialTransitions.COLOR_MS)
    val barColor by androidx.compose.animation.animateColorAsState(if (isScrolled) scrolled else Color.Transparent, motion, label = "searchBar")
    val scheme = MaterialTheme.colorScheme
    val fieldColor by androidx.compose.animation.animateColorAsState(
        if (isScrolled) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh, motion, label = "searchField",
    )
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().background(barColor)) {
        if (statusBarInset) androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        // Visible height shrinks with the offset; the row slides up inside it.
        androidx.compose.ui.layout.Layout(
            content = {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().height(TOP_BAR_HEIGHT.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigationIcon()
                    NavSearchField(
                        session, search,
                        Modifier.weight(1f).padding(horizontal = (SEARCH_APP_BAR_FIELD_INSET - 4).dp, vertical = SEARCH_APP_BAR_VERTICAL_PADDING.dp),
                        container = fieldColor,
                    )
                    actions()
                }
            },
            modifier = Modifier.fillMaxWidth().clipToBounds(),
        ) { measurables, constraints ->
            val p = measurables.first().measure(constraints.copy(minHeight = 0))
            val offset = state.heightOffset.coerceIn(-p.height.toFloat(), 0f)
            val h = (p.height + offset).roundToIntSafe()
            layout(p.width, h) { p.place(0, offset.roundToIntSafe()) }
        }
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

/**
 * `navigationBar.titleView`: a DSL component (brand content, identical to iOS) at its
 * fitting size, like iOS `systemLayoutSizeFitting`, in the bar's title slot.
 */
@Composable
private fun TitleView(session: ScreenSession, component: dev.app8.engine.core.model.Component) {
    val env = LocalRenderEnv.current
    val cell = remember(session, component) { dev.app8.engine.render.collection.Cell.inParentScope(session.root, component) } ?: return
    DisposableEffect(cell) { onDispose { cell.dispose() } }
    val solved = cell.layout(env, width = 0.0, height = null, selfSizingWidth = true)
    dev.app8.engine.render.collection.CellView(cell, solved)
}

private val BACK = dev.app8.engine.core.model.Action(dev.app8.engine.core.model.Action.Type.navigation, isBack = true)

/** Opacity of the circle behind bar icons over a hero (surfaceContainerLowest): legible on any image. */
internal const val OVER_CONTENT_ICON_ALPHA = 0.9f

@Composable
private fun iconColors(container: Color) =
    if (container.alpha > 0f) androidx.compose.material3.IconButtonDefaults.iconButtonColors(containerColor = container)
    else androidx.compose.material3.IconButtonDefaults.iconButtonColors()

@Composable
private fun BarItem(item: BarAction, defaultTint: Color, iconContainer: Color = Color.Transparent, onClick: () -> Unit) {
    val color = item.color?.let { Color(ColorHex.parseOrFallback(it).argb) } ?: defaultTint
    val icon = item.icon
    if (icon != null) {
        IconButton(onClick = onClick, colors = iconColors(iconContainer)) { Symbol(icon, color) }
    } else {
        TextButton(onClick = onClick) { Text(item.label.orEmpty(), color = color) }
    }
}

/**
 * A Material Symbol by SF or Material name, [size] dp (24dp in chrome). Chrome icons are
 * dp, like Material's `Icon`: they do not grow with the user's font scale.
 */
@Composable
internal fun Symbol(name: String, color: Color, size: Float = 24f) {
    val glyph = MaterialSymbols.resolve(androidx.compose.ui.platform.LocalContext.current, name, LocalRenderEnv.current.runtime.diagnostics, "bar item")
        ?: return
    val fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { size.dp.toSp() }
    // Exactly size × size like Material's Icon: the font's line box is taller than its em.
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        BasicText(
            glyph.text,
            style = TextStyle(color = color, fontSize = fontSize, lineHeight = fontSize, fontFamily = MaterialSymbols.family(glyph.filled, 400, size)),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.wrapContentSize(unbounded = true),
        )
    }
}
