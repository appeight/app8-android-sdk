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

/** Material 3 small top app bar height. */
internal const val TOP_BAR_HEIGHT = 64.0

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
    val top = (chrome.top ?: window.calculateTopPadding().value.toDouble()) +
        (if (bar != null) TOP_BAR_HEIGHT else 0.0) + (if (searchRow) SEARCH_ROW_HEIGHT else 0.0)
    val bottom = chrome.bottom ?: window.calculateBottomPadding().value.toDouble()
    val safe = EdgeInsets(
        top = top + (extra?.top ?: 0.0),
        left = window.calculateLeftPadding(ld).value.toDouble() + (extra?.left ?: 0.0),
        bottom = bottom + (extra?.bottom ?: 0.0),
        right = window.calculateRightPadding(ld).value.toDouble() + (extra?.right ?: 0.0),
    )
    ScreenAnalytics(session, bar)
    val backdrop = rememberApp8Backdrop()
    Box(Modifier.fillMaxSize().background(background)) {
        App8BackdropSource(backdrop) { m -> ScreenCanvas(session, safe, m) }
        if (bar != null) TopBar(session, bar, canGoBack, chrome.top == null)
    }
    @Suppress("UNUSED_EXPRESSION") env
}

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
 * The DSL `navigationBar` as a Material 3 top app bar (app8-dsl platforms.md): title,
 * arrow back button when the stack can pop, left / right bar items. Transparent, like the
 * iOS 26 bar, over content that extends beneath it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(session: ScreenSession, bar: NavigationBar, canGoBack: Boolean, statusBarInset: Boolean) {
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
    androidx.compose.foundation.layout.Column {
    TopAppBar(
        title = {
            when {
                search != null && slot == SearchSlot.Title ->
                    NavSearchField(session, search, Modifier.fillMaxWidth().padding(end = 16.dp))
                search != null && slot == SearchSlot.Button && searchOpen ->
                    NavSearchField(session, search, Modifier.fillMaxWidth().padding(end = 16.dp), autoFocus = true, onClose = { searchOpen = false })
                else -> Text(title, color = titleColor, maxLines = 1)
            }
        },
        navigationIcon = {
            val left = bar.leftAction
            when {
                left != null -> BarItem(left, tint) { dispatcher.execute(root, left.action) }
                canGoBack -> IconButton(onClick = { dispatcher.execute(root, BACK) }) { Symbol("arrow_back", tint) }
            }
        },
        actions = {
            val items = bar.rightActions ?: listOfNotNull(bar.rightAction)
            for (item in items) BarItem(item, tint) { dispatcher.execute(root, item.action) }
            if (slot == SearchSlot.Button && !searchOpen) IconButton(onClick = { searchOpen = true }) { Symbol("magnifyingglass", tint) }
        },
        windowInsets = if (statusBarInset) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
    )
    if (search != null && slot == SearchSlot.Row) NavSearchRow(session, search)
    }
}

private val BACK = dev.app8.engine.core.model.Action(dev.app8.engine.core.model.Action.Type.navigation, isBack = true)

@Composable
private fun BarItem(item: BarAction, defaultTint: Color, onClick: () -> Unit) {
    val color = item.color?.let { Color(ColorHex.parseOrFallback(it).argb) } ?: defaultTint
    val icon = item.icon
    if (icon != null) {
        IconButton(onClick = onClick) { Symbol(icon, color) }
    } else {
        TextButton(onClick = onClick) { Text(item.label.orEmpty(), color = color) }
    }
}

/** A Material Symbol by SF or Material name, 24dp. */
@Composable
internal fun Symbol(name: String, color: Color, size: Float = 24f) {
    val glyph = MaterialSymbols.resolve(androidx.compose.ui.platform.LocalContext.current, name, LocalRenderEnv.current.runtime.diagnostics, "bar item")
        ?: return
    BasicText(
        glyph.text,
        style = TextStyle(color = color, fontSize = size.sp, fontFamily = MaterialSymbols.family(glyph.filled, 400, size)),
        maxLines = 1,
        softWrap = false,
    )
}
