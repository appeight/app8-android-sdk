package dev.app8.engine.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import dev.app8.engine.core.model.Animation
import dev.app8.engine.render.collection.LocalCellOnScreen
import kotlin.math.roundToInt

/** The resolved inline descriptor when it loops (`options: ["repeat"]`), else null. */
internal fun Animation?.repeatingInline(runtime: dev.app8.engine.runtime.App8Runtime): Animation.Inline? {
    val inline = this?.inline { id -> runtime.app?.animations?.firstNotNullOfOrNull { a -> (a as? Animation.Inline)?.takeIf { it.id == id } } }
        ?: return null
    return inline.takeIf { Animation.Option.repeat in it.options }
}

/** A loop of [inline]: a duration-based spec (a spring loops on its duration, eased out). */
internal fun Animation.Inline.loopTween(): DurationBasedAnimationSpec<Float> {
    val ms = (duration * 1000).roundToInt().coerceAtLeast(1)
    val easing = when (val t = timing) {
        is Animation.Timing.SpringTiming -> LinearOutSlowInEasing
        is Animation.Timing.CubicBezier -> CubicBezierEasing(t.x1.toFloat(), t.y1.toFloat(), t.x2.toFloat(), t.y2.toFloat())
        is Animation.Timing.Curve -> when (t.curve) {
            Animation.NamedCurve.linear -> LinearEasing
            Animation.NamedCurve.easeIn -> FastOutLinearInEasing
            Animation.NamedCurve.easeOut -> LinearOutSlowInEasing
            Animation.NamedCurve.easeInOut -> FastOutSlowInEasing
        }
    }
    return tween(ms, 0, easing)
}

/**
 * A view property that animates to [target] with the node's DSL [animation]. With
 * `repeat` it loops from its start value to [target] forever (`autoreverse`: back and
 * forth), as the spec's "Repeating animations": the start is the previous target after
 * a change, and [rest] when the view is composed again (a reused cell, a screen built
 * again). The value is a plain number (degrees for a rotation), so a full turn spins.
 * The loop pauses while its cell is kept composed off screen and starts over when it
 * comes back.
 */
@Composable
internal fun animatedDslFloat(target: Float, animation: Animation?, rest: Float, node: RenderNode, label: String): State<Float> {
    val loop = animation?.repeatingInline(node.runtime)
    if (loop == null) return animateFloatAsState(target, animation.toSpec(node), label = label)
    // A screen under a sheet is still on screen, so only a cell kept composed off screen pauses;
    // screens and cells that leave the composition start over when they come back.
    val visible = LocalCellOnScreen.current
    val holder = remember { RepeatHolder(rest) }
    if (holder.target != target) {
        holder.from = holder.target ?: rest
        holder.target = target
    }
    val from = holder.from
    LaunchedEffect(target, from, visible, loop) {
        val anim = Animatable(from)
        if (from == target || !visible) {
            holder.value.floatValue = target
            return@LaunchedEffect
        }
        holder.value.floatValue = from
        val delayMs = (loop.delay * 1000).roundToInt()
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs.toLong())
        anim.animateTo(
            target,
            infiniteRepeatable(
                loop.loopTween(),
                if (Animation.Option.autoreverse in loop.options) RepeatMode.Reverse else RepeatMode.Restart,
                StartOffset(0),
            ),
        ) {
            holder.value.floatValue = value
        }
    }
    return holder.value
}

private class RepeatHolder(rest: Float) {
    var target: Float? = null
    var from: Float = rest
    val value = mutableFloatStateOf(rest)
}
