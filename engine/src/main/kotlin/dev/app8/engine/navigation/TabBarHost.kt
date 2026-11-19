package dev.app8.engine.navigation

import dev.app8.engine.render.hexColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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

/** The selected item's indicator pill: the tab tint at this alpha (the web renderer uses the same). */
internal const val TAB_INDICATOR_ALPHA = 0.14f

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
                    StackHost(flow, layer, stacks[tab], state, Chrome(bottom = TAB_BAR_HEIGHT + navInset, inTabs = true))
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
                        selectedIndicatorColor = tint.copy(alpha = TAB_INDICATOR_ALPHA),
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
