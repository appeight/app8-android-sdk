package dev.app8.engine.navigation

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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.string
import dev.app8.engine.style.App8ChromeTheme
import dev.app8.engine.style.IosSystemColors

/**
 * Material 3 Expressive short navigation bar height (without the system navigation bar
 * inset): what Pixel apps (Clock, Phone) ship in 2026. The baseline 80dp bar is retired.
 */
internal const val TAB_BAR_HEIGHT = 64.0

/** Window width (dp) from which tab items lay out icon-beside-label, centered (M3 medium width). */
internal const val TAB_BAR_HORIZONTAL_ITEMS_WIDTH = 600

/** The selected item's indicator pill: the tab tint at this alpha (Studio uses the same). */
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
    val stacks = remember(session) { tabs.map { StackState(Route(it.screen)) } }
    androidx.compose.runtime.DisposableEffect(state, stacks) {
        flow.registerTabs(layer, state, stacks)
        onDispose { flow.unregisterTabs(state) }
    }
    val holder = rememberSaveableStateHolder()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value.toDouble()
    fun hex(s: String?) = s?.let { Color(ColorHex.parseOrFallback(it).argb) }
    val accent = hex(content.tintColor)
    val tint = accent ?: IosSystemColors.of("systemBlue", env.appearance)
    val unselected = hex(content.unselectedColor) ?: IosSystemColors.of("systemGray", env.appearance)
    val badgeColor = hex(content.badgeColor)
    val badgeTextColor = hex(content.badgeTextColor)
    // The tab bar tint is the app's accent for chrome outside the tabs too (sheets, dialogs).
    androidx.compose.runtime.SideEffect { env.runtime.chromeAccent = accent }
    App8ChromeTheme(accent, env.appearance.isDark) {
    Box(Modifier.fillMaxSize()) {
        val i = state.selected
        key(i) {
            holder.SaveableStateProvider(i) {
                StackHost(flow, layer, stacks[i], state, Chrome(bottom = TAB_BAR_HEIGHT + navInset, inTabs = true))
            }
        }
        // `hidesTabBar` on the top screen of the selected tab slides the bar away.
        androidx.compose.animation.AnimatedVisibility(
            visible = !stacks[i].topHidesTabBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.slideOutVertically { it },
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
                    onClick = { state.selected = index },
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
