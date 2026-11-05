package dev.app8.engine.navigation.transitions

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.style.ContinuousRoundedShape
import dev.app8.engine.style.toColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A custom modal (`mode: modal` transition; iOS `App8DimmingPresentationController` +
 * `PropertyTransitionAnimator` + `App8InteractiveTransitionDriver`):
 *
 * - the dimming backdrop fades with the presentation; a tap dismisses when `dismissOnTap`;
 * - the container is full screen, or sized and aligned by `presentation` (popup / sheet),
 *   clipped to its corner (only the leading edge's corners for top / bottom sheets) with an
 *   optional shadow, kept above the keyboard when `avoidsKeyboard`;
 * - the `to` keyframes play on present; dismissal plays them backwards (or the explicit
 *   reverse keyframes) and then removes the layer;
 * - `interactive`: a drag toward the dismissal edge tracks 1:1 and completes past
 *   `threshold` or `velocity`; a sized edge sheet dragged the other way stretches with
 *   resistance; system back (predictive) scrubs the dismissal.
 */
@Composable
internal fun CustomModal(
    transition: ResolvedTransition,
    dismissing: Boolean,
    onDismissRequest: () -> Unit,
    onRemoved: () -> Unit,
    backEnabled: Boolean,
    content: @Composable (sized: Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val appearance = LocalRenderEnv.current.appearance
    val scope = rememberCoroutineScope()
    // Forward progress of the presentation (0 = before present, 1 = presented).
    val progress = remember { Animatable(0f) }
    // Explicit reverse keyframes run 0 → 1 on dismissal.
    val exit = remember { Animatable(0f) }
    // Interactive drag along the dismissal edge, px (positive = toward the edge).
    val drag = remember { Animatable(0f) }
    val spec = transition.animation.toTransitionSpec()
    val explicitExit = transition.explicitReverse(true)
    val edge = transition.interactive?.edge ?: transition.edge
    val vertical = edge == ScreenTransition.Edge.top || edge == ScreenTransition.Edge.bottom
    val sign = if (edge == ScreenTransition.Edge.bottom || edge == ScreenTransition.Edge.trailing) 1f else -1f
    val sized = transition.presentation

    LaunchedEffect(Unit) { progress.animateTo(1f, spec) }
    LaunchedEffect(dismissing) {
        if (!dismissing) return@LaunchedEffect
        // A non-gesture dismissal settles with one monotonic ease-in-out (iOS).
        val out = tween<Float>(((transition.animation.duration.coerceIn(0.2, 0.45)) * 1000).roundToInt(), easing = FastOutSlowInEasing)
        if (explicitExit) exit.animateTo(1f, out) else progress.animateTo(0f, out)
        onRemoved()
    }

    PredictiveBackHandler(enabled = backEnabled && !dismissing) { events ->
        val start = progress.value
        try {
            events.collect { e -> progress.snapTo(start * (1f - e.progress)) }
            onDismissRequest()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(1f, tween(200)) }
            throw e
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPt = maxWidth.value.toDouble()
        val hPt = maxHeight.value.toDouble()
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val ld = LocalLayoutDirection.current
        val safe = WindowInsets.safeDrawing
        val keyboard = WindowInsets.ime.getBottom(density) / density.density
        val frame = sized?.frame(
            wPt, hPt,
            safe.getTop(density) / density.density.toDouble(),
            safe.getBottom(density) / density.density.toDouble(),
            safe.getLeft(density, ld) / density.density.toDouble(),
            safe.getRight(density, ld) / density.density.toDouble(),
            keyboard.toDouble(),
        )

        // Backdrop.
        val dim = transition.dimming
        if (dim != null) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = presentedFraction(progress.value, exit.value, explicitExit) * dragFade(drag.value, if (vertical) hPx else wPx) }
                    .background(dim.color.copy(alpha = dim.opacity))
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = dim.dismissOnTap && !dismissing) { onDismissRequest() },
            )
        }

        val shape = frame?.let { containerShape(sized.corner, sized.align, it) } ?: RectangleShape
        var box = if (frame != null) {
            Modifier.offset { IntOffset((frame.x * density.density).roundToInt(), (frame.y * density.density).roundToInt()) }
                .size(frame.width.dp, (frame.height + stretchPt(drag.value, density.density, vertical, sign, sized.align)).dp)
                .offset { IntOffset(0, if (sized.align == ScreenTransition.ModalAlign.bottom) -(stretchPt(drag.value, density.density, vertical, sign, sized.align) * density.density).roundToInt() else 0) }
        } else {
            Modifier.fillMaxSize()
        }
        box = box.graphicsLayer {
            val p = if (explicitExit && exit.value > 0f) transition.reverseFrom ?: ScreenTransition.Participant() else transition.to
            val f = if (explicitExit && exit.value > 0f) exit.value else progress.value
            // Translate percentages are of the transition container (the window).
            applyKeyframes(p, f, wPx, hPx)
            val d = drag.value.coerceAtLeast(0f) * sign
            if (vertical) translationY += d else translationX += d
        }
        if (frame != null && (sized.shadow != null || sized.defaultShadow)) {
            val layers = sized.shadow?.layers?.map { Triple(it.color.toColor(appearance).copy(alpha = it.opacity.toFloat()), it.radius.toFloat(), androidx.compose.ui.geometry.Offset(it.offset.x.toFloat(), it.offset.y.toFloat())) }
                ?: listOf(Triple(Color.Black.copy(alpha = 0.22f), 30f, androidx.compose.ui.geometry.Offset(0f, 12f)))
            box = box.drawBehind {
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }.asAndroidPath()
                for ((color, radius, offset) in layers) {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color.toArgb()
                        if (radius > 0f) maskFilter = android.graphics.BlurMaskFilter(radius * this@drawBehind.density / 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    drawIntoCanvas { c ->
                        c.nativeCanvas.save()
                        c.nativeCanvas.translate(offset.x * this@drawBehind.density, offset.y * this@drawBehind.density)
                        c.nativeCanvas.drawPath(path, paint)
                        c.nativeCanvas.restore()
                    }
                }
            }
        }
        val interactive = transition.interactive
        if (interactive?.enabled == true && !dismissing) {
            val dimension = if (vertical) (frame?.height?.times(density.density)?.toFloat() ?: hPx) else (frame?.width?.times(density.density)?.toFloat() ?: wPx)
            box = box.draggable(
                rememberDraggableState { delta -> scope.launch { drag.snapTo(drag.value + delta * sign) } },
                if (vertical) Orientation.Vertical else Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val v = velocity * sign / density.density
                    val past = drag.value / dimension >= interactive.threshold || v >= interactive.velocity
                    if (past && drag.value > 0f) {
                        // Finish the slide off the edge, then remove (the dismissal path).
                        drag.animateTo(if (vertical) hPx else wPx, tween(250, easing = FastOutSlowInEasing))
                        onRemoved()
                    } else {
                        drag.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
                    }
                },
            )
        }
        Box(box.clip(shape)) { content(frame != null) }
    }
}

/** How presented the modal is (drives the dimming): 1 shown, 0 gone. */
private fun presentedFraction(progress: Float, exit: Float, explicitExit: Boolean): Float =
    (if (explicitExit) 1f - exit else 1f).coerceIn(0f, 1f) * progress.coerceIn(0f, 1f)

/** Dimming fades as the card is dragged off. */
private fun dragFade(drag: Float, dimension: Float): Float =
    if (drag <= 0f || dimension <= 0f) 1f else (1f - drag / dimension).coerceIn(0f, 1f)

/** Elastic growth (points) of a sized edge sheet dragged away from its edge. */
private fun stretchPt(drag: Float, density: Float, vertical: Boolean, sign: Float, align: ScreenTransition.ModalAlign): Float {
    if (drag >= 0f || !vertical) return 0f
    if (align != ScreenTransition.ModalAlign.bottom && align != ScreenTransition.ModalAlign.top) return 0f
    val pull = -drag / density
    // Rubber band: grows ever slower (UIScrollView-like resistance).
    return (pull * 0.55f) / (1f + pull / 400f)
}

private fun containerShape(corner: Corner?, align: ScreenTransition.ModalAlign, frame: FrameD): Shape {
    corner ?: return RectangleShape
    val r = when (val radius = corner.radius) {
        is Corner.Radius.Fixed -> radius.value
        is Corner.Radius.Fraction -> radius.value * minOf(frame.width, frame.height)
        Corner.Radius.Capsule -> minOf(frame.width, frame.height) / 2
    }.dp
    val zero = 0.dp
    return when (align) {
        // Sheets / banners round only the edge facing the content.
        ScreenTransition.ModalAlign.bottom -> ContinuousRoundedShape(r, r, zero, zero)
        ScreenTransition.ModalAlign.top -> ContinuousRoundedShape(zero, zero, r, r)
        else -> ContinuousRoundedShape(r)
    }
}
