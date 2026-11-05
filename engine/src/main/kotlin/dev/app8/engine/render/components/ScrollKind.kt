package dev.app8.engine.render.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import dev.app8.engine.core.layout.ScrollDirection
import dev.app8.engine.core.layout.ScrollSpec
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.ScrollViewProperties
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalSolved
import dev.app8.engine.render.NodeView
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.px
import kotlin.math.roundToInt

/**
 * `scrollView` (UIScrollView). The solver lays the children out in a content view as wide
 * (vertical) or tall (horizontal) as the scroll view; Compose scrolls it, with Android's
 * stretch overscroll. `contentInsetAdjustment: automatic` (the default) insets the content
 * by the part of the safe area the scroll view covers, like `adjustedContentInset`.
 */
internal object ScrollKind : ComponentKind {
    override val clipsToMaterial: Boolean get() = true

    private fun props(node: RenderNode) = node.content.properties as? ScrollViewProperties ?: ScrollViewProperties()

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits = LayoutTraits(
        scroll = ScrollSpec(if (props(node).direction == ScrollViewProperties.Direction.horizontal) ScrollDirection.HORIZONTAL else ScrollDirection.VERTICAL),
    )

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = props(node)
        val vertical = p.direction != ScrollViewProperties.Direction.horizontal
        val state = rememberScrollState()
        val solved = LocalSolved.current
        val density = LocalDensity.current.density
        ScrollEvents(node, p, state, vertical, density)
        val scrollModifier = if (vertical) Modifier.verticalScroll(state) else Modifier.horizontalScroll(state)
        Box(modifier.clipToBounds()) {
            Layout(
                content = { for (child in node.children) key(child.key) { NodeView(child) } },
                modifier = scrollModifier,
            ) { measurables, constraints ->
                val s = solved.value
                val d = density
                val self = s.absolute[node.key]
                val root = s.absolute[node.session.screenId]
                val content = s.contentSizes[node.key]
                // adjustedContentInset: explicit contentInset + covered safe area (automatic).
                val inset = p.contentInset
                var top = inset?.top ?: 0.0
                var bottom = inset?.bottom ?: 0.0
                var left = inset?.left ?: 0.0
                var right = inset?.right ?: 0.0
                if (p.contentInsetAdjustment != ScrollViewProperties.ContentInsetAdjustment.never && self != null && root != null) {
                    if (vertical) {
                        top += (root.y + s.safeArea.top - self.y).coerceIn(0.0, self.height)
                        bottom += (self.maxY - (root.maxY - s.safeArea.bottom)).coerceIn(0.0, self.height)
                    }
                }
                val viewportW = if (constraints.hasBoundedWidth) constraints.maxWidth else ((self?.width ?: 0.0) * d).roundToInt()
                val viewportH = if (constraints.hasBoundedHeight) constraints.maxHeight else ((self?.height ?: 0.0) * d).roundToInt()
                val contentW = if (vertical) viewportW else (((content?.width ?: 0.0) + left + right) * d).roundToInt()
                val contentH = if (vertical) (((content?.height ?: 0.0) + top + bottom) * d).roundToInt() else viewportH
                val placeables = measurables.map { m ->
                    val f = s.local[m.layoutId as? String]
                    m.measure(Constraints.fixed(((f?.width ?: 0.0) * d).roundToInt().coerceAtLeast(0), ((f?.height ?: 0.0) * d).roundToInt().coerceAtLeast(0))) to f
                }
                layout(contentW.coerceAtLeast(if (vertical) 0 else viewportW), contentH.coerceAtLeast(if (vertical) viewportH else 0)) {
                    for ((pl, f) in placeables) if (f != null) pl.place(px(f.x + left, d), px(f.y + top, d))
                }
            }
        }
    }

    /** `outputVariable` (offset in points) and `onScrollThreshold` (fires once each time the offset passes it). */
    @Composable
    private fun ScrollEvents(node: RenderNode, p: ScrollViewProperties, state: ScrollState, vertical: Boolean, density: Float) {
        val output = p.outputVariable
        val threshold = p.scrollThreshold
        val hasThresholdAction = node.content.actions?.containsKey(ActionTrigger.onScrollThreshold) == true ||
            node.content.analytics?.containsKey(ActionTrigger.onScrollThreshold) == true
        if (output == null && (threshold == null || !hasThresholdAction)) return
        val dispatcher = LocalDispatcher.current
        LaunchedEffect(node, state) {
            var above = false
            snapshotFlow { state.value / density }.collect { offset ->
                if (output != null && node.scope.store.hasVariable(output)) {
                    runCatching { node.scope.store.setValue(output, offset.toDouble()) }
                }
                if (threshold != null && hasThresholdAction) {
                    val now = offset >= threshold
                    if (now && !above) dispatcher.dispatchTrigger(node, ActionTrigger.onScrollThreshold)
                    above = now
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION") vertical
    }
}
