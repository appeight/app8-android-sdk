package dev.app8.engine.render.components

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import dev.app8.engine.core.model.NativeContent
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.nativecomponent.App8NativeContext
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * `native`: the host's [dev.app8.engine.nativecomponent.App8NativeComponent] registered for
 * the DSL `module`, drawn in the component's layout frame. Unregistered: the `fallback`
 * subtree (built as the node's only child), or an empty view and a `native.module.missing`
 * warning when there is none.
 */
internal object NativeKind : ComponentKind {
    /** The host view owns its touches. */
    override val handlesTaps: Boolean get() = true

    override fun isHidden(node: RenderNode): Boolean = node.bool((node.content as? NativeContent)?.properties?.isHidden) == true

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val content = node.content as? NativeContent ?: return Box(modifier)
        val module = content.properties.module
        val component = node.runtime.nativeComponents[module]
        val placement = remember(node) { Placement() }
        val visible = rememberNativeVisible(node, placement)
        Box(modifier.then(Modifier.onPlaced { placement.placed = true })) {
            when {
                component != null -> {
                    val dispatcher = LocalDispatcher.current
                    val emit = remember(node, dispatcher) {
                        { event: String, payload: Map<String, Any?> ->
                            onMain { dispatcher.dispatchNativeEvent(node, event, payload) }
                        }
                    }
                    val app = node.runtime.app
                    val context = App8NativeContext(
                        // Tracked reads: a variable change recomposes with fresh props.
                        props = resolveProps(node, content),
                        emit = emit,
                        appId = app?.id ?: app?.bundleId ?: "",
                        screenId = node.session.screenId,
                        module = module,
                        isVisible = visible,
                    )
                    component.Content(context, Modifier.fillMaxSize())
                }
                node.children.isNotEmpty() -> ChildrenLayout(node, Modifier.fillMaxSize())
                else -> LaunchedEffect(node) {
                    node.runtime.diagnostics.warn("native.module.missing", "no App8NativeComponent for '$module' and no fallback; '${node.id}' renders empty")
                }
            }
        }
    }

    /** Whether the component has been placed in its composition (false on first composition). */
    @androidx.compose.runtime.Stable
    class Placement {
        var placed: Boolean by androidx.compose.runtime.mutableStateOf(false)
    }

    /**
     * The component's visibility (app8-dsl `components/native.md#visibility`): navigation's and
     * the lifecycle's part ([dev.app8.engine.navigation.LocalScreenVisible]), the enclosing
     * collection cells on screen ([LocalCellOnScreen]), and placed at least once (so the first
     * composition reports `false`). A `scrollView` scrolled away does not change it. Runs its `appear` / `disappear` actions once per change
     * (`disappear` also when the component leaves the composition while visible).
     */
    @Composable
    private fun rememberNativeVisible(node: RenderNode, placement: Placement): Boolean {
        val value = dev.app8.engine.navigation.LocalScreenVisible.current && dev.app8.engine.render.collection.LocalCellOnScreen.current && placement.placed
        val dispatcher = LocalDispatcher.current
        val reported = remember(node) { booleanArrayOf(false) }
        LaunchedEffect(node, value) {
            if (reported[0] == value) return@LaunchedEffect
            reported[0] = value
            dispatcher.dispatchNativeVisibility(node, value)
        }
        androidx.compose.runtime.DisposableEffect(node) {
            onDispose {
                if (reported[0]) {
                    reported[0] = false
                    dispatcher.dispatchNativeVisibility(node, false)
                }
            }
        }
        return value
    }

    /** `props` with `{{…}}` strings resolved (nested arrays and objects too). */
    fun resolveProps(node: RenderNode, content: NativeContent, ctx: VariableContext = node.scope.tracked): Map<String, Any?> =
        content.properties.props.mapValues { (_, v) -> resolveValue(node, v, ctx) }

    private fun resolveValue(node: RenderNode, element: JsonElement, ctx: VariableContext): Any? = when (element) {
        is JsonNull -> null
        is JsonArray -> element.map { resolveValue(node, it, ctx) }
        is JsonObject -> element.mapValues { (_, v) -> resolveValue(node, v, ctx) }
        else -> {
            val v = Values.fromJson(element)
            if (v is String && node.runtime.propertyResolver.containsExpression(v)) {
                try {
                    node.runtime.propertyResolver.resolve(v, ctx).takeUnless { it === Values.Null }
                } catch (e: Exception) {
                    node.runtime.diagnostics.warn("expression", "'$v' in ${node.key}: ${e.message}")
                    null
                }
            } else {
                v
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        val main = Looper.getMainLooper()
        if (main == null || Looper.myLooper() == main) block() else Handler(main).post(block)
    }
}
