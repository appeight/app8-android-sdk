package dev.app8.engine.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.ComponentState
import dev.app8.engine.core.model.EntityContent
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.Trigger
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.core.model.style.ViewStyle
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * Android press feedback for DSL views and collection cells (a Material bounded ripple), clipped to the view's own corner shape and drawn over its content.
 *
 * - The colour is the scheme's `onSurface` at the M3 pressed state layer opacity
 *   ([PRESSED_ALPHA], `StateTokens.PressedStateLayerOpacity`).
 * - A component with its own touch state (a `touchDown` trigger) keeps it and gets no
 *   ripple, as on iOS; an opacity-only touch state is not a brand look, so the ripple joins it.
 */
internal object PressFeedback {
    /** M3 `StateTokens.PressedStateLayerOpacity`. */
    const val PRESSED_ALPHA = 0.10f

    /** The ripple grows over this long (Material ripple: ~225ms to full radius). */
    const val RIPPLE_EXPAND_MS = 225

    /** The ripple fades in within a few frames: `DurationShort1`. */
    const val LAYER_IN_MS = 50

    /** Release: the state layer and ripple fade out (`DurationShort3`). */
    const val FADE_OUT_MS = 150

    /**
     * Whether a component should show the engine's press feedback, given its content: false
     * when the DSL defines a touch state of its own that changes more than opacity.
     */
    fun wanted(content: EntityContent): Boolean {
        val triggers = (content as? StatefulContent)?.triggers ?: return true
        val name = triggers[Trigger.touchDown] ?: return true
        @Suppress("UNCHECKED_CAST")
        val state = (content as? dev.app8.engine.core.model.ComponentContent<Any, Any>)?.states?.get(name) ?: return true
        return isOpacityOnly(state)
    }

    /** A state that only changes `alpha` (property or style): no other look of its own. */
    fun isOpacityOnly(state: ComponentState<*, *>): Boolean {
        if (state.layout != null || !state.childStates.isNullOrEmpty() || state.isHidden != null) return false
        val props = state.properties
        if (props != null && !(props is ViewProperties && props == ViewProperties(alpha = props.alpha))) return false
        val style = state.style
        if (style != null && !(style is ViewStyle && style == ViewStyle(alpha = style.alpha, alphaExpression = style.alphaExpression))) return false
        return props != null || style != null
    }
}

/** The press indication with [shape] in [color]. */
@Stable
internal class PressIndication(private val shape: Shape, private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, shape, color)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.shape == shape && other.color == color

    override fun hashCode(): Int = 31 * shape.hashCode() + color.hashCode()
}

private const val RIPPLE_START = 0.3f

private class PressNode(
    private val source: InteractionSource,
    private val shape: Shape,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {
    private val layer = Animatable(0f)
    private val radius = Animatable(0f)
    private var origin: Offset? = null
    private val path = Path()

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        origin = i.pressPosition
                        coroutineScope.launch { layer.animateTo(1f, tween(PressFeedback.LAYER_IN_MS, easing = LinearEasing)) { invalidateDraw() } }
                        coroutineScope.launch {
                            radius.snapTo(0f)
                            radius.animateTo(1f, tween(PressFeedback.RIPPLE_EXPAND_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { invalidateDraw() }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> coroutineScope.launch {
                        // Let the ripple reach the edge before it fades (a quick tap still shows it).
                        if (radius.isRunning) radius.animateTo(1f, tween(PressFeedback.FADE_OUT_MS)) { invalidateDraw() }
                        layer.animateTo(0f, tween(PressFeedback.FADE_OUT_MS, easing = LinearEasing)) { invalidateDraw() }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = layer.value
        if (a <= 0f) return
        val outline = shape.createOutline(size, layoutDirection, this)
        path.reset()
        path.addOutline(outline)
        clipPath(path) {
            // The ripple from the touch point, onSurface at the pressed opacity. Like Material's it
            // starts at 30% of its final radius, so the press shows on the first frame.
            val o = origin ?: center
            val maxR = max(hypot(o.x, o.y), max(hypot(size.width - o.x, o.y), max(hypot(o.x, size.height - o.y), hypot(size.width - o.x, size.height - o.y))))
            drawCircle(color.copy(alpha = color.alpha * PressFeedback.PRESSED_ALPHA * a), radius = maxR * (RIPPLE_START + (1f - RIPPLE_START) * radius.value), center = o)
        }
    }
}

/** The press indication for a view with [cornerRadius] (points), in the theme's `onSurface`. */
@Composable
internal fun pressIndication(cornerRadius: Double): PressIndication {
    val color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val shape = if (cornerRadius > 0) RoundedCornerShape(cornerRadius.toFloat().dp) else androidx.compose.ui.graphics.RectangleShape
    return androidx.compose.runtime.remember(shape, color) { PressIndication(shape, color) }
}
