package dev.app8.engine.render.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.layout.StackAlignment
import dev.app8.engine.core.layout.StackAxis
import dev.app8.engine.core.layout.StackDistribution
import dev.app8.engine.core.layout.StackSpec
import dev.app8.engine.core.model.StackViewProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.string

/** `stackView` (UIStackView): the solver arranges the children; this draws the container. */
internal object StackKind : ComponentKind {
    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val p = node.content.properties as? StackViewProperties ?: StackViewProperties()
        return LayoutTraits(
            stack = StackSpec(
                axis = if (p.axis == "horizontal") StackAxis.HORIZONTAL else StackAxis.VERTICAL,
                spacing = p.spacing ?: 0.0,
                alignment = StackAlignment.parse(p.alignment),
                distribution = StackDistribution.parse(p.distribution),
            ),
        )
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? StackViewProperties
        var m = modifier
        val radius = p?.cornerRadius ?: 0.0
        val bg = p?.backgroundColor?.let { node.string(it) }?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }
        // `cornerRadius` clips the arranged children, with or without a background (docs: "clips
        // children to rounded bounds"). The clip follows any material shadow in [modifier], so the
        // shadow still draws outside the bounds, as iOS clips its inner contentView.
        if (radius > 0) m = m.clip(RoundedCornerShape(radius.toFloat().dp))
        if (bg != null) m = m.background(bg)
        Box(m) { ChildrenLayout(node, Modifier.fillMaxSize()) }
    }
}
