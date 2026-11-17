package dev.app8.engine.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.Defer
import dev.app8.engine.core.model.Layout
import dev.app8.engine.core.model.StackViewProperties
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.core.model.style.ViewStyle
import dev.app8.engine.render.components.ComponentKind
import dev.app8.engine.render.controls.ShimmerMath

/**
 * A deferred component (DSL "Deferred sections"): a direct `stackView` child with `defer`.
 * Its [placeholder] holds the slot until [build] makes the real node, after the screen is
 * shown. Everything [RenderNode.build] would have used is kept, so the component builds as if
 * it had been built with the screen (same key, scope and scroll context).
 */
internal class DeferredSlot private constructor(
    private val session: ScreenSession,
    private val entity: ComponentEntity<*>,
    private val key: String,
    private val scope: Scope,
    private val stack: RenderNode,
    private val scopes: MutableList<Scope>,
    private val inVerticalScroll: Boolean,
    val defer: Defer,
) {
    lateinit var placeholder: RenderNode
        private set

    /**
     * Builds the component and swaps it in for [placeholder] in the stack's children. Then
     * re-applies the current state's `childStates` of the stack and its ancestors to the new
     * subtree, innermost first, as the screen build does.
     */
    fun build(): RenderNode? {
        val built = RenderNode.build(
            session, entity, key, scope, stack.type, scopes, inVerticalScroll,
            parent = stack, insideDeferred = true,
        )
        stack.children = if (built == null) stack.children.filter { it !== placeholder }
        else stack.children.map { if (it === placeholder) built else it }
        if (built == null) return null
        built.fadesIn = true
        var ancestor: RenderNode? = stack
        while (ancestor != null) {
            ancestor.stateName?.let { RenderNode.applyChildStates(ancestor!!, it, within = built) }
            ancestor = ancestor.parent
        }
        return built
    }

    companion object {
        /**
         * The placeholder for [entity], a child of [stack], queued on the session. Vertical
         * stack: as wide as the stack, `estimatedHeight` tall; horizontal: `estimatedWidth`
         * wide, as tall as the stack.
         */
        fun placeholder(
            session: ScreenSession,
            entity: ComponentEntity<*>,
            key: String,
            scope: Scope,
            stack: RenderNode,
            scopes: MutableList<Scope>,
            inVerticalScroll: Boolean,
        ): RenderNode {
            val defer = entity.defer ?: Defer()
            val slot = DeferredSlot(session, entity, key, scope, stack, scopes, inVerticalScroll, defer)
            val node = RenderNode(
                key = "$key#deferred",
                // Not the component's id: until it is built, nothing can find or target it.
                id = "_deferred@$key",
                type = ComponentType.Known(ComponentType.Key.view),
                base = ComponentContent<ViewProperties, ViewStyle>(
                    properties = ViewProperties(),
                    layout = layout(defer, horizontal = (stack.content.properties as? StackViewProperties)?.axis == "horizontal"),
                ),
                scope = scope,
                session = session,
                parentType = stack.type,
                // The component's own `isHidden` (read in the stack's scope) hides the slot too.
                baseHiddenBinding = entity.isHidden,
                inVerticalScroll = inVerticalScroll,
                parent = stack,
                deferredSlot = slot,
            )
            slot.placeholder = node
            session.deferred.add(slot)
            return node
        }

        internal fun layout(defer: Defer, horizontal: Boolean): Layout = if (horizontal) {
            Layout(width = Layout.Dimension.Fixed(defer.estimatedWidth.toFloat()), height = Layout.Dimension.Fraction(1f))
        } else {
            Layout(width = Layout.Dimension.Fraction(1f), height = Layout.Dimension.Fixed(defer.estimatedHeight.toFloat()))
        }
    }
}

/** The deferred components of one screen presentation, in document order. */
internal class DeferredQueue(private val nanoTime: () -> Long = System::nanoTime) {
    private val slots = ArrayDeque<DeferredSlot>()

    val isEmpty: Boolean get() = slots.isEmpty()
    val size: Int get() = slots.size

    fun add(slot: DeferredSlot) {
        slots.addLast(slot)
    }

    /**
     * Builds deferred components in order until [budgetNanos] is spent: at least one, so the
     * queue always moves. Returns how many were built.
     */
    fun drain(budgetNanos: Long, onBuilt: () -> Unit = {}): Int {
        val start = nanoTime()
        var count = 0
        while (slots.isNotEmpty()) {
            slots.removeFirst().build()
            count++
            onBuilt()
            if (nanoTime() - start >= budgetNanos) break
        }
        return count
    }

    /** Builds everything now (tooling that renders without frames). */
    fun drainAll(onBuilt: () -> Unit = {}): Int = drain(Long.MAX_VALUE, onBuilt)

    fun clear() = slots.clear()

    companion object {
        /** Build time per frame: room for the frame's own work at 60–120 Hz. */
        const val FRAME_BUDGET_NANOS: Long = 7_000_000L
    }
}

/**
 * Drains [session]'s deferred components a few per frame. Frames only tick while the screen
 * is composed in an attached, started window, so nothing is built for a screen that is not
 * shown. The first build waits for the frame after the screen's first one: the screen (and
 * its push or zoom transition) appears at once. Counts as outstanding work, so tooling that
 * waits for an idle screen sees the built components.
 */
@Composable
internal fun DeferredBuilds(session: ScreenSession) {
    LaunchedEffect(session) {
        if (session.deferred.isEmpty) return@LaunchedEffect
        val runtime = session.runtime
        runtime.beginWork()
        try {
            while (!session.deferred.isEmpty && session.isAlive) {
                withFrameNanos { session.buildDeferred(DeferredQueue.FRAME_BUDGET_NANOS) }
            }
        } finally {
            runtime.endWork()
        }
    }
}

/** 0.2 s cubic ease-out, the fade of an arriving deferred component. */
internal const val DEFERRED_FADE_MS: Int = 200
internal val CubicEaseOut = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** Fades a node built from a deferred slot in on its first composition. */
@Composable
internal fun Modifier.deferredFadeIn(node: RenderNode): Modifier {
    if (!node.fadesIn) return this
    val context = LocalContext.current
    val alpha = remember(node) { Animatable(if (reduceMotion(context)) 1f else 0f) }
    LaunchedEffect(node) { alpha.animateTo(1f, tween(DEFERRED_FADE_MS, easing = CubicEaseOut)) }
    return graphicsLayer { this.alpha = alpha.value }
}

/** The placeholder of a deferred component: empty space, or a neutral block with a sweeping highlight. */
internal object DeferredPlaceholderKind : ComponentKind {
    override fun isHidden(node: RenderNode): Boolean = node.bool(node.hiddenBinding) == true

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val slot = node.deferredSlot
        if (slot?.defer?.placeholder != Defer.Placeholder.shimmer) {
            Box(modifier)
            return
        }
        val dark = LocalRenderEnv.current.isDark
        val fill = if (dark) Color(0x1FFFFFFF) else Color(0x0F000000)
        val highlight = Color.White.copy(alpha = if (dark) 0.12f else 0.5f)
        val t by rememberInfiniteTransition(label = "deferred").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "deferred.t",
        )
        val m = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .drawWithContent {
                drawContent()
                val (a, _, b) = ShimmerMath.stops(t)
                val w = size.width
                drawRect(Brush.linearGradient(listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)), Offset(a * w, 0f), Offset(b * w, 0f)))
            }
        Box(m)
    }
}
