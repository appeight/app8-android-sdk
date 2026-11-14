package dev.app8.engine.navigation.transitions

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import android.content.Context
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
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
    initialAlignmentKey: String? = null,
    private val realign: () -> String? = { null },
) {
    var key: String by mutableStateOf(initialKey)
        private set

    /** `alignmentKey`: the element on the incoming screen the source lines up with (null: the whole screen). */
    var alignmentKey: String? by mutableStateOf(initialAlignmentKey)
        private set

    /** The incoming screen's keyed elements, for [alignmentKey]. */
    val alignment = ZoomAlignment()

    /**
     * Dismissal starts: the key may now name another element. A key that only resolves in the
     * action's scope (`item` of a cell) gives nothing on the screen; then the first key stays.
     */
    fun refreshKey(sources: ZoomSources) {
        rekey()?.takeIf { it.isNotEmpty() && sources.frame(session, it) != null }?.let { key = it }
        realign()?.takeIf { it.isNotEmpty() && alignment.has(it) }?.let { alignmentKey = it }
    }

    /** The alignment element's rect in the incoming screen's local (untransformed) px, if laid out. */
    fun alignmentRect(): Rect? = alignmentKey?.let { alignment.rect(it) }

    /** The source's photo, loaded for the shared-element morph ([awaitZoomReady]). */
    var photo: ZoomPhoto? by mutableStateOf(null)

    /**
     * The shared-element morph at [progress]: the source's photo, aspect-filled and opaque, in
     * `G = lerp(c0, A, q)` (c0: the source-shaped start clip centred on the alignment rect A),
     * corner `lerp(S.radius / s0, 0, q)`, drawn by the alignment element in place of itself.
     * Null at the end (the element shows itself) and without a photo or an alignment element
     * (then the screen-level ghost cross-fades).
     */
    fun photoGhost(progress: Float, own: Rect, frame: ZoomSourceFrame?): PhotoGhost? {
        if (progress >= 1f || own.width <= 0f) return null
        val f = frame ?: return null
        val model = f.photo ?: return null
        val image = photo?.takeIf { it.model == model }?.image ?: return null
        val key = alignmentKey ?: return null
        val a = alignment.rect(key) ?: return null
        val (s0, c0) = zoomStart(f.bounds.translate(-own.left, -own.top), a)
        val q = progress.coerceIn(0f, 1f)
        fun lerp(x: Float, y: Float) = x + (y - x) * q
        val g = Rect(lerp(c0.left, a.left), lerp(c0.top, a.top), lerp(c0.right, a.right), lerp(c0.bottom, a.bottom))
        return PhotoGhost(key, image, g.translate(-a.left, -a.top), lerp(f.cornerPx / s0, 0f))
    }
}

/** A source photo loaded for the morph; [model] is the source image's asset reference. */
internal class ZoomPhoto(val model: Any, val image: ImageBitmap)

/** What the alignment element [key] draws instead of itself: [image] aspect-filled in [rect] (element-local px). */
internal class PhotoGhost(val key: String, val image: ImageBitmap, val rect: Rect, val radius: Float)

/** Draws [ghost]'s photo aspect-filled (crop) in its rect with its corner. */
internal fun DrawScope.drawPhotoGhost(ghost: PhotoGhost) {
    val r = ghost.rect
    val iw = ghost.image.width.toFloat()
    val ih = ghost.image.height.toFloat()
    if (r.width <= 0f || r.height <= 0f || iw <= 0f || ih <= 0f) return
    val k = max(r.width / iw, r.height / ih)
    val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(ghost.radius.coerceAtLeast(0f)))) }
    clipPath(path) {
        withTransform({
            translate(r.center.x - k * iw / 2, r.center.y - k * ih / 2)
            scale(k, k, pivot = Offset.Zero)
        }) { drawImage(ghost.image) }
    }
}

/**
 * Where the incoming screen's keyed elements are, relative to the screen itself (inside its
 * zoom transform, so the rect is untransformed). Live [LayoutCoordinates], read when drawing.
 */
internal class ZoomAlignment {
    var screen: LayoutCoordinates? = null

    /** Set while the zoom runs: the morph the alignment element draws instead of itself. */
    var ghost: (() -> PhotoGhost?)? by mutableStateOf(null)
    private val elements = HashMap<String, LayoutCoordinates>()

    fun put(key: String, coordinates: LayoutCoordinates) {
        elements[key] = coordinates
    }

    fun remove(key: String, coordinates: LayoutCoordinates?) {
        if (coordinates == null || elements[key] === coordinates) elements.remove(key)
    }

    fun has(key: String): Boolean = elements[key]?.isAttached == true

    fun rect(key: String): Rect? {
        val s = screen?.takeIf { it.isAttached } ?: return null
        val e = elements[key]?.takeIf { it.isAttached } ?: return null
        return runCatching { s.localBoundingBoxOf(e, clipBounds = false) }.getOrNull()?.takeIf { it.width > 0f && it.height > 0f }
    }
}

/** The zoom alignment of the screen being presented with a native zoom (its keyed elements report here). */
internal val LocalZoomAlignment = compositionLocalOf<ZoomAlignment?> { null }

/** A source element's window bounds (px), corner radius (px) and picture (an `image` element's asset reference). */
internal data class ZoomSourceFrame(val bounds: Rect, val cornerPx: Float, val photo: Any? = null)

/**
 * Elements with `content.transition.key` report where they are, per presenting screen, so a
 * native zoom can find its source. The zoom hides its source while the incoming screen is up.
 */
internal class ZoomSources {
    private val frames = mutableStateMapOf<Pair<Any, String>, ZoomSourceFrame>()
    private val hidden = mutableStateMapOf<Pair<Any, String>, Int>()
    private val layers = HashMap<Pair<Any, String>, GraphicsLayer>()

    /** The source's last drawn content (the ghost drawn over the zooming screen). */
    fun layer(session: Any, key: String): GraphicsLayer? = layers[session to key]?.takeIf { !it.isReleased }

    fun putLayer(session: Any, key: String, layer: GraphicsLayer) {
        layers[session to key] = layer
    }

    fun removeLayer(session: Any, key: String, layer: GraphicsLayer) {
        if (layers[session to key] === layer) layers.remove(session to key)
    }

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

/**
 * Registers a keyed element as a zoom source (its frame and its drawn content, for the ghost),
 * hides it while its zoom is presented, and reports it to the [LocalZoomAlignment] of a screen
 * that is being zoomed in (the `alignmentKey` target).
 */
@Composable
internal fun Modifier.zoomSource(sources: ZoomSources, session: Any, key: String?, cornerPx: Float, photo: Any? = null): Modifier {
    if (key.isNullOrEmpty()) return this
    val layer = rememberGraphicsLayer()
    val alignment = LocalZoomAlignment.current
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(sources, session, key, layer) {
        sources.putLayer(session, key, layer)
        onDispose {
            sources.remove(session, key)
            sources.removeLayer(session, key, layer)
        }
    }
    DisposableEffect(alignment, key) { onDispose { alignment?.remove(key, placed[0]) } }
    var m = this
    if (alignment != null) m = m.onPlaced { c -> placed[0] = c; alignment.put(key, c) }
    return m
        .onGloballyPositioned { c -> if (c.isAttached) sources.report(session, key, ZoomSourceFrame(c.boundsInRoot(), cornerPx, photo)) }
        .graphicsLayer { if (sources.isHidden(session, key)) alpha = 0f }
        .drawWithContent {
            // The alignment element of a running zoom: the morphing photo instead of itself
            // (under the screen's overlays: page dots, back button).
            val ghost = alignment?.ghost?.invoke()?.takeIf { it.key == key }
            if (ghost != null) {
                drawPhotoGhost(ghost)
                return@drawWithContent
            }
            // Recorded even while hidden: the ghost draws the same content over the zooming screen.
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
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

/** Native zoom easing: cubic ease-out, `1 - (1 - t)^3`. */
internal val NativeZoomEasing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** Native zoom durations (ms): growing in, shrinking back (also finishing or cancelling a gesture). */
internal const val NATIVE_ZOOM_IN_MS = 360
internal const val NATIVE_ZOOM_OUT_MS = 320

/** Longest wait for the incoming screen's alignment element before the zoom starts anyway (ms). */
internal const val NATIVE_ZOOM_READY_TIMEOUT_MS = 300

/**
 * Waits (frame by frame, at most [NATIVE_ZOOM_READY_TIMEOUT_MS]) until the incoming screen has
 * laid out its `alignmentKey` element: the screen loads asynchronously, and starting before the
 * alignment is known would make the screen jump mid-zoom. The ghost covers the source meanwhile.
 */
internal suspend fun awaitZoomReady(origin: ZoomOrigin, sources: ZoomSources, loader: ImageLoader, context: Context, maxSize: IntSize) {
    if (origin.alignmentKey == null) return
    val start = withFrameNanos { it }
    val timeout = NATIVE_ZOOM_READY_TIMEOUT_MS * 1_000_000L
    coroutineScope {
        // The source photo for the morph, at most screen-sized (normally a disk cache hit).
        val model = sources.frame(origin.session, origin.key)?.photo
        val load = if (model != null && origin.photo?.model != model) {
            async {
                val request = ImageRequest.Builder(context).data(model).size(maxSize.width.coerceAtLeast(1), maxSize.height.coerceAtLeast(1)).build()
                (loader.execute(request) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.let { origin.photo = ZoomPhoto(model, it) }
            }
        } else {
            null
        }
        var now = start
        while (origin.alignmentRect() == null && now - start < timeout) now = withFrameNanos { it }
        if (load != null) {
            val left = (timeout - (now - start)) / 1_000_000L
            if (left <= 0 || withTimeoutOrNull(left) { load.await() } == null) load.cancel()
        }
    }
}

internal val NATIVE_ZOOM_IN = tween<Float>(NATIVE_ZOOM_IN_MS, easing = NativeZoomEasing)
internal val NATIVE_ZOOM_OUT = tween<Float>(NATIVE_ZOOM_OUT_MS, easing = NativeZoomEasing)

/**
 * The incoming screen's transform at one progress: uniform [scale] about the top-left corner,
 * then [translation] (px), clipped to [clip] (screen-local, untransformed px) with corner
 * [radius] (local px).
 */
internal data class ZoomGeometry(val scale: Float, val translation: Offset, val clip: Rect, val radius: Float)

/**
 * The native zoom geometry at [progress] (0 = in the source, 1 = the screen's own bounds).
 * [screen] is the incoming screen's size; [source] the source rect and [sourceCorner] its corner,
 * both relative to the screen's top-left (px). [alignment] is the `alignmentKey` element on the
 * incoming screen (screen-local, untransformed); default the whole screen.
 *
 * At 0 the screen is scaled and moved so [alignment] lines up with [source] (aspect-filled:
 * `s0 = max(S.w/A.w, S.h/A.h)`), clipped to a source-shaped rect centred on the alignment rect;
 * at 1 it is the identity, unclipped. Scale, translation, local clip and radius interpolate
 * linearly with the progress (the web renderer uses the same math).
 */
internal fun zoomGeometry(progress: Float, screen: Size, source: Rect, sourceCorner: Float, alignment: Rect? = null): ZoomGeometry {
    val f = progress.coerceIn(0f, 1.2f)
    val w = screen.width
    val h = screen.height
    val a = alignment?.takeIf { it.width > 0f && it.height > 0f } ?: Rect(0f, 0f, w, h)
    val (s0, c0) = zoomStart(source, a)
    val t0 = Offset(source.left - s0 * c0.left, source.top - s0 * c0.top)
    fun lerp(x: Float, y: Float) = x + (y - x) * f
    val scale = lerp(s0, 1f).coerceAtLeast(1e-4f)
    val clip = Rect(lerp(c0.left, 0f), lerp(c0.top, 0f), lerp(c0.right, w), lerp(c0.bottom, h))
    val radiusOnScreen = sourceCorner * (1f - f.coerceIn(0f, 1f))
    return ZoomGeometry(scale, Offset(lerp(t0.x, 0f), lerp(t0.y, 0f)), clip, radiusOnScreen / scale)
}

/** The start scale `s0 = max(S.w/A.w, S.h/A.h)` and start clip c0 (source-shaped, centred on [a]). */
internal fun zoomStart(source: Rect, a: Rect): Pair<Float, Rect> {
    val s0 = max(source.width / a.width, source.height / a.height).coerceAtLeast(1e-4f)
    val cw = source.width / s0
    val ch = source.height / s0
    return s0 to Rect(a.center.x - cw / 2, a.center.y - ch / 2, a.center.x + cw / 2, a.center.y + ch / 2)
}

/** Interactive drag: the screen follows the finger (px) and shrinks with the distance, about the clip's centre. */
internal fun ZoomGeometry.dragged(drag: Offset, screen: Size, extraRadius: Float): ZoomGeometry {
    if (drag == Offset.Zero) return this
    val k = (1f - hypot(drag.x, drag.y) / max(screen.width, screen.height) * 0.6f).coerceIn(0.4f, 1f)
    val c = translation + clip.center * scale
    val t = c + drag + (translation - c) * k
    val s = scale * k
    return ZoomGeometry(s, t, clip, (radius * scale * k + extraRadius) / s)
}

/**
 * The card photo drawn over the screen during the zoom: aspect-filled in the clip. Growing in it
 * fades out over the first 40% of the time; shrinking back it fades in over the last half of the
 * way and lands opaque exactly over the source.
 */
internal fun zoomGhostAlpha(progress: Float, dismissing: Boolean): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (dismissing) {
        (1f - 2f * p).coerceIn(0f, 1f)
    } else {
        // Time fraction from the cubic ease-out position.
        val t = 1f - Math.cbrt((1.0 - p)).toFloat()
        (1f - t / 0.4f).coerceIn(0f, 1f)
    }
}

/**
 * The container transform at [progress] (0 = the source rect, 1 = the screen's own bounds
 * [own], both in window px), plus an interactive [drag] offset (px) that shrinks the screen as
 * it follows the finger. [alignment] is the `alignmentKey` element (screen-local). Without a
 * source the screen cross-fades; with one it stays opaque (the ghost covers the start).
 */
internal fun GraphicsLayerScope.applyNativeZoom(progress: Float, own: Rect, source: ZoomSourceFrame?, alignment: Rect? = null, drag: Offset = Offset.Zero) {
    transformOrigin = TransformOrigin(0f, 0f)
    val w = own.width
    val h = own.height
    if (w <= 0f || h <= 0f) return
    if (source == null) {
        alpha = progress.coerceIn(0f, 1f)
        applyDrag(drag, w, h)
        return
    }
    val g = zoomGeometry(progress, Size(w, h), source.bounds.translate(-own.left, -own.top), source.cornerPx, alignment)
        .dragged(drag, Size(w, h), if (drag != Offset.Zero) 24f * density else 0f)
    scaleX = g.scale
    scaleY = g.scale
    translationX = g.translation.x
    translationY = g.translation.y
    shape = ZoomClipShape(g.clip, g.radius)
    clip = true
    alpha = 1f
}

/**
 * Draws the source's recorded content ([layer]) aspect-filled in the zoom clip, inside the
 * screen's layer (local coordinates; the layer's clip rounds it).
 */
internal fun DrawScope.drawZoomGhost(progress: Float, own: Rect, source: ZoomSourceFrame?, layer: GraphicsLayer?, alignment: Rect?, dismissing: Boolean) {
    if (source == null || layer == null || own.width <= 0f || own.height <= 0f) return
    val a = zoomGhostAlpha(progress, dismissing)
    if (a <= 0f) return
    val sw = source.bounds.width
    val sh = source.bounds.height
    if (sw <= 0f || sh <= 0f) return
    val g = zoomGeometry(progress, Size(own.width, own.height), source.bounds.translate(-own.left, -own.top), source.cornerPx, alignment)
    val c = g.clip
    val fill = max(c.width / sw, c.height / sh)
    val canvas = drawContext.canvas
    canvas.saveLayer(c, Paint().apply { alpha = a })
    withTransform({
        clipRect(c.left, c.top, c.right, c.bottom)
        translate(c.center.x - fill * sw / 2, c.center.y - fill * sh / 2)
        scale(fill, fill, pivot = Offset.Zero)
    }) { drawLayer(layer) }
    canvas.restore()
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
    // Landed: the ghost covers the source exactly; the source shows again one frame before the
    // screen goes, so there is never a frame without either.
    var landed by remember { mutableStateOf(false) }
    val acc = remember { floatArrayOf(0f, 0f) }
    HideZoomSource(sources, origin, active = !landed)

    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = dev.app8.engine.render.LocalRenderEnv.current.runtime.imageLoader
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    LaunchedEffect(Unit) {
        awaitZoomReady(origin, sources, loader, context, window)
        progress.animateTo(1f, NATIVE_ZOOM_IN)
    }
    DisposableEffect(origin) {
        origin.alignment.ghost = { origin.photoGhost(progress.value, own, sources.frame(origin.session, origin.key)) }
        onDispose { origin.alignment.ghost = null }
    }

    suspend fun land() {
        landed = true
        withFrameNanos { }
        withFrameNanos { }
        onRemoved()
    }

    suspend fun shrinkAway() {
        closing = true
        launchJoin(
            { progress.animateTo(0f, NATIVE_ZOOM_OUT) },
            { dragX.animateTo(0f, NATIVE_ZOOM_OUT) },
            { dragY.animateTo(0f, NATIVE_ZOOM_OUT) },
        )
        land()
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
            progress.animateTo(0f, NATIVE_ZOOM_OUT)
            land()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(1f, NATIVE_ZOOM_OUT) }
            throw e
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPx = constraints.maxHeight.toFloat()
        var m: Modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { own = it.boundsInRoot() }
            .graphicsLayer {
                applyNativeZoom(progress.value, own, sources.frame(origin.session, origin.key), origin.alignmentRect(), Offset(dragX.value, dragY.value))
            }
            .onPlaced { origin.alignment.screen = it }
            .drawWithContent {
                drawContent()
                val key = origin.key
                if (origin.photoGhost(progress.value, own, sources.frame(origin.session, key)) == null) drawZoomGhost(progress.value, own, sources.frame(origin.session, key), sources.layer(origin.session, key), origin.alignmentRect(), dismissing = closing || dismissing)
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
                        { dragX.animateTo(0f, NATIVE_ZOOM_OUT) },
                        { dragY.animateTo(0f, NATIVE_ZOOM_OUT) },
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
        Box(m) { CompositionLocalProvider(LocalZoomAlignment provides origin.alignment) { content() } }
    }
}

private suspend fun launchJoin(vararg blocks: suspend () -> Unit) {
    kotlinx.coroutines.coroutineScope { blocks.map { b -> launch { b() } }.forEach { it.join() } }
}
