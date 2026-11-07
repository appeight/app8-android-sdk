package dev.app8.engine.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.app8.engine.actions.Navigator
import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.App
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.navigation.transitions.ResolvedTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One screen on a back stack. [id] is unique per push, so the same screen can be pushed twice. */
internal data class Route(
    val screenId: String,
    val params: Map<String, Any?> = emptyMap(),
    /** The transition that pushed this route (and pops it); null = the platform default. */
    val transition: ResolvedTransition? = null,
    val id: Long = nextId.incrementAndGet(),
) {
    private companion object {
        val nextId = AtomicLong()
    }
}

/** A back stack (UINavigationController). */
@Stable
internal class StackState(root: Route) {
    val entries: SnapshotStateList<Route> = mutableStateListOf(root)

    /** Routes whose screen sets `hidesTabBar` (filled in as screens load). */
    val hidesTabBar: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean> = androidx.compose.runtime.mutableStateMapOf()

    /** The last change: true after a pop (the animation plays the popped route's transition in reverse). */
    var popping: Boolean by androidx.compose.runtime.mutableStateOf(false)

    /** The route the last pop removed (its transition drives the pop animation). */
    var lastPopped: Route? by androidx.compose.runtime.mutableStateOf(null)

    fun push(route: Route) {
        popping = false
        entries += route
    }

    fun pop(toRoot: Boolean): Boolean {
        if (entries.size <= 1) return false
        popping = true
        lastPopped = entries.last()
        if (toRoot) entries.removeRange(1, entries.size) else entries.removeAt(entries.lastIndex)
        return true
    }

    /** Whether the top screen hides the enclosing tab bar (iOS `hidesBottomBarWhenPushed`). */
    val topHidesTabBar: Boolean get() = entries.lastOrNull()?.let { hidesTabBar[it.id] } == true
}

/** The base flow or a presented modal (sheet / full screen), each with its own stack. */
@Stable
internal class Layer(
    val stack: StackState,
    /** Null for the base layer. */
    val presentation: Action.PresentationStyle?,
    val detents: List<Action.SheetDetent>? = null,
    val grabber: Boolean? = null,
    /** A custom modal transition (`mode: modal`); null for system presentations. */
    val transition: ResolvedTransition? = null,
) {
    /** A custom modal plays its exit animation first; the host removes the layer when done. */
    var dismissing: Boolean by androidx.compose.runtime.mutableStateOf(false)
}

/** Selected tab of a `tabBarScreen`. */
@Stable
internal class TabState(initial: Int, val count: Int, private val ids: List<String>) {
    private val selectedState = mutableIntStateOf(initial)
    var selected: Int
        get() = selectedState.intValue
        set(value) {
            selectedState.intValue = value.coerceIn(0, (count - 1).coerceAtLeast(0))
        }

    fun select(index: Int?, id: String?) {
        id?.let { ids.indexOf(it) }?.takeIf { it >= 0 }?.let { selected = it; return }
        index?.let { selected = it }
    }
}

/**
 * The app's navigation state (iOS `FlowCoordinator`): the current flow's base stack plus
 * the modal layers presented over it. All state is snapshot state; the composables in
 * `FlowHost.kt` draw it with Navigation 3 (`NavDisplay`, predictive back) and Material 3
 * sheets.
 */
@Stable
internal class FlowController(
    private val app: App?,
    startScreen: String,
    params: Map<String, Any?> = emptyMap(),
    /** The flow [startScreen] belongs to (iOS `currentFlowId`); null for a lone screen. */
    startFlowId: String? = null,
    private val warn: (String) -> Unit = {},
    /** Pushes run here when they must load the target screen's default transition first. */
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    /** A screen's own default transition (`content.transition` on its root). */
    private val screenTransition: (suspend (String) -> ScreenTransition?)? = null,
) {
    var currentFlowId: String? = startFlowId
        private set

    /** Bumped when a screen's top bar turns opaque or transparent: status bar icons re-sample what is under them. */
    var chromeEpoch: Int by androidx.compose.runtime.mutableIntStateOf(0)
    val layers: SnapshotStateList<Layer> = mutableStateListOf(Layer(StackState(Route(startScreen, params)), null))

    /** The base flow's tab bar while it is on screen (TabBarHost registers it). */
    private var tabs: Pair<TabState, List<StackState>>? = null
    private val pending = ArrayDeque<dev.app8.engine.App8NavigationRequest>()
    /** After a host `completeFlow`, requests wait this long (ms, uptime) for the new flow's tab bar. */
    private var awaitTabsUntil = 0L

    fun registerTabs(layer: Layer, state: TabState, stacks: List<StackState>) {
        if (layers.firstOrNull() !== layer) return
        tabs = state to stacks
        drain()
    }

    fun unregisterTabs(state: TabState) {
        if (tabs?.first === state) tabs = null
    }

    /** A host request (App8Instance.navigate); runs in order, see [App8NavigationRequest]. */
    fun enqueue(request: dev.app8.engine.App8NavigationRequest) {
        pending += request
        drain()
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            val waiting = tabs == null && android.os.SystemClock.uptimeMillis() < awaitTabsUntil
            if (waiting && pending.first() !is dev.app8.engine.App8NavigationRequest.CompleteFlow) {
                scope?.launch {
                    kotlinx.coroutines.delay((awaitTabsUntil - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1))
                    drain()
                }
                return
            }
            apply(pending.removeFirst())
        }
    }

    private fun apply(request: dev.app8.engine.App8NavigationRequest) {
        val modal = layers.size > 1
        // The active stack: the top modal's, else the selected tab's, else the base stack.
        val stack = layers.lastOrNull()?.takeIf { modal }?.stack
            ?: tabs?.let { (state, stacks) -> stacks.getOrNull(state.selected) }
            ?: layers.firstOrNull()?.stack
            ?: return
        when (request) {
            is dev.app8.engine.App8NavigationRequest.Push -> if (!modal) {
                push(stack, request.screenId, request.params.mapValues { (_, v) -> if (v is kotlinx.serialization.json.JsonElement) dev.app8.engine.core.vars.Values.fromJson(v) else v }, null)
            }
            dev.app8.engine.App8NavigationRequest.Pop -> if (!modal) stack.pop(toRoot = false)
            dev.app8.engine.App8NavigationRequest.PopToRoot -> if (!modal) stack.pop(toRoot = true)
            dev.app8.engine.App8NavigationRequest.Dismiss -> dismissTop()
            is dev.app8.engine.App8NavigationRequest.SelectTab -> tabs?.first?.select(request.index, request.id)
                ?: warn("selectTab: no tab bar on screen")
            is dev.app8.engine.App8NavigationRequest.CompleteFlow -> {
                val before = currentFlowId
                completeFlow(request.destination)
                if (currentFlowId != before) {
                    tabs = null
                    awaitTabsUntil = android.os.SystemClock.uptimeMillis() + 1500
                }
            }
        }
    }

    fun present(
        screenId: String,
        params: Map<String, Any?>,
        presentation: Action.PresentationStyle,
        detents: List<Action.SheetDetent>?,
        grabber: Boolean?,
        transition: ScreenTransition? = null,
    ) {
        val resolved = ResolvedTransition.resolve(transition, app)?.takeIf { it.isAnimated || it.kind == ResolvedTransition.Kind.None }
        layers += Layer(StackState(Route(screenId, params)), presentation, detents, grabber, resolved)
    }

    /** Dismisses [layer]: a custom modal animates out first (see [Layer.dismissing]). */
    fun dismiss(layer: Layer) {
        if (layers.indexOf(layer) <= 0) return
        if (layer.transition?.isAnimated == true) layer.dismissing = true else layers.remove(layer)
    }

    /** Removes [layer] now (end of its exit animation). */
    fun remove(layer: Layer) {
        if (layers.indexOf(layer) > 0) layers.remove(layer)
    }

    fun dismissTop() {
        layers.lastOrNull()?.let { dismiss(it) }
    }

    /**
     * Pushes [screenId] (iOS: action transition → target screen default → app default). With
     * no action transition the target screen is loaded first to read its default.
     */
    fun push(stack: StackState, screenId: String, params: Map<String, Any?>, transition: ScreenTransition?) {
        if (transition != null) {
            stack.push(Route(screenId, params, ResolvedTransition.resolve(transition, app)))
            return
        }
        val load = screenTransition
        if (load == null || scope == null) {
            stack.push(Route(screenId, params, ResolvedTransition.resolve(app?.defaultTransition, app)))
            return
        }
        scope.launch {
            val screenDefault = runCatching { load(screenId) }.getOrNull()
            stack.push(Route(screenId, params, ResolvedTransition.resolve(screenDefault ?: app?.defaultTransition, app)))
        }
    }

    /**
     * `completeFlow` (iOS `FlowCoordinator.completeFlow`): one-way switch to the flow
     * [destination]. An unknown flow is an error and leaves navigation as it is; switching
     * to the current flow is ignored (a launch timer can fire more than once).
     */
    fun completeFlow(destination: String) {
        val flow = app?.navigation?.flows?.firstOrNull { it.id == destination }
        if (flow == null) {
            warn("Flow not found: $destination")
            return
        }
        if (destination == currentFlowId) return
        currentFlowId = destination
        layers.clear()
        layers += Layer(StackState(Route(flow.startScreen)), null)
    }
}

/** A screen's [Navigator]: pushes onto its own stack, presents and dismisses through the flow. */
internal class StackNavigator(
    private val flow: FlowController,
    private val layer: Layer,
    private val stack: StackState,
    private val tabs: TabState?,
) : Navigator {
    override fun push(screenId: String, params: Map<String, Any?>, transition: ScreenTransition?) =
        flow.push(stack, screenId, params, transition)

    override fun pop(toRoot: Boolean) {
        if (!stack.pop(toRoot)) flow.dismiss(layer)
    }

    override fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?, transition: ScreenTransition?) =
        flow.present(screenId, params, presentation, detents, grabber, transition)

    /** iOS `dismiss`: closes the presented modal this screen is in; on the base flow, pops. */
    override fun dismiss() {
        if (layer.presentation != null) flow.dismiss(layer) else pop(toRoot = false)
    }

    override fun selectTab(index: Int?, id: String?) {
        tabs?.select(index, id)
    }

    override fun completeFlow(destination: String) = flow.completeFlow(destination)
}
