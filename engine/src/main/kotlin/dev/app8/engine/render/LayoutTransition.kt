package dev.app8.engine.render

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.layout.StackAxis
import dev.app8.engine.core.model.Animation

/**
 * The shape of a solved tree that an animated `isHidden` change needs: parents, stack
 * children in order, stack axes, hidden components and the components whose `isHidden`
 * carries an `animation`.
 */
@Immutable
internal class LayoutStructure(
    val parent: Map<String, String>,
    /** Arranged subviews of each stack, in order. */
    val stackChildren: Map<String, List<String>>,
    /** Stack key → vertical axis. */
    val vertical: Map<String, Boolean>,
    val hidden: Set<String>,
    /** Nearest hidden strict ancestor of each component under a hidden one. */
    val hiddenAncestor: Map<String, String>,
    val animated: Map<String, Animation>,
) {
    fun inStack(key: String): Boolean = parent[key]?.let { it in vertical } == true

    companion object {
        fun of(root: LayoutNode, animated: Map<String, Animation>): LayoutStructure {
            val parent = HashMap<String, String>()
            val stackChildren = HashMap<String, List<String>>()
            val vertical = HashMap<String, Boolean>()
            val hidden = HashSet<String>()
            val hiddenAncestor = HashMap<String, String>()
            fun visit(n: LayoutNode, hiddenAbove: String?) {
                if (n.hidden) hidden += n.key
                if (hiddenAbove != null) hiddenAncestor[n.key] = hiddenAbove
                n.stack?.let { s ->
                    vertical[n.key] = s.axis == StackAxis.VERTICAL
                    stackChildren[n.key] = n.children.map { it.key }
                }
                val below = if (n.hidden) n.key else hiddenAbove
                for (c in n.children) {
                    parent[c.key] = n.key
                    visit(c, below)
                }
            }
            visit(root, null)
            return LayoutStructure(parent, stackChildren, vertical, hidden, hiddenAncestor, animated.toMap())
        }
    }
}

/** The screen's [LayoutTransition]; null inside collection cells (bound and reused at once). */
internal val LocalLayoutTransition = staticCompositionLocalOf<LayoutTransition?> { null }

/**
 * Animated `isHidden` (app8-dsl animations.md "Animated visibility"): when a solve hides or
 * shows a component whose `isHidden` carries an `animation`, the frames of the whole screen
 * move from where they are drawn now to the new solve on that curve, as one layout pass
 * (UIKit `UIView.animate { view.isHidden = …; root.layoutIfNeeded() }`).
 *
 * - An arranged subview grows from 0 to its laid-out size along the stack axis (or shrinks
 *   to 0); it is measured at its full size and clipped to the animating bounds, so its content
 *   is revealed by the moving edge, not squeezed. Its alpha fades on the same timing.
 * - Siblings after it, the stack, its ancestors and scroll content sizes interpolate on the
 *   same curve. A scroll view past its new end follows the shrinking content back.
 * - Anywhere else the component keeps its frame and cross-fades.
 * - A change while one runs starts from the frames drawn now. The first solve, a change
 *   without an animation and Reduce Motion (animator duration scale 0) apply at once.
 *
 * The frames are read in measure blocks ([state]), so a running transition only re-lays-out.
 * Pure state machine apart from the frame loop ([run]); [onFrame] is driven by tests directly.
 */
internal class LayoutTransition(
    private val resolve: (Animation) -> AnimationSpec<Float>?,
    private val reduceMotion: () -> Boolean = { false },
) {
    /** True while frames are being interpolated. */
    var running: Boolean by mutableStateOf(false)
        private set

    /** Animation progress (the spec's eased value, 0 → 1; a spring may overshoot). */
    private val progress = mutableFloatStateOf(1f)

    /** Animated-hidden components that stay composed: visible ones, and ones fading out. */
    private var drawn: Set<String> by mutableStateOf(emptySet())

    private var processed: Solved? = null
    private var idle: Solved = Solved.EMPTY
    private var fromLocal: Map<String, Frame> = emptyMap()
    private var toLocal: Map<String, Frame> = emptyMap()
    private var fromContent: Map<String, Size> = emptyMap()
    private var toContent: Map<String, Size> = emptyMap()
    private var fromAlpha: Map<String, Float> = emptyMap()

    /** The components changing visibility → true when appearing. */
    private var toggled: Map<String, Boolean> = emptyMap()
    private val lastVisible = HashMap<String, Frame>()
    private var spec: AnimationSpec<Float>? = null
    private var animation: TargetBasedAnimation<Float, *>? = null
    private var startNanos = Long.MIN_VALUE
    private var cacheProgress = Float.NaN
    private var cache: Solved = Solved.EMPTY

    /** A [State] whose value is the frames to draw for [target] now. */
    fun state(target: State<Solved>): State<Solved> = object : State<Solved> {
        override val value: Solved get() = displayed(target.value)
    }

    /** The frames to draw for [target] at the current progress. */
    fun displayed(target: Solved): Solved {
        if (target !== processed) process(target)
        val p = progress.floatValue
        if (!running) return idle
        if (p == cacheProgress) return cache
        val t = processed ?: return idle
        val local = HashMap<String, Frame>(toLocal.size)
        for ((k, to) in toLocal) {
            val from = fromLocal[k]
            local[k] = if (from == null || from == to) to else lerp(from, to, p)
        }
        val content = HashMap<String, Size>(toContent.size)
        for ((k, to) in toContent) {
            val from = fromContent[k]
            content[k] = if (from == null || from == to) to else Size(lerp(from.width, to.width, p), lerp(from.height, to.height, p))
        }
        cache = t.copy(local = local, contentSizes = content)
        cacheProgress = p
        return cache
    }

    /** Whether [key], hidden by its solve, is still drawn (fading out). Read in composition. */
    fun keepsDrawn(key: String): Boolean = key in drawn

    /** The alpha multiplier of an animated-hidden component. Read in the draw phase. */
    fun alpha(key: String): Float {
        val p = progress.floatValue
        val appearing = toggled[key]
        if (!running || appearing == null) {
            return if (processed?.structure?.hidden?.contains(key) == true) 0f else 1f
        }
        val from = fromAlpha[key] ?: if (appearing) 0f else 1f
        return lerp(from, if (appearing) 1f else 0f, p).coerceIn(0f, 1f)
    }

    /** Whether [key] is clipped to its animating bounds (an arranged subview growing or shrinking). */
    fun clips(key: String): Boolean = running && key in toggled && processed?.structure?.inStack(key) == true

    /** The laid-out size [key] is measured at while its bounds animate; null = its frame. */
    fun fullSize(key: String): Frame? = if (clips(key)) lastVisible[key] else null

    /** Advances a running transition to the frame at [frameNanos]. */
    fun onFrame(frameNanos: Long) {
        val anim = animation ?: return finish()
        if (startNanos == Long.MIN_VALUE) startNanos = frameNanos
        val play = frameNanos - startNanos
        if (play >= anim.durationNanos) finish() else progress.floatValue = anim.getValueFromNanos(play)
    }

    /** The frame loop: ticks [onFrame] while a transition runs. */
    suspend fun run() {
        snapshotFlow { running }.collect { r ->
            while (r && running) withFrameNanos(::onFrame)
        }
    }

    private fun finish() {
        running = false
        animation = null
        toggled = emptyMap()
        fromAlpha = emptyMap()
        progress.floatValue = 1f
        cacheProgress = Float.NaN
        drawn = visibleAnimated(processed?.structure)
    }

    private fun process(target: Solved) {
        val prev = processed
        processed = target
        val s1 = target.structure
        val s0 = prev?.structure
        if (prev == null || s1 == null || s0 == null) return snap(target)
        val changed = s1.animated.keys.filter { k ->
            k in prev.local && k in target.local && (k in s0.hidden) != (k in s1.hidden)
        }
        val wasRunning = running
        if (changed.isEmpty() && !wasRunning) return snap(target)
        val newSpec = changed.firstNotNullOfOrNull { k -> s1.animated[k]?.let(resolve) } ?: spec.takeIf { wasRunning }
        if (newSpec == null || reduceMotion()) return snap(target)

        // Where everything is drawn now: the starting point (UIKit beginFromCurrentState).
        val now = displayed(prev, wasRunning)
        val nowAlpha = HashMap<String, Float>()
        val all = LinkedHashMap<String, Boolean>()
        if (wasRunning) for (k in toggled.keys) { nowAlpha[k] = alpha(k); all[k] = k !in s1.hidden }
        for (k in changed) {
            if (k !in nowAlpha) nowAlpha[k] = if (k in s0.hidden) 0f else 1f
            all[k] = k !in s1.hidden
        }
        recordVisible(target, s1)
        val to = effective(target, s1, now.local, all)
        val from = HashMap(now.local)
        for ((k, f) in to) {
            val cur = from[k]
            val appearingAbove = s1.hiddenAncestor[k] == null && ancestorIn(k, s1, all) { it }
            when {
                // An appearing component's content is laid out at its final frames from the start.
                cur == null || appearingAbove -> from[k] = f
                // A toggled arranged subview animates along the stack axis only.
                k in all && s1.inStack(k) -> from[k] = if (s1.vertical[s1.parent[k]] == true) cur.copy(x = f.x, width = f.width) else cur.copy(y = f.y, height = f.height)
            }
        }
        fromLocal = from
        toLocal = to
        fromContent = now.contentSizes
        toContent = target.contentSizes
        fromAlpha = nowAlpha
        toggled = all
        spec = newSpec
        animation = TargetBasedAnimation(newSpec, Float.VectorConverter, 0f, 1f)
        startNanos = Long.MIN_VALUE
        idle = target.copy(local = effective(target, s1, emptyMap(), emptyMap()))
        cacheProgress = Float.NaN
        progress.floatValue = 0f
        running = true
        drawn = visibleAnimated(s1) + all.filterValues { !it }.keys
    }

    /** The frames drawn for the previous target: interpolated when running, else its idle frames. */
    private fun displayed(prev: Solved, wasRunning: Boolean): Solved {
        if (!wasRunning) return idle
        cacheProgress = Float.NaN
        val saved = processed
        processed = prev
        return try { displayed(prev) } finally { processed = saved }
    }

    private fun snap(target: Solved) {
        val s = target.structure
        if (s != null) recordVisible(target, s)
        idle = if (s == null) target else target.copy(local = effective(target, s, emptyMap(), emptyMap()))
        running = false
        animation = null
        toggled = emptyMap()
        fromAlpha = emptyMap()
        progress.floatValue = 1f
        cacheProgress = Float.NaN
        drawn = visibleAnimated(s)
    }

    /** Records the laid-out frames of the visible components (the size an arranged subview grows to). */
    private fun recordVisible(target: Solved, s: LayoutStructure) {
        for ((k, f) in target.local) if (k !in s.hidden && k !in s.hiddenAncestor) lastVisible[k] = f
    }

    /**
     * The frames of [target] as drawn: a hidden arranged subview sits collapsed where it would
     * appear (after the previous visible sibling, its laid-out cross size); the content of a
     * component fading out keeps the frames it has [now].
     */
    private fun effective(target: Solved, s: LayoutStructure, now: Map<String, Frame>, toggled: Map<String, Boolean>): Map<String, Frame> {
        val out = HashMap<String, Frame>(target.local.size)
        for ((k, f) in target.local) {
            val above = s.hiddenAncestor[k]
            out[k] = when {
                above != null && toggled[above] == false -> now[k] ?: f
                above == null && k in s.hidden && s.inStack(k) -> collapsed(k, f, target, s)
                else -> f
            }
        }
        return out
    }

    private fun collapsed(key: String, raw: Frame, target: Solved, s: LayoutStructure): Frame {
        val stack = s.parent.getValue(key)
        val vertical = s.vertical.getValue(stack)
        val siblings = s.stackChildren[stack].orEmpty()
        val prev = siblings.subList(0, siblings.indexOf(key).coerceAtLeast(0)).lastOrNull { it !in s.hidden }?.let { target.local[it] }
        val base = lastVisible[key] ?: raw
        return if (vertical) Frame(base.x, prev?.maxY ?: 0.0, base.width, 0.0) else Frame(prev?.maxX ?: 0.0, base.y, 0.0, base.height)
    }

    private inline fun ancestorIn(key: String, s: LayoutStructure, toggled: Map<String, Boolean>, test: (Boolean) -> Boolean): Boolean {
        var cur = s.parent[key]
        while (cur != null) {
            toggled[cur]?.let { if (test(it)) return true }
            cur = s.parent[cur]
        }
        return false
    }

    private fun visibleAnimated(s: LayoutStructure?): Set<String> = s?.animated?.keys?.filterTo(HashSet()) { it !in s.hidden } ?: emptySet()

    private companion object {
        fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun lerp(a: Frame, b: Frame, t: Float) = Frame(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.width, b.width, t).coerceAtLeast(0.0), lerp(a.height, b.height, t).coerceAtLeast(0.0))
    }
}

/**
 * An animated-hidden component while its stack bounds animate: measured at its laid-out size
 * and reporting the animating size to its stack (the caller clips), so the content is revealed
 * by the moving edge instead of squeezed.
 */
internal fun Modifier.revealLayout(transition: LayoutTransition, key: String): Modifier = layout { measurable, constraints ->
    val full = transition.fullSize(key)
    if (full == null) {
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
    } else {
        val p = measurable.measure(fixedConstraints(pxSize(full.width, density), pxSize(full.height, density)))
        val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
        layout(w, h) { p.place(0, 0) }
    }
}

/** Reduce Motion on Android: animations removed (animator duration scale 0). */
internal fun reduceMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
