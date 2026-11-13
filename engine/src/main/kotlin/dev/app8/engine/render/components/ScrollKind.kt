package dev.app8.engine.render.components

import androidx.compose.foundation.layout.fillMaxSize
import me.saket.telephoto.zoomable.zoomable

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.translate
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
        if (p.isZoomEnabled) {
            ZoomContent(node, p, modifier)
            return
        }
        val vertical = p.direction != ScrollViewProperties.Direction.horizontal
        val state = rememberScrollState()
        val solved = LocalSolved.current
        val density = LocalDensity.current.density
        ScrollEvents(node, p, state, vertical, density)
        val auto = p.autoScroll
        val infinite = auto != null && (auto.infinite ?: true)
        val loopGapPx = ((auto?.loopGap ?: 0.0) * density).toFloat()
        // Main-axis length of one copy of the content in px (the marquee wrap length).
        val contentLength = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        if (auto != null) AutoScroll(state, auto.speed * density, infinite, { contentLength.intValue + loopGapPx })
        val fling = if (p.pagingEnabled == true) rememberPagingFling(state, vertical) else null
        val scrollModifier = if (vertical) Modifier.verticalScroll(state, flingBehavior = fling) else Modifier.horizontalScroll(state, flingBehavior = fling)
        // `infinite` auto-scroll draws the content a second time after itself (plus
        // `loopGap`), so wrapping the offset by one length is seamless (iOS marquee duplicate).
        val marquee = if (infinite) Modifier.drawWithContent {
            drawContent()
            val len = contentLength.intValue + loopGapPx
            translate(left = if (vertical) 0f else len, top = if (vertical) len else 0f) { this@drawWithContent.drawContent() }
        } else Modifier
        // The keyboard covers the bottom of a vertical scroll view (the window does not
        // resize edge-to-edge): extend the content by the covered part so a focused field
        // near the end can still scroll above the keyboard.
        val imePx = if (vertical) WindowInsets.ime.getBottom(LocalDensity.current) else 0
        val gapBelow = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val keyboardPad = (imePx - gapBelow.intValue).coerceAtLeast(0)
        val scroller = remember(state) { dev.app8.engine.render.controls.KeyboardScroller(state) }
        val track = if (vertical) Modifier.onGloballyPositioned { c ->
            scroller.viewport = c
            val root = c.findRootCoordinates()
            gapBelow.intValue = (root.size.height - c.localToRoot(androidx.compose.ui.geometry.Offset(0f, c.size.height.toFloat())).y).toInt().coerceAtLeast(0)
        } else Modifier
        Box(modifier.then(track).clipToBounds()) {
            Layout(
                content = {
                    val provided = if (vertical) scroller else dev.app8.engine.render.controls.LocalKeyboardScroller.current
                    androidx.compose.runtime.CompositionLocalProvider(dev.app8.engine.render.controls.LocalKeyboardScroller provides provided) {
                        for (child in node.children) key(child.key) { NodeView(child) }
                    }
                },
                modifier = scrollModifier.then(marquee),
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
                val contentH = if (vertical) (((content?.height ?: 0.0) + top + bottom) * d).roundToInt() + keyboardPad else viewportH
                val placeables = measurables.map { m ->
                    val f = s.local[m.layoutId as? String]
                    m.measure(Constraints.fixed(((f?.width ?: 0.0) * d).roundToInt().coerceAtLeast(0), ((f?.height ?: 0.0) * d).roundToInt().coerceAtLeast(0))) to f
                }
                val w = contentW.coerceAtLeast(if (vertical) 0 else viewportW)
                val h = contentH.coerceAtLeast(if (vertical) viewportH else 0)
                // One copy's length: the content extent (children may reach past the solved
                // content size), so the marquee seam sits right after the last child.
                val extent = placeables.maxOfOrNull { (_, f) -> if (f == null) 0.0 else if (vertical) f.y + f.height else f.x + f.width } ?: 0.0
                contentLength.intValue = maxOf(if (vertical) contentH else contentW, ((extent + if (vertical) top + bottom else left + right) * d).roundToInt())
                // The marquee copy extends the scrollable range by one length + gap.
                val extra = if (infinite) contentLength.intValue + loopGapPx.roundToInt() else 0
                layout(if (vertical) w else maxOf(w, extra * 2), if (vertical) maxOf(h, extra * 2) else h) {
                    for ((pl, f) in placeables) if (f != null) pl.place(px(f.x + left, d), px(f.y + top, d))
                }
            }
        }
    }

    /**
     * Pinch zoom (`maximumZoomScale` > 1, iOS `viewForZooming`): telephoto's `zoomable` on the
     * content, which fills the scroll view. Over-zoom rubber-bands and springs back; double tap
     * zooms to the tap point (2.5× or the maximum, whichever is smaller) and back out. Inside a
     * horizontal pager the pan goes to the pager at a content edge or at scale 1.
     * `stretchesTopChild` has no custom effect on Android (system stretch overscroll).
     */
    @Composable
    private fun ZoomContent(node: RenderNode, p: ScrollViewProperties, modifier: Modifier) {
        val max = (p.maximumZoomScale ?: 1.0).toFloat()
        val min = (p.minimumZoomScale ?: 1.0).toFloat().coerceIn(0.1f, 1f)
        val zoomState = me.saket.telephoto.zoomable.rememberZoomableState(
            zoomSpec = me.saket.telephoto.zoomable.ZoomSpec(maxZoomFactor = max, minZoomFactor = min),
        )
        val doubleTap = if (p.doubleTapZoom != false) {
            me.saket.telephoto.zoomable.DoubleClickToZoomListener.cycle(maxZoomFactor = minOf(2.5f, max))
        } else null
        val output = p.outputVariable
        if (output != null) {
            LaunchedEffect(node, zoomState) {
                snapshotFlow { zoomState.contentTransformation.scale.scaleX }.collect { scale ->
                    if (node.scope.store.hasVariable(output) && !scale.isNaN()) {
                        runCatching { node.scope.store.setValue(output, mapOf("offset" to 0.0, "zoomScale" to scale.toDouble())) }
                    }
                }
            }
        }
        val solved = LocalSolved.current
        val density = LocalDensity.current.density
        Box(modifier.clipToBounds()) {
            Layout(
                content = { for (child in node.children) key(child.key) { NodeView(child) } },
                modifier = Modifier.fillMaxSize().zoomable(zoomState, onDoubleClick = doubleTap),
            ) { measurables, constraints ->
                val s = solved.value
                val placeables = measurables.map { m ->
                    val f = s.local[m.layoutId as? String]
                    m.measure(
                        Constraints.fixed(
                            ((f?.width ?: 0.0) * density).roundToInt().coerceAtLeast(0),
                            ((f?.height ?: 0.0) * density).roundToInt().coerceAtLeast(0),
                        ),
                    ) to f
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    for ((pl, f) in placeables) if (f != null) pl.place(px(f.x, density), px(f.y, density))
                }
            }
        }
    }

    /**
     * `autoScroll` (iOS display-link marquee): advances the offset by `speed` points per
     * second. `infinite` wraps by one content length (+ `loopGap`); otherwise it stops at
     * the end. A drag takes over and the marquee resumes from wherever the user left it.
     */
    @Composable
    private fun AutoScroll(state: ScrollState, speedPx: Double, infinite: Boolean, length: () -> Float) {
        LaunchedEffect(state, speedPx, infinite) {
            var last = 0L
            var position = state.value.toFloat()
            var written = state.value
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last == 0L) { last = now; return@withFrameNanos }
                    val dt = (now - last) / 1e9
                    last = now
                    if (state.isScrollInProgress) { position = state.value.toFloat(); written = state.value; return@withFrameNanos }
                    // Sub-pixel accumulation: never advance from the pixel-snapped offset.
                    if (kotlin.math.abs(state.value - written) > 1) position = state.value.toFloat()
                    position += (speedPx * dt).toFloat()
                    val len = length()
                    if (infinite && len > 0f) {
                        while (position >= len) position -= len
                        while (position < 0f) position += len
                    } else {
                        position = position.coerceIn(0f, state.maxValue.toFloat())
                    }
                    val target = position.roundToInt()
                    state.dispatchRawDelta((target - state.value).toFloat())
                    written = state.value
                }
            }
        }
    }

    /**
     * `pagingEnabled` (UIScrollView paging): a drag settles on a whole page (the viewport
     * size). A fling moves at most one page in its direction; a slow release snaps to the
     * nearest page.
     */
    @Composable
    private fun rememberPagingFling(state: ScrollState, vertical: Boolean): FlingBehavior {
        val spec = androidx.compose.animation.core.spring<Float>(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
        val density = LocalDensity.current.density
        return remember(state, vertical) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    val page = state.viewportSize.toFloat()
                    if (page <= 0f) return initialVelocity
                    val offset = state.value.toFloat()
                    val current = kotlin.math.floor(offset / page)
                    val fraction = offset / page - current
                    val threshold = 400f * density
                    val targetPage = when {
                        initialVelocity > threshold -> current + 1
                        initialVelocity < -threshold -> current
                        else -> if (fraction >= 0.5f) current + 1 else current
                    }
                    val target = (targetPage * page).coerceIn(0f, state.maxValue.toFloat())
                    var previous = 0f
                    androidx.compose.animation.core.animate(0f, target - offset, initialVelocity, spec) { v, _ ->
                        scrollBy(v - previous)
                        previous = v
                    }
                    return 0f
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
