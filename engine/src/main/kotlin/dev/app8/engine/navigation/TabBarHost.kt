package dev.app8.engine.navigation

import dev.app8.engine.render.hexColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.string
import dev.app8.engine.style.App8ChromeTheme

/**
 * Material 3 Expressive short navigation bar height (without the system navigation bar
 * inset): what Pixel apps (Clock, Phone) ship in 2026. The baseline 80dp bar is retired.
 */
internal const val TAB_BAR_HEIGHT = 64.0

/** Window width (dp) from which tab items lay out icon-beside-label, centered (M3 medium width). */
internal const val TAB_BAR_HORIZONTAL_ITEMS_WIDTH = 600


/**
 * `tabBarScreen` as a Material 3 Expressive `ShortNavigationBar`: each tab keeps its own back stack (and
 * saved state) like a UITabBarController of navigation controllers. Tab content extends
 * under the bar; its safe area bottom is the bar's top edge.
 */
@Composable
internal fun TabBarHost(session: ScreenSession, flow: FlowController, layer: Layer) {
    val env = LocalRenderEnv.current
    val content = session.tabBar ?: return
    val tabs = content.tabs
    if (tabs.isEmpty()) return
    val state = remember(session) {
        TabState(tabs.indexOfFirst { it.id == content.initialTab }.coerceAtLeast(0), tabs.size, tabs.map { it.id })
    }
    val stacks = remember(session) { tabs.map { StackState(Route(it.screen), flow.sessions) } }
    androidx.compose.runtime.DisposableEffect(state, stacks) {
        flow.registerTabs(layer, state, stacks)
        onDispose {
            flow.unregisterTabs(state)
            stacks.forEach { it.discard() }
        }
    }
    // The tab whose content is drawn. It follows the selection once the incoming tab's top
    // screen has loaded (at most PREPARE_TIMEOUT_MS), so the fade through never shows a blank
    // screen; a tab visited before is still loaded and switches at once.
    var shown by remember(session) { androidx.compose.runtime.mutableIntStateOf(state.selected) }
    androidx.compose.runtime.LaunchedEffect(state, stacks) {
        androidx.compose.runtime.snapshotFlow { state.selected }.collect { target ->
            stacks.getOrNull(target)?.entries?.lastOrNull()?.let { top ->
                flow.prepare(top)?.let { load -> kotlinx.coroutines.withTimeoutOrNull(PREPARE_TIMEOUT_MS) { load.await() } }
            }
            shown = target
        }
    }
    val holder = rememberSaveableStateHolder()
    // `barStyle`: a text bar or a component bar; null draws the platform bar exactly as before.
    val styled = content.barStyle?.takeUnless { it.isSystem }
    val barTemplate = styled?.component?.let { name -> remember(session, name) { env.runtime.resolveTemplate(name) } }
    val barHeight = when {
        styled == null -> TAB_BAR_HEIGHT
        else -> styled.height ?: if (barTemplate != null) COMPONENT_TAB_BAR_HEIGHT else TEXT_TAB_BAR_HEIGHT
    }
    val reselectScope = androidx.compose.runtime.rememberCoroutineScope()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value.toDouble()
    fun hex(s: String?) = session.root.hexColor(s)
    val accent = hex(content.tintColor)
    val authoredUnselected = hex(content.unselectedColor)
    val badgeColor = hex(content.badgeColor)
    val badgeTextColor = hex(content.badgeTextColor)
    // The tab bar tint is the app's accent for chrome outside the tabs too (sheets, dialogs).
    androidx.compose.runtime.SideEffect { env.runtime.chromeAccent = accent }
    App8ChromeTheme(accent, env.appearance.isDark) {
    // Chrome colors from the app's Material roles: the selected tint is `tintColor`, else
    // `primary`; unselected items are `unselectedColor` while it reaches 4.5:1 on the bar,
    // else `onSurfaceVariant` (the iOS gray defaults read at 2.3–3:1 on `surfaceContainer`).
    val scheme = MaterialTheme.colorScheme
    val tint = accent ?: scheme.primary
    val unselected = dev.app8.engine.style.readableOn(authoredUnselected, scheme.surfaceContainer, scheme.onSurfaceVariant)
    val selectionIndicator = selectionIndicatorColorFor(hex(content.selectionIndicatorColor), scheme.surfaceContainer)
    // The fade through passes through the surface color, never the black window behind.
    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        val i = state.selected
        // M3 fade through between tabs. Each tab's saved state (scroll, fields) lives in the
        // holder and its screens in the session cache, so a revisit draws at once, unchanged.
        androidx.compose.animation.AnimatedContent(
            targetState = shown,
            transitionSpec = { MotionSpecs.tabFadeThrough() },
            contentKey = { it },
            label = "a8-tab",
        ) { tab ->
            // The outgoing tab is gone as far as the screen and back gestures go.
            val current = tab == shown
            val backOwner = androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner(enabled = current)
            androidx.compose.runtime.CompositionLocalProvider(
                LocalScreenVisible provides (LocalScreenVisible.current && current),
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides backOwner,
            ) {
                holder.SaveableStateProvider(tab) {
                    StackHost(flow, layer, stacks[tab], state, Chrome(bottom = barHeight + navInset, inTabs = true))
                }
            }
        }
        // `hidesTabBar` on the top screen of the selected tab slides the bar away. During a
        // predictive back to a screen that shows it, the bar follows the gesture (iOS).
        val stack = stacks[i]
        val hidden = stack.topHidesTabBar
        val belowShows = stack.entries.size > 1 && stack.hidesTabBar[stack.entries[stack.entries.size - 2].id] != true
        val offset = androidx.compose.animation.core.animateFloatAsState(
            if (hidden) 1f else 0f,
            // Engine chrome: the Material standard motion scheme's spatial spring.
            MotionSpecs.spatialSpring(),
            label = "a8-tab-bar",
        )
        val back = stack.backProgress
        Box(
            Modifier.align(Alignment.BottomCenter).graphicsLayer {
                val p = if (hidden && belowShows) back?.value else null
                translationY = size.height * (if (p != null) 1f - p else offset.value)
            },
        ) {
        if (styled != null) {
            // The bar is composed outside any screen: its template's actions
            // (selectTab) need a dispatcher of their own, on this flow layer.
            val host = LocalHostEffects.current
            val barDispatcher = remember(session, flow, layer) { dev.app8.engine.actions.ActionDispatcher(env.runtime, LiveNavigator(flow, layer), host) }
            androidx.compose.runtime.CompositionLocalProvider(dev.app8.engine.render.LocalDispatcher provides barDispatcher) {
                StyledBar(session, styled, barTemplate, tabs, state, stacks, barHeight, navInset, tint, authoredUnselected ?: unselected, reselectScope)
            }
            return@Box
        }
        // Medium and wider windows: centered items with the icon beside the label.
        val windowWidth = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
        val wide = with(androidx.compose.ui.platform.LocalDensity.current) { windowWidth.toDp().value } >= TAB_BAR_HORIZONTAL_ITEMS_WIDTH
        ShortNavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            arrangement = if (wide) ShortNavigationBarArrangement.Centered else ShortNavigationBarArrangement.EqualWeight,
        ) {
            tabs.forEachIndexed { index, tab ->
                val badge = tab.badge?.let { session.root.string(it) }?.takeIf { it.isNotEmpty() && it != "0" }
                ShortNavigationBarItem(
                    selected = index == i,
                    onClick = {
                        if (index == i) {
                            // Reselect: pop to root, else scroll the root screen to the top.
                            reselectScope.launch { stacks[index].reselect() }
                        } else {
                            state.selected = index
                        }
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (badge != null) {
                                    // Bar `badgeColor` / per-tab override; text `badgeTextColor`, default white on a custom color.
                                    val custom = hex(tab.badgeColor) ?: badgeColor
                                    val container = custom ?: androidx.compose.material3.BadgeDefaults.containerColor
                                    Badge(
                                        containerColor = container,
                                        contentColor = badgeTextColor
                                            ?: if (custom != null) Color.White else androidx.compose.material3.contentColorFor(container),
                                    ) { Text(badge) }
                                }
                            },
                        ) {
                            Symbol(tab.icon, if (index == i) tint else unselected)
                        }
                    },
                    label = { Text(tab.label, maxLines = 1) },
                    iconPosition = if (wide) NavigationItemIconPosition.Start else NavigationItemIconPosition.Top,
                    colors = ShortNavigationBarItemDefaults.colors(
                        selectedIconColor = tint,
                        selectedTextColor = tint,
                        selectedIndicatorColor = selectionIndicator,
                        unselectedIconColor = unselected,
                        unselectedTextColor = unselected,
                    ),
                )
            }
        }
        }
    }
    }
}

/** A `barStyle` bar (text or component) over its background, which also fills the bottom safe area. */
@Composable
private fun StyledBar(
    session: ScreenSession,
    style: dev.app8.engine.core.model.TabBarStyle,
    template: dev.app8.engine.core.model.Component?,
    tabs: List<dev.app8.engine.core.model.Tab>,
    state: TabState,
    stacks: List<StackState>,
    height: Double,
    navInset: Double,
    tint: Color,
    unselected: Color,
    reselectScope: kotlinx.coroutines.CoroutineScope,
) {
    val env = LocalRenderEnv.current
    val content = session.tabBar ?: return
    fun hex(s: String?) = session.root.hexColor(s)
    val progress = rememberSelectionProgress(state)
    val badges = tabs.map { tab -> tab.badge?.let { session.root.string(it) }?.takeIf { it.isNotEmpty() && it != "0" } }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().tabBarBackground(style.background, env)) {
        if (template != null) {
            // A host indicator only for an explicit capsule / underline / box (opacity is the template's job).
            val indicator = style.selection?.takeIf { it != dev.app8.engine.core.model.TabBarStyle.Selection.opacity }
                ?.let { it to indicatorColorFor(style, hex(style.indicatorColor), tint) }
            ComponentTabBar(session, template, tabs, badges, state, progress, height, indicator)
        } else {
            val opacity = style.effectiveSelection == dev.app8.engine.core.model.TabBarStyle.Selection.opacity
            // Labels take their color from their text style; without one, tint (selected) and
            // unselectedColor (tint for both with `opacity`, where only the opacity differs).
            val fallback = MaterialTheme.typography.labelLarge
            fun label(model: dev.app8.engine.core.model.style.TextModel?, color: Color): androidx.compose.ui.text.TextStyle {
                val s = model?.let { env.text(it, 12f, color, 1).style } ?: fallback.copy(color = color)
                return if (s.color == Color.Unspecified) s.copy(color = color) else s
            }
            val textModel = env.textModel(style.text, session.root)
            val selectedModel = env.textModel(style.selectedText, session.root) ?: textModel
            TextTabBar(
                tabs = tabs,
                selected = state.selected,
                progress = progress,
                style = style,
                labelStyle = label(textModel, if (opacity) tint else unselected),
                selectedLabelStyle = label(selectedModel, tint),
                height = height,
                indicator = indicatorColorFor(style, hex(style.indicatorColor), tint),
                badge = { i ->
                    badges[i]?.let { TabBadge(it, hex(tabs[i].badgeColor) ?: hex(content.badgeColor), hex(content.badgeTextColor)) }
                },
                onTap = { index ->
                    if (index == state.selected) reselectScope.launch { stacks[index].reselect() } else state.selected = index
                },
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.fillMaxWidth().height(navInset.dp))
    }
}

/**
 * The tab bar's navigator: resolves [FlowController.navigatorFor] on every call. A navigator
 * taken when the bar is first composed has no tabs yet (they register in an effect after
 * composition), so its `selectTab` did nothing until something else rebuilt it.
 */
private class LiveNavigator(private val flow: FlowController, private val layer: Layer) : dev.app8.engine.actions.Navigator {
    private val now get() = flow.navigatorFor(layer)
    override fun push(screenId: String, params: Map<String, Any?>, transition: dev.app8.engine.core.model.ScreenTransition?, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin?) = now.push(screenId, params, transition, zoom)
    override fun pop(toRoot: Boolean) = now.pop(toRoot)
    override fun present(screenId: String, params: Map<String, Any?>, presentation: dev.app8.engine.core.model.Action.PresentationStyle, detents: List<dev.app8.engine.core.model.Action.SheetDetent>?, grabber: Boolean?, transition: dev.app8.engine.core.model.ScreenTransition?, zoom: dev.app8.engine.navigation.transitions.ZoomOrigin?) =
        now.present(screenId, params, presentation, detents, grabber, transition, zoom)
    override fun dismiss() = now.dismiss()
    override suspend fun dismissAndAwait(): dev.app8.engine.actions.Navigator = now.dismissAndAwait()
    override fun selectTab(index: Int?, id: String?) = now.selectTab(index, id)
    override fun completeFlow(destination: String) = now.completeFlow(destination)
}

/**
 * `selectionIndicatorColor` of the platform bar, else the spec default: black at 10% on a
 * light bar, white at 10% on a dark one, so the pill never mixes the brand tint with grey.
 */
internal fun selectionIndicatorColorFor(authored: Color?, bar: Color): Color =
    authored ?: if (bar.luminance() > 0.5f) Color.Black.copy(alpha = TAB_SELECTION_DEFAULT_ALPHA) else Color.White.copy(alpha = TAB_SELECTION_DEFAULT_ALPHA)

internal const val TAB_SELECTION_DEFAULT_ALPHA = 0.10f
