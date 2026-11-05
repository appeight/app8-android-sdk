package dev.app8.engine.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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

/** Material 3 navigation bar height (without the system navigation bar inset). */
internal const val TAB_BAR_HEIGHT = 80.0

/** The selected item's indicator pill: the tab tint at this alpha (Studio uses the same). */
internal const val TAB_INDICATOR_ALPHA = 0.14f

/**
 * `tabBarScreen` as a Material 3 `NavigationBar`: each tab keeps its own back stack (and
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
    val holder = rememberSaveableStateHolder()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().value.toDouble()
    fun hex(s: String?) = s?.let { Color(ColorHex.parseOrFallback(it).argb) }
    val accent = hex(content.tintColor)
    val tint = accent ?: IosSystemColors.of("systemBlue", env.appearance)
    val unselected = hex(content.unselectedColor) ?: IosSystemColors.of("systemGray", env.appearance)
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
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            tabs.forEachIndexed { index, tab ->
                val badge = tab.badge?.let { session.root.string(it) }?.takeIf { it.isNotEmpty() && it != "0" }
                NavigationBarItem(
                    selected = index == i,
                    onClick = { state.selected = index },
                    icon = {
                        BadgedBox(badge = { if (badge != null) Badge { Text(badge) } }) {
                            Symbol(tab.icon, if (index == i) tint else unselected)
                        }
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedTextColor = tint,
                        unselectedTextColor = unselected,
                        indicatorColor = tint.copy(alpha = TAB_INDICATOR_ALPHA),
                    ),
                )
            }
        }
        }
    }
    }
}
