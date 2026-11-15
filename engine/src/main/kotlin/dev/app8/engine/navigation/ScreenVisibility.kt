package dev.app8.engine.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * Whether the content composed here is on screen as far as navigation and the app lifecycle
 * go (DSL 0.6.1 native visibility): the host is at least STARTED, the screen's layer is the top
 * one and at rest (not under a sheet or full-screen cover, not being presented or dismissed),
 * and the screen is the settled top of its stack (the selected tab's, since only the selected
 * tab is composed). Components add their own part (in the window, not clipped away).
 *
 * Outside a navigation host (previews, tests) it is `true`; off-screen renders such as the
 * context menu preview provide `false`.
 */
internal val LocalScreenVisible = compositionLocalOf { true }

/** The host lifecycle (activity or fragment hosting the App8 view) is at least STARTED. */
@Composable
internal fun rememberHostForeground(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return state.isAtLeast(Lifecycle.State.STARTED)
}

/**
 * Navigation's part of [LocalScreenVisible] for the screen of [route] on [stack] in [layer]:
 * the parent's value, the layer on top and at rest, the route on top with its enter transition
 * finished. Turns false as soon as a covering or leaving transition starts, true only once an
 * appearing one has ended.
 */
@Composable
internal fun rememberScreenVisible(flow: FlowController, layer: Layer, stack: StackState, route: Route): Boolean {
    val parent = LocalScreenVisible.current
    val transition = LocalNavAnimatedContentScope.current.transition
    val own by remember(flow, layer, stack, route, transition) {
        derivedStateOf {
            isScreenOnTop(flow, layer, stack, route) &&
                transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
        }
    }
    return parent && own
}

/**
 * [route] is the top of [stack] in [layer], and [layer] is the top layer at rest: no sheet or
 * modal over it (any detent), not being presented or dismissed.
 */
internal fun isScreenOnTop(flow: FlowController, layer: Layer, stack: StackState, route: Route): Boolean =
    flow.layers.lastOrNull() === layer && layer.presented && !layer.dismissing && stack.entries.lastOrNull() == route
