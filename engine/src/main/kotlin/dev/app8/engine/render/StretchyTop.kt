package dev.app8.engine.render

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.ScrollViewProperties
import dev.app8.engine.core.model.StackViewProperties

/**
 * `scrollView.stretchesTopChild` (app8-dsl `scroll-view.md#stretchy-top`, 0.7.11): the
 * overscroll of a pull-down at the top. It replaces the system stretch overscroll for that
 * scroll view: the content moves down by [pull] and the hero scales about its bottom centre
 * by `(h + pull) / h`, so its top stays on the scroll view's top edge.
 *
 * [pull] is in px. A drag past the top adds to it with a rubber-band resistance that grows
 * towards [maxPull]; a drag back up relaxes it before the content scrolls; the release
 * springs it back to 0.
 */
@Stable
internal class StretchyTopOverscroll(private val maxPull: () -> Float) : OverscrollEffect {
    private val pullState: MutableFloatState = mutableFloatStateOf(0f)

    /** The current pull distance in px (0 at rest). */
    val pull: State<Float> get() = pullState

    override val isInProgress: Boolean get() = pullState.floatValue > 0f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // A drag up while pulled first relaxes the pull, then scrolls.
        var relaxed = 0f
        val current = pullState.floatValue
        if (current > 0f && delta.y < 0f) {
            relaxed = maxOf(delta.y, -current)
            pullState.floatValue = (current + relaxed).coerceAtLeast(0f)
        }
        val left = Offset(delta.x, delta.y - relaxed)
        val consumed = performScroll(left)
        val over = left.y - consumed.y
        if (over > 0.5f && source == NestedScrollSource.UserInput) {
            pullState.floatValue = rubberBand(pullState.floatValue, over, maxPull())
            return Offset(consumed.x, delta.y)
        }
        return Offset(consumed.x, relaxed + consumed.y)
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pullState.floatValue > 0f) {
            // Released while pulled: spring back to rest (UIScrollView's bounce back).
            animate(pullState.floatValue, 0f, 0f, spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                pullState.floatValue = v.coerceAtLeast(0f)
            }
            pullState.floatValue = 0f
            return
        }
        performFling(velocity)
    }

    internal companion object {
        /** iOS-like resistance: half the finger's travel at rest, nothing once [max] is reached. */
        fun rubberBand(current: Float, delta: Float, max: Float): Float {
            if (max <= 0f) return current + delta * 0.5f
            val resistance = (1f - current / max).coerceIn(0f, 1f)
            return (current + delta * 0.5f * resistance).coerceIn(0f, max)
        }
    }
}

/** The hero of a stretchy scroll view and its pull, for [NodeView] to scale. */
@Stable
internal class HeroStretch(val heroKey: String?, val pull: State<Float>)

/** The innermost stretchy scroll view's hero; null outside one. */
internal val LocalHeroStretch = staticCompositionLocalOf<HeroStretch?> { null }

/** Scales a hero uniformly about its bottom centre to fill the [pull] (px) above it. */
internal fun Modifier.heroStretch(pull: State<Float>): Modifier = graphicsLayer {
    val h = size.height
    val d = pull.value
    if (h > 0f && d > 0f) {
        val s = (h + d) / h
        scaleX = s
        scaleY = s
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}

/**
 * The hero of a stretchy [scroll] view (scroll-view.md, "Stretchy top"): its first child;
 * when that is a container, its first child, repeated. A vertical `stackView` descends into
 * its first arranged child; a `view` into its first child when that child is pinned to the
 * view's top and shorter than it (a hero box whose first child fills it, such as a
 * full-bleed image under overlays, stays the hero so its overlays scale with it). Hidden
 * children (no solved height) are skipped.
 */
internal fun stretchHeroKey(scroll: RenderNode, s: Solved): String? {
    fun shown(n: RenderNode) = (s.local[n.key]?.height ?: 0.0) > 0.0
    var hero = scroll.children.firstOrNull(::shown) ?: return null
    while (true) {
        val first = hero.children.firstOrNull(::shown) ?: return hero.key
        val next = when ((hero.type as? ComponentType.Known)?.key) {
            ComponentType.Key.stackView ->
                first.takeIf { (hero.content.properties as? StackViewProperties)?.axis != "horizontal" }
            ComponentType.Key.view -> {
                val f = s.local[first.key]
                val self = s.local[hero.key]
                first.takeIf { f != null && self != null && f.y <= 0.5 && f.height < self.height - 0.5 }
            }
            else -> null
        } ?: return hero.key
        hero = next
    }
}

/**
 * The screen's main vertical scroll view (navigation.md, bottom bar layout): the largest
 * vertical `scrollView` that is not inside another vertical scroll view.
 */
internal fun mainVerticalScrollKey(root: RenderNode, s: Solved): String? {
    var best: String? = null
    var bestArea = 0.0
    fun visit(n: RenderNode) {
        val isVerticalScroll = (n.type as? ComponentType.Known)?.key == ComponentType.Key.scrollView &&
            (n.content.properties as? ScrollViewProperties)?.direction != ScrollViewProperties.Direction.horizontal
        if (isVerticalScroll) {
            val f = s.absolute[n.key]
            val area = if (f == null) 0.0 else f.width * f.height
            if (area > bestArea) {
                bestArea = area
                best = n.key
            }
            return // nested scroll views are not the main one
        }
        n.children.forEach(::visit)
    }
    visit(root)
    return best
}
