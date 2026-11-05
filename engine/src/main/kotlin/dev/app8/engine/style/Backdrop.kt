package dev.app8.engine.style

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.Modifier.Node

/**
 * What a background blur can sample: the content of one subtree, recorded into a
 * [GraphicsLayer] each time it draws. Materials drawn above it (outside the subtree)
 * draw that layer blurred. The engine puts screen content in the source and floating
 * chrome / overlays outside it. See `VisualEffects.kt` for why this is needed.
 */
@Stable
internal class App8Backdrop {
    internal var layer: GraphicsLayer? = null
    internal var positionInRoot: Offset = Offset.Zero
}

/**
 * The backdrop materials in this subtree may sample, or `null`. [App8BackdropSource]
 * clears it for its own content so nothing samples a layer that contains itself.
 */
internal val LocalApp8Backdrop: ProvidableCompositionLocal<App8Backdrop?> = staticCompositionLocalOf { null }

@Composable
internal fun rememberApp8Backdrop(): App8Backdrop = remember { App8Backdrop() }

/** Composes [content] as the backdrop source: records it, and hides the backdrop from it. */
@Composable
internal fun App8BackdropSource(backdrop: App8Backdrop, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    CompositionLocalProvider(LocalApp8Backdrop provides null) {
        content(modifier.then(BackdropSourceElement(backdrop)))
    }
}

private data class BackdropSourceElement(val backdrop: App8Backdrop) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop)
    override fun update(node: BackdropSourceNode) {
        node.backdrop = backdrop
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "app8BackdropSource"
    }
}

private class BackdropSourceNode(var backdrop: App8Backdrop) :
    Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var layer: GraphicsLayer? = null

    override fun onAttach() {
        layer = currentValueOf(LocalGraphicsContext).createGraphicsLayer().also { backdrop.layer = it }
    }

    override fun onDetach() {
        layer?.let { currentValueOf(LocalGraphicsContext).releaseGraphicsLayer(it) }
        if (backdrop.layer === layer) backdrop.layer = null
        layer = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        backdrop.positionInRoot = coordinates.positionInRoot()
    }

    override fun ContentDrawScope.draw() {
        val l = layer ?: return drawContent()
        backdrop.layer = l
        l.record { this@draw.drawContent() }
        drawLayer(l)
    }
}
