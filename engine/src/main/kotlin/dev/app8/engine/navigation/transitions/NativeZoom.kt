package dev.app8.engine.navigation.transitions

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.draggable2D
import androidx.compose.foundation.gestures.rememberDraggable2DState
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.app8.engine.core.model.ScreenTransition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * The native zoom (`preset: "zoom"` + `sourceKey`, app8-dsl `docs/transitions.md`): the
 * incoming screen grows out of a keyed element on the presenting screen and shrinks back into
 * it. Android draws the container transform itself (the screen's bounds morph from the source
 * rect with the source's corner, the content scaled to fill them), so push and custom modal
 * presentation share one implementation and the gestures own the progress.
 *
 * [initialKey] is the `sourceKey` evaluated in the action's scope; [rekey] evaluates it again in
 * the presenting screen's scope when the dismissal starts (the current gallery page).
 * [session] identifies the presenting screen, whose source elements register in [ZoomSources].
 */
@Stable
internal class ZoomOrigin(
    val session: Any,
    initialKey: String,
    private val rekey: () -> String?,
    val dismissGesture: ScreenTransition.DismissGesture,
) {
    var key: String by mutableStateOf(initialKey)
        private set

    /**
     * Dismissal starts: the key may now name another element. A key that only resolves in the
     * action's scope (`item` of a cell) gives nothing on the screen; then the first key stays.
     */
    fun refreshKey(sources: ZoomSources) {
        rekey()?.takeIf { it.isNotEmpty() && sources.frame(session, it) != null }?.let { key = it }
    }
}

/** A source element's window bounds (px) and corner radius (px). */
internal data class ZoomSourceFrame(val bounds: Rect, val cornerPx: Float)

/**
 * Elements with `content.transition.key` report where they are, per presenting screen, so a
 * native zoom can find its source. The zoom hides its source while the incoming screen is up.
 */
internal class ZoomSources {
    private val frames = mutableStateMapOf<Pair<Any, String>, ZoomSourceFrame>()
    private val hidden = mutableStateMapOf<Pair<Any, String>, Int>()

    fun report(session: Any, key: String, frame: ZoomSourceFrame) {
        frames[session to key] = frame
    }

    fun remove(session: Any, key: String) {
        frames.remove(session to key)
    }

    fun frame(session: Any, key: String): ZoomSourceFrame? = frames[session to key]

    fun isHidden(session: Any, key: String): Boolean = (hidden[session to key] ?: 0) > 0

    fun hide(session: Any, key: String) {
        val k = session to key
        hidden[k] = (hidden[k] ?: 0) + 1
    }

    fun unhide(session: Any, key: String) {
        val k = session to key
        val n = (hidden[k] ?: 0) - 1
        if (n <= 0) hidden.remove(k) else hidden[k] = n
    }
}

/** Registers a keyed element as a zoom source, and hides it while its zoom is presented. */
@Composable
internal fun Modifier.zoomSource(sources: ZoomSources, session: Any, key: String?, cornerPx: Float): Modifier {
    if (key.isNullOrEmpty()) return this
    DisposableEffect(sources, session, key) { onDispose { sources.remove(session, key) } }
    return this
        .onGloballyPositioned { c -> if (c.isAttached) sources.report(session, key, ZoomSourceFrame(c.boundsInRoot(), cornerPx)) }
        .graphicsLayer { if (sources.isHidden(session, key)) alpha = 0f }
}

/** Hides the origin's current source element while [active]. */
@Composable
internal fun HideZoomSource(sources: ZoomSources, origin: ZoomOrigin, active: Boolean = true) {
    val key = origin.key
    DisposableEffect(sources, origin, key, active) {
        if (active) sources.hide(origin.session, key)
        onDispose { if (active) sources.unhide(origin.session, key) }
    }
}

/** Default native zoom timing (container transform): a critically damped spring of about 0.45 s. */
internal val NATIVE_ZOOM_SPRING = spring<Float>(dampingRatio = 1f, stiffness = 260f, visibilityThreshold = 0.001f)

/**
 * The container transform at [progress] (0 = the source rect, 1 = the screen's own bounds
 * [own], both in window px), plus an interactive [drag] offset (px) that shrinks the screen as
 * it follows the finger. Without a source the screen cross-fades.
 */
internal fun GraphicsLayerScope.applyNativeZoom(progress: Float, own: Rect, source: ZoomSourceFrame?, drag: Offset = Offset.Zero) {
    val f = progress.coerceIn(0f, 1.2f)
    transformOrigin = TransformOrigin(0f, 0f)
    val w = own.width
    val h = own.height
    if (w <= 0f || h <= 0f) return
    if (source == null) {
        alpha = progress.coerceIn(0f, 1f)
        applyDrag(drag, w, h)
        return
    }
    val s = source.bounds
    fun lerp(a: Float, b: Float) = a + (b - a) * f
    var left = lerp(s.left, own.left)
    var top = lerp(s.top, own.top)
    var rw = lerp(s.width, w).coerceAtLeast(1f)
    var rh = lerp(s.height, h).coerceAtLeast(1f)
    // Interactive drag: the card follows the finger and shrinks with the distance.
    if (drag != Offset.Zero) {
        val shrink = (1f - hypot(drag.x, drag.y) / max(w, h) * 0.6f).coerceIn(0.4f, 1f)
        val cx = left + rw / 2 + drag.x
        val cy = top + rh / 2 + drag.y
        rw *= shrink
        rh *= shrink
        left = cx - rw / 2
        top = cy - rh / 2
    }
    // Content scaled to fill the morphing bounds (`scaleInSharedContentToBounds`), centered.
    val scale = max(rw / w, rh / h)
    val tx = left - own.left + (rw - scale * w) / 2
    val ty = top - own.top + (rh - scale * h) / 2
    scaleX = scale
    scaleY = scale
    translationX = tx
    translationY = ty
    val clipLeft = (left - own.left - tx) / scale
    val clipTop = (top - own.top - ty) / scale
    val radius = (source.cornerPx * (1f - f.coerceIn(0f, 1f)) + (if (drag != Offset.Zero) 24f * density else 0f)) / scale
    shape = ZoomClipShape(Rect(clipLeft, clipTop, clipLeft + rw / scale, clipTop + rh / scale), radius)
    clip = true
    // The incoming content fades in over the first part of the growth.
    alpha = (progress * 4f).coerceIn(0f, 1f)
}

private fun GraphicsLayerScope.applyDrag(drag: Offset, w: Float, h: Float) {
    if (drag == Offset.Zero) return
    val shrink = (1f - hypot(drag.x, drag.y) / max(w, h) * 0.6f).coerceIn(0.4f, 1f)
    scaleX = shrink
    scaleY = shrink
    translationX = drag.x + w * (1 - shrink) / 2
    translationY = drag.y + h * (1 - shrink) / 2
}

private class ZoomClipShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius.coerceAtLeast(0f))))
}

/**
 * A custom modal presented with the native zoom: grows out of its source, shrinks back on
 * dismissal. System back (predictive) scrubs the shrink. `dismissGesture: any` drags in any
 * direction (`down`: vertically) with the screen following the finger and shrinking; past 25%
 * of the screen or with a fling it completes into the source, otherwise it springs back.
 * Content keeps the pans it can use (a pager's horizontal swipe, a zoomed image's pan).
 */
@Composable
internal fun NativeZoomModal(
    origin: ZoomOrigin,
    sources: ZoomSources,
    dismissing: Boolean,
    onDismissRequest: () -> Unit,
    onRemoved: () -> Unit,
    backEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var own by remember { mutableStateOf(Rect.Zero) }
    var closing by remember { mutableStateOf(false) }
    val acc = remember { floatArrayOf(0f, 0f) }
    HideZoomSource(sources, origin)

    LaunchedEffect(Unit) { progress.animateTo(1f, NATIVE_ZOOM_SPRING) }

    suspend fun shrinkAway() {
        closing = true
        launchJoin(
            { progress.animateTo(0f, NATIVE_ZOOM_SPRING) },
            { dragX.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) },
            { dragY.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) },
        )
        onRemoved()
    }

    LaunchedEffect(dismissing) {
        if (!dismissing || closing) return@LaunchedEffect
        origin.refreshKey(sources)
        shrinkAway()
    }

    PredictiveBackHandler(enabled = backEnabled && !dismissing && !closing) { events ->
        origin.refreshKey(sources)
        val start = progress.value
        try {
            events.collect { e -> progress.snapTo(start * (1f - 0.35f * e.progress)) }
            closing = true
            progress.animateTo(0f, NATIVE_ZOOM_SPRING)
            onRemoved()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(1f, tween(200)) }
            throw e
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        var m: Modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { own = it.boundsInRoot() }
            .graphicsLayer {
                applyNativeZoom(progress.value, own, sources.frame(origin.session, origin.key), Offset(dragX.value, dragY.value))
            }
        val gesture = origin.dismissGesture
        if (!dismissing && !closing && gesture != ScreenTransition.DismissGesture.none) {
            val onStopped: suspend (Offset) -> Unit = { velocity ->
                val distance = if (gesture == ScreenTransition.DismissGesture.down) dragY.value else hypot(dragX.value, dragY.value)
                val speed = if (gesture == ScreenTransition.DismissGesture.down) velocity.y else hypot(velocity.x, velocity.y)
                if (distance / hPx >= 0.25f || (speed > 1200f && distance > 0f)) {
                    origin.refreshKey(sources)
                    shrinkAway()
                } else {
                    launchJoin(
                        { dragX.animateTo(0f, spring(0.8f, 400f)) },
                        { dragY.animateTo(0f, spring(0.8f, 400f)) },
                    )
                }
            }
            m = if (gesture == ScreenTransition.DismissGesture.down) {
                m.draggable(
                    rememberDraggableState { d ->
                        acc[1] = (acc[1] + d).coerceAtLeast(0f)
                        val y = acc[1]
                        scope.launch { dragY.snapTo(y) }
                    },
                    Orientation.Vertical,
                    onDragStarted = { acc[0] = dragX.value; acc[1] = dragY.value },
                    onDragStopped = { v -> onStopped(Offset(0f, v)) },
                )
            } else {
                m.draggable2D(
                    rememberDraggable2DState { d ->
                        // Accumulate synchronously: the finger tracks 1:1 even when frames coalesce.
                        acc[0] += d.x
                        acc[1] += d.y
                        val x = acc[0]
                        val y = acc[1]
                        scope.launch { dragX.snapTo(x); dragY.snapTo(y) }
                    },
                    onDragStarted = { acc[0] = dragX.value; acc[1] = dragY.value },
                    onDragStopped = { v -> scope.launch { onStopped(Offset(v.x, v.y)) } },
                )
            }
        }
        Box(m) { content() }
    }
}

private suspend fun launchJoin(vararg blocks: suspend () -> Unit) {
    kotlinx.coroutines.coroutineScope { blocks.map { b -> launch { b() } }.forEach { it.join() } }
}
