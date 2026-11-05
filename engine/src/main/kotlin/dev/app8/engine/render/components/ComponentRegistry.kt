package dev.app8.engine.render.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.IconProperties
import dev.app8.engine.core.model.LabelProperties
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool

/**
 * How one component type renders (iOS `ComponentRegistry` + the `C<Name>View` classes).
 * Universal behavior (alpha, transforms, material, taps, events) is applied by
 * [dev.app8.engine.render.NodeView] around [Render].
 */
internal interface ComponentKind {
    /** Layout participation: intrinsic size, stack/scroll specs, hugging. Called inside the layout derived state. */
    fun traits(node: RenderNode, env: RenderEnv): LayoutTraits = LayoutTraits.NONE

    /** `isHidden` (expression), for the kinds that have it. */
    fun isHidden(node: RenderNode): Boolean = when (val p = node.content.properties) {
        is ViewProperties -> node.bool(p.isHidden) == true
        is LabelProperties -> node.bool(p.isHidden) == true
        is IconProperties -> node.bool(p.isHidden) == true
        else -> false
    }

    /** Clip content and children to the material's corner (UIView `masksToBounds` with a corner). */
    val clipsToMaterial: Boolean get() = true

    /** True when the component wires its own tap handling (buttons). */
    val handlesTaps: Boolean get() = false

    /** Draws the component in [modifier] (which carries its layout id and universal styling). */
    @Composable
    fun Render(node: RenderNode, modifier: Modifier)
}

/** A plain container: `view`, `screen`, and the base of most components. */
internal object ViewKind : ComponentKind {
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        Box(modifier) { ChildrenLayout(node, Modifier.fillMaxSize()) }
    }
}

/**
 * A component type this renderer does not draw yet: an empty view in its frame, plus a
 * `component.placeholder` diagnostic, so screens still lay out around it.
 */
internal object PlaceholderKind : ComponentKind {
    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        node.runtime.diagnostics.warn("component.placeholder", "'${node.type.raw}' is not rendered on Android yet (${node.id})")
        return LayoutTraits(opaque = true)
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        Box(modifier)
    }
}

internal object ComponentRegistry {
    private val kinds: Map<ComponentType.Key, ComponentKind> = mapOf(
        ComponentType.Key.screen to ViewKind,
        ComponentType.Key.view to ViewKind,
        ComponentType.Key.stackView to StackKind,
        ComponentType.Key.label to LabelKind,
        ComponentType.Key.button to ButtonKind,
        ComponentType.Key.image to ImageKind,
        ComponentType.Key.icon to IconKind,
        ComponentType.Key.scrollView to ScrollKind,
        ComponentType.Key.shape to ShapeKind,
        ComponentType.Key.pageControl to PageControlKind,
        ComponentType.Key.textField to TextInputKind(multiline = false),
        ComponentType.Key.textView to TextInputKind(multiline = true),
        ComponentType.Key.toggle to ToggleKind,
        ComponentType.Key.slider to SliderKind,
        ComponentType.Key.picker to PickerKind,
        ComponentType.Key.activityIndicator to ActivityIndicatorKind,
        ComponentType.Key.shimmer to ShimmerKind,
        ComponentType.Key.datePicker to DatePickerKind,
        ComponentType.Key.video to VideoKind,
        ComponentType.Key.map to MapKind,
    )

    fun kind(node: RenderNode): ComponentKind = node.typeKey?.let { kinds[it] } ?: PlaceholderKind

    /** Types rendered natively (others are placeholders). */
    val supported: Set<ComponentType.Key> get() = kinds.keys
}
