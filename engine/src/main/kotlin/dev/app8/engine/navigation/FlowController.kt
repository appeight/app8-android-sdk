package dev.app8.engine.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.app8.engine.actions.Navigator
import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.App
import java.util.concurrent.atomic.AtomicLong

/** One screen on a back stack. [id] is unique per push, so the same screen can be pushed twice. */
internal data class Route(val screenId: String, val params: Map<String, Any?> = emptyMap(), val id: Long = nextId.incrementAndGet()) {
    private companion object {
        val nextId = AtomicLong()
    }
}

/** A back stack (UINavigationController). */
@Stable
internal class StackState(root: Route) {
    val entries: SnapshotStateList<Route> = mutableStateListOf(root)
}

/** The base flow or a presented modal (sheet / full screen), each with its own stack. */
@Stable
internal class Layer(
    val stack: StackState,
    /** Null for the base layer. */
    val presentation: Action.PresentationStyle?,
    val detents: List<Action.SheetDetent>? = null,
    val grabber: Boolean? = null,
)

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
internal class FlowController(private val app: App?, startScreen: String, params: Map<String, Any?> = emptyMap()) {
    val layers: SnapshotStateList<Layer> = mutableStateListOf(Layer(StackState(Route(startScreen, params)), null))

    fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?) {
        layers += Layer(StackState(Route(screenId, params)), presentation, detents, grabber)
    }

    fun dismiss(layer: Layer) {
        if (layers.indexOf(layer) > 0) layers.remove(layer)
    }

    fun dismissTop() {
        if (layers.size > 1) layers.removeAt(layers.lastIndex)
    }

    /** `completeFlow`: [destination] is a flow id (its start screen) or a screen id. */
    fun completeFlow(destination: String) {
        val screen = app?.navigation?.flows?.firstOrNull { it.id == destination }?.startScreen ?: destination
        layers.clear()
        layers += Layer(StackState(Route(screen)), null)
    }
}

/** A screen's [Navigator]: pushes onto its own stack, presents and dismisses through the flow. */
internal class StackNavigator(
    private val flow: FlowController,
    private val layer: Layer,
    private val stack: StackState,
    private val tabs: TabState?,
) : Navigator {
    override fun push(screenId: String, params: Map<String, Any?>) {
        stack.entries += Route(screenId, params)
    }

    override fun pop(toRoot: Boolean) {
        val entries = stack.entries
        when {
            entries.size > 1 && toRoot -> entries.removeRange(1, entries.size)
            entries.size > 1 -> entries.removeAt(entries.lastIndex)
            else -> flow.dismiss(layer)
        }
    }

    override fun present(screenId: String, params: Map<String, Any?>, presentation: Action.PresentationStyle, detents: List<Action.SheetDetent>?, grabber: Boolean?) =
        flow.present(screenId, params, presentation, detents, grabber)

    /** iOS `dismiss`: closes the presented modal this screen is in; on the base flow, pops. */
    override fun dismiss() {
        if (layer.presentation != null) flow.dismiss(layer) else pop(toRoot = false)
    }

    override fun selectTab(index: Int?, id: String?) {
        tabs?.select(index, id)
    }

    override fun completeFlow(destination: String) = flow.completeFlow(destination)
}
