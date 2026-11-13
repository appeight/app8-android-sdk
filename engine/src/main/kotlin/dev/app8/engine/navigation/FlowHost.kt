package dev.app8.engine.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.SharedTransitionLayout
import dev.app8.engine.navigation.transitions.CustomModal
import dev.app8.engine.navigation.transitions.NativeZoomModal
import dev.app8.engine.navigation.transitions.MaterialTransitions
import dev.app8.engine.navigation.transitions.TransitionFrame
import dev.app8.engine.navigation.transitions.stackTransitionSpec
import dev.app8.engine.core.model.Action
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.style.IosSystemColors

/**
 * System chrome around a stack, in points: what the screens' safe area adds on each edge
 * beyond their own top app bar. [top]/[bottom] null = the window's safe drawing insets.
 */
@Immutable
internal data class Chrome(val top: Double? = null, val bottom: Double? = null, val inTabs: Boolean = false)

internal val LocalFlow = staticCompositionLocalOf<FlowController> { error("No FlowController") }

/** The whole app: the base stack, and modal layers over it. */
@Composable
internal fun FlowHost(flow: FlowController, modifier: Modifier = Modifier) {
    androidx.compose.runtime.CompositionLocalProvider(LocalFlow provides flow) {
        Box(modifier.fillMaxSize()) {
            val layers = flow.layers.toList()
            layers.firstOrNull()?.let { base -> key(base) { StackHost(flow, base, base.stack, null, Chrome()) } }
            for (layer in layers.drop(1)) key(layer) { ModalLayer(flow, layer) }
        }
    }
}

/**
 * A back stack drawn with Navigation 3: push/pop animations and the predictive back
 * gesture come from `NavDisplay`. Each entry is a [ScreenHost].
 */
@Composable
internal fun StackHost(flow: FlowController, layer: Layer, stack: StackState, tabs: TabState?, chrome: Chrome) {
    val navigator = remember(flow, layer, stack, tabs) { StackNavigator(flow, layer, stack, tabs) }
    // Shared elements (`shared` preset) morph inside this scope; screen keyframes run per
    // entry in TransitionFrame; `system` keeps Navigation 3's own animations.
    SharedTransitionLayout {
        val shared = this
        // `system`: Material shared axis X and the in-app predictive back preview.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val platformPush = remember(density) { MaterialTransitions.push<Route>(density) }
        val platformPop = remember(density) { MaterialTransitions.pop<Route>(density) }
        val platformPredictive = remember(density) { MaterialTransitions.predictivePop<Route>(density) }
        NavDisplay(
            backStack = stack.entries,
            onBack = { stack.pop(toRoot = false) },
            sharedTransitionScope = shared,
            transitionSpec = stackTransitionSpec(stack, pop = false, predictive = false, platformPush),
            popTransitionSpec = stackTransitionSpec(stack, pop = true, predictive = false, platformPop),
            predictivePopTransitionSpec = { edge: Int ->
                stackTransitionSpec<Route>(stack, pop = true, predictive = true) { platformPredictive(this, edge) }(this)
            },
            entryProvider = { route ->
                NavEntry(route, contentKey = route.id) {
                    TransitionFrame(route, stack, shared) {
                        ScreenHost(route, navigator, flow, layer, stack, chrome)
                    }
                }
            },
        )
    }
}

/** Height of the Material 3 bottom sheet drag handle with its vertical padding (SheetBottomTokens: 4dp + 2 × 22dp). */
internal const val SHEET_HANDLE_BLOCK = 48f

/** A presented layer: Material 3 modal bottom sheet (`sheet`, `formSheet`, `pageSheet`) or a full-screen cover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalLayer(flow: FlowController, layer: Layer) {
    val custom = layer.transition
    val zoom = layer.zoom
    if (custom != null && custom.nativeZoom && zoom != null) {
        NativeZoomModal(
            origin = zoom,
            sources = LocalRenderEnv.current.runtime.zoomSources,
            dismissing = layer.dismissing,
            onDismissRequest = { flow.dismiss(layer) },
            onRemoved = { flow.remove(layer) },
            backEnabled = layer.stack.entries.size <= 1,
        ) {
            Box(Modifier.fillMaxSize().background(IosSystemColors.of("systemBackground", LocalRenderEnv.current.appearance))) {
                StackHost(flow, layer, layer.stack, null, Chrome())
            }
        }
        return
    }
    if (custom != null) {
        CustomModal(
            transition = custom,
            dismissing = layer.dismissing,
            onDismissRequest = { flow.dismiss(layer) },
            onRemoved = { flow.remove(layer) },
            backEnabled = layer.stack.entries.size <= 1,
        ) { sized ->
            // A sized card owns no system bars: its screen's safe area is its own bounds.
            StackHost(flow, layer, layer.stack, null, if (sized) Chrome(top = 0.0, bottom = 0.0) else Chrome())
        }
        return
    }
    when (layer.presentation) {
        Action.PresentationStyle.sheet, Action.PresentationStyle.formSheet, Action.PresentationStyle.pageSheet -> {
            val detents = layer.detents.orEmpty()
            val hasMedium = detents.any { it is Action.SheetDetent.Medium }
            val state = rememberModalBottomSheetState(skipPartiallyExpanded = !hasMedium)
            // A large detent leaves the status bar and a 10pt gap above the sheet, like iOS.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val windowHeight = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp().value }
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
            val screenHeight = windowHeight
            val single = detents.singleOrNull()
            val height = when (single) {
                is Action.SheetDetent.Fixed -> Modifier.height(single.points.dp)
                is Action.SheetDetent.Fraction -> Modifier.height((screenHeight * single.fraction).dp)
                // The M3 drag handle block (32x4dp handle, 22dp above and below) sits above the content.
                else -> Modifier.height((windowHeight - statusTop - 10f - (if (layer.grabber != false) SHEET_HANDLE_BLOCK else 0f)).coerceAtLeast(0f).dp)
            }
            ModalBottomSheet(
                onDismissRequest = { flow.dismiss(layer) },
                sheetState = state,
                containerColor = IosSystemColors.of("systemBackground", LocalRenderEnv.current.appearance),
                dragHandle = if (layer.grabber != false) ({ BottomSheetDefaults.DragHandle() }) else null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                Box(height.fillMaxWidth()) {
                    StackHost(flow, layer, layer.stack, null, Chrome(top = 0.0))
                }
            }
        }
        else -> {
            val visible = remember { MutableTransitionState(false).apply { targetState = true } }
            BackHandler(enabled = layer.stack.entries.size <= 1) { flow.dismiss(layer) }
            AnimatedVisibility(
                visibleState = visible,
                enter = if (layer.presentation == Action.PresentationStyle.crossDissolve) fadeIn() else slideInVertically { it },
            ) {
                Box(Modifier.fillMaxSize().background(IosSystemColors.of("systemBackground", LocalRenderEnv.current.appearance))) {
                    StackHost(flow, layer, layer.stack, null, Chrome())
                }
            }
        }
    }
}
