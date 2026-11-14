package dev.app8.engine.navigation.transitions

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.setValue
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.navigation.Route
import dev.app8.engine.navigation.StackState

/**
 * What a keyed element (`content.transition.key`) needs to morph during a `shared`
 * transition: the shared scope around the stack, the entry's enter/exit scope, and the
 * transition running now (null when idle or not shared).
 */
internal class SharedMorph(
    val scope: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val active: ResolvedTransition?,
)

internal val LocalSharedMorph = compositionLocalOf<SharedMorph?> { null }

/** The engine-driven transition between the top two routes, decided from the stack state. */
private fun StackState.pushTransition(): ResolvedTransition? = entries.lastOrNull()?.transition
private fun StackState.popTransition(): ResolvedTransition? = lastPopped?.transition

private fun zIndex(t: ResolvedTransition, pop: Boolean): Float {
    // Kept for AnimatedContent; the effective layering is done by TransitionFrame.
    // `raise` in forward terms; the reverse mirrors the stacking.
    val incomingOnTop = t.raise == ScreenTransition.Raise.incoming
    return if (incomingOnTop != pop) 1f else -1f
}

/**
 * NavDisplay specs: an engine transition keeps both screens composed while the entries
 * animate their own keyframes ([TransitionFrame]); `system` (or none set) falls back to
 * the platform's Navigation 3 animations. Predictive back seeks the same transition, so
 * the gesture scrubs the authored pop animation.
 */
internal fun <T : Any> stackTransitionSpec(
    stack: StackState,
    pop: Boolean,
    predictive: Boolean,
    platform: AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform,
): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    // NavDisplay may evaluate the push spec for a pop before it knows the direction (the
    // z-order is fixed at that first evaluation), so the direction comes from the stack: the
    // target scene is not the top route (a back gesture), or the last change was a pop.
    val targetTop = targetState.entries.lastOrNull()?.contentKey
    val isPop = pop || predictive || stack.popping || (targetTop != null && targetTop != stack.entries.lastOrNull()?.id)
    val t = if (isPop) (if (stack.popping) stack.popTransition() else stack.pushTransition()) else stack.pushTransition()
    when {
        t == null || t.kind == ResolvedTransition.Kind.System -> platform()
        t.kind == ResolvedTransition.Kind.None -> ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else -> ContentTransform(EnterTransition.None, ExitTransition.KeepUntilTransitionsFinished, zIndex(t, isPop), sizeTransform = null)
    }
}

private class Role(val transition: ResolvedTransition, val entering: Boolean, val pop: Boolean) {
    val autoReverse: Boolean get() = pop && !transition.explicitReverse(true)

    /** Keyframes this screen plays (iOS: forward `from`/`to`, or the explicit reverse pair). */
    val participant: ScreenTransition.Participant
        get() = when {
            !pop -> if (entering) transition.to else transition.from
            autoReverse -> if (entering) transition.from else transition.to
            else -> if (entering) transition.reverseTo ?: IDENTITY else transition.reverseFrom ?: IDENTITY
        }

    /**
     * Fraction along [participant] (0 = begin, 1 = end) from the entry's role-independent
     * visibility [v] (PreEnter 0, Visible 1, PostExit 2). The animated value never depends on
     * the role, so a screen whose role is decided mid-composition never jumps.
     */
    fun fraction(v: Float): Float {
        val forward = if (entering) v else v - 1f
        // Auto reverse plays the forward keyframes backwards.
        return (if (autoReverse) 1f - forward else forward).coerceIn(-0.5f, 1.5f)
    }

    companion object {
        val IDENTITY = ScreenTransition.Participant()
    }
}

/**
 * One stack entry's side of the transition (iOS `PropertyTransitionAnimator`): the
 * participant keyframes on its whole screen, driven by the entry's enter/exit transition
 * (which predictive back seeks).
 */
@Composable
internal fun TransitionFrame(route: Route, stack: StackState, shared: SharedTransitionScope?, content: @Composable () -> Unit) {
    val visibility = LocalNavAnimatedContentScope.current
    val t = visibility.transition
    val entries = stack.entries
    val last = entries.lastOrNull()
    val role: Role? = when {
        t.targetState == EnterExitState.Visible && t.currentState == EnterExitState.PreEnter -> {
            // Entering: the new top on push; the revealed screen on pop (or during a back gesture).
            val pop = route != last || stack.popping
            val tr = if (!pop) route.transition else if (route != last) last?.transition else stack.lastPopped?.transition
            tr?.takeIf { it.isAnimated }?.let { Role(it, entering = true, pop = pop) }
        }
        t.targetState == EnterExitState.PostExit -> {
            // Leaving: the covered screen on push; the popped screen on pop.
            val pop = route == last || (route !in entries && stack.popping)
            val tr = if (pop) route.transition else last?.transition
            tr?.takeIf { it.isAnimated }?.let { Role(it, entering = false, pop = pop) }
        }
        else -> null
    }
    val spec = role?.transition?.let { tr ->
        // The native zoom's default timing: cubic ease-out, shorter going back.
        // Push: the zoom runs on its own clock once the incoming screen is ready
        // ([awaitZoomReady]); the navigation transition only keeps both screens up meanwhile.
        if (tr.nativeZoom && tr.animation == ResolvedTransition.NATIVE_ZOOM_ANIMATION) {
            if (role.pop) NATIVE_ZOOM_OUT else androidx.compose.animation.core.tween(NATIVE_ZOOM_IN_MS + NATIVE_ZOOM_READY_TIMEOUT_MS, easing = androidx.compose.animation.core.LinearEasing)
        }
        else tr.animation.toTransitionSpec()
    }
    val visibleState = t.animateFloat(transitionSpec = { spec ?: androidx.compose.animation.core.snap() }, label = "a8-transition") { s ->
        when (s) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 2f
        }
    }
    val visible by visibleState
    val participant = role?.participant
    val active = role?.transition
    // Layering (`raise`): AnimatedContent gives both screens of a transition the same
    // z-index, so the screen that must be on top is drawn in the shared-transition overlay
    // for the duration. Forward: incoming (`raise: incoming`) or outgoing on top; the
    // reverse mirrors it.
    val onTop = role != null && run {
        val isTo = role.entering != role.pop
        if (role.transition.raise == ScreenTransition.Raise.incoming) isTo else !isTo
    }
    // Native zoom (`zoom` + `sourceKey`): the route that zooms is the pushed one (entering on
    // push, leaving on pop); its bounds morph from the source element. Predictive back seeks
    // `visible`, so the gesture scrubs the shrink.
    val zoomOrigin = route.zoom?.takeIf { role != null && role.transition.nativeZoom && role.entering != role.pop }
    val runtime = dev.app8.engine.render.LocalRenderEnv.current.runtime
    var ownBounds by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    if (route.zoom != null) {
        // Hidden while this route is on the stack (or animating out). A pop that has landed
        // shows the source again under the ghost (same pixels) before the screen goes: no blink.
        val popping = zoomOrigin != null && role?.pop == true
        val landed by androidx.compose.runtime.remember(popping) {
            androidx.compose.runtime.derivedStateOf { popping && visibleState.value >= 2f }
        }
        HideZoomSource(runtime.zoomSources, route.zoom, active = !landed)
    }
    // The push zoom's own clock (default timing): starts when the screen is ready.
    val zoomIn = androidx.compose.runtime.remember(route) { androidx.compose.animation.core.Animatable(0f) }
    val ownClock = zoomOrigin != null && role?.pop == false && role.transition.animation == ResolvedTransition.NATIVE_ZOOM_ANIMATION
    if (ownClock && zoomOrigin != null) {
        androidx.compose.runtime.LaunchedEffect(zoomOrigin) {
            if (zoomIn.value < 1f) {
                awaitZoomReady(zoomOrigin)
                zoomIn.animateTo(1f, NATIVE_ZOOM_IN)
            }
        }
    }
    fun zoomProgress(): Float = when {
        ownClock -> zoomIn.value
        visible <= 1f -> visible
        else -> 2f - visible
    }
    if (zoomOrigin != null && role?.pop == true) {
        androidx.compose.runtime.LaunchedEffect(zoomOrigin) { zoomOrigin.refreshKey(runtime.zoomSources) }
    }
    val body: @Composable () -> Unit = {
        var m: Modifier = Modifier.fillMaxSize()
        if (shared != null) {
            with(shared) {
                @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
                m = m.renderInSharedTransitionScopeOverlay(renderInOverlay = { onTop }, zIndexInOverlay = -1f)
            }
        }
        if (zoomOrigin != null) {
            m = m.onGloballyPositioned { ownBounds = it.boundsInRoot() }
        }
        m = m.graphicsLayer {
            if (zoomOrigin != null) {
                applyNativeZoom(zoomProgress(), ownBounds, runtime.zoomSources.frame(zoomOrigin.session, zoomOrigin.key), zoomOrigin.alignmentRect())
            } else if (participant != null && role != null) {
                applyKeyframes(participant, role.fraction(visible), size.width, size.height)
            }
        }
        val zoom = route.zoom
        if (zoom != null) {
            m = m.onPlaced { zoom.alignment.screen = it }
            if (zoomOrigin != null) {
                m = m.drawWithContent {
                    drawContent()
                    val key = zoomOrigin.key
                    drawZoomGhost(
                        zoomProgress(), ownBounds, runtime.zoomSources.frame(zoomOrigin.session, key), runtime.zoomSources.layer(zoomOrigin.session, key),
                        zoomOrigin.alignmentRect(), dismissing = role?.pop == true,
                    )
                }
            }
            Box(m) { CompositionLocalProvider(LocalZoomAlignment provides zoom.alignment) { content() } }
        } else {
            Box(m) { content() }
        }
    }
    if (shared != null) {
        CompositionLocalProvider(LocalSharedMorph provides SharedMorph(shared, visibility, active?.takeIf { it.kind == ResolvedTransition.Kind.Shared })) { body() }
    } else {
        body()
    }
}


/**
 * A keyed element's part in a `shared` transition (iOS `SharedElementTransitionAnimator`):
 * matched keys morph their bounds between the screens. `frameFade` (default) cross-fades
 * the two renders while the frame moves; `frame` moves one render without a dissolve;
 * `fade` cross-fades in place (the backdrop's fade). Unmatched keys ride their screen's
 * keyframes (`fallback`), and nothing happens outside a shared transition.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.elementTransition(element: ScreenTransition.ElementTransition?): Modifier {
    if (element == null) return this
    val morph = LocalSharedMorph.current ?: return this
    val active = morph.active ?: return this
    if (element.morph == ScreenTransition.Morph.fade) return this
    val base = (element.animation?.inlineOrNull ?: active.animation)
    val stagger = element.stagger ?: 0.0
    val timing = if (stagger > 0) base.copy(delay = base.delay + stagger) else base
    val spec = timing.toTransitionSpec()
    val rectSpec = timingRectSpec(timing)
    return with(morph.scope) {
        val state = rememberSharedContentState(element.key)
        if (element.morph == ScreenTransition.Morph.frame) {
            this@elementTransition.sharedElement(state, morph.visibility, boundsTransform = { _, _ -> rectSpec })
        } else {
            this@elementTransition.sharedBounds(
                state, morph.visibility,
                enter = androidx.compose.animation.fadeIn(spec),
                exit = androidx.compose.animation.fadeOut(spec),
                boundsTransform = { _, _ -> rectSpec },
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    }
}

private fun timingRectSpec(a: dev.app8.engine.core.model.Animation.Inline): androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.geometry.Rect> {
    val ms = (a.duration * 1000).toInt().coerceAtLeast(1)
    val delay = (a.delay * 1000).toInt()
    return when (val t = a.timing) {
        is dev.app8.engine.core.model.Animation.Timing.SpringTiming -> {
            val damping = t.spring.damping.coerceIn(0.05, 1.5)
            val omega = 4.0 / (damping.coerceAtMost(1.0) * a.duration.coerceAtLeast(0.05))
            androidx.compose.animation.core.spring(damping.toFloat(), (t.spring.stiffness ?: omega * omega).toFloat().coerceAtLeast(1f))
        }
        is dev.app8.engine.core.model.Animation.Timing.CubicBezier ->
            androidx.compose.animation.core.tween(ms, delay, androidx.compose.animation.core.CubicBezierEasing(t.x1.toFloat(), t.y1.toFloat(), t.x2.toFloat(), t.y2.toFloat()))
        is dev.app8.engine.core.model.Animation.Timing.Curve -> androidx.compose.animation.core.tween(ms, delay, androidx.compose.animation.core.FastOutSlowInEasing)
    }
}

