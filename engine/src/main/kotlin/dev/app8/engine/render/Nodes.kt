package dev.app8.engine.render

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.app8.engine.navigation.transitions.elementTransition
import dev.app8.engine.navigation.transitions.zoomSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.Indication
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.layout.Frame
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.Animation
import dev.app8.engine.core.model.EntityContent
import dev.app8.engine.core.model.EventTrigger
import dev.app8.engine.core.model.StatefulContent
import dev.app8.engine.core.model.Trigger
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.core.model.style.BaseStyle
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.debug.Recompositions
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.style.dslClip
import dev.app8.engine.style.dslMaterial
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The solved layout of the screen being drawn. Read only in measure blocks, so a new solve re-lays-out without recomposing. */
internal val LocalSolved = staticCompositionLocalOf<State<Solved>> { error("No solved layout") }

internal val LocalDispatcher = staticCompositionLocalOf<ActionDispatcher> { error("No ActionDispatcher") }

/** False under a component whose `interaction.enabled` is false (iOS `isUserInteractionEnabled`). */
internal val LocalInteractionEnabled = compositionLocalOf { true }

/**
 * One DSL component: universal behavior (visibility, alpha, transforms, material, taps,
 * `onEvent`, z-index) around the component's own content. Always emits exactly one layout
 * node tagged with [RenderNode.key], so its parent can place it at its solved frame.
 */
@Composable
internal fun NodeView(node: RenderNode) {
    Recompositions.Count(node.key)
    val kind = ComponentRegistry.kind(node)
    // An animated `isHidden` keeps the component drawn while it fades out (LayoutTransition).
    val transition = LocalLayoutTransition.current?.takeIf { node.hiddenAnimation != null }
    val hidden = kind.isHidden(node)
    if (hidden && transition?.keepsDrawn(node.key) != true) {
        Box(Modifier.layoutId(node.key))
        return
    }
    val env = LocalRenderEnv.current
    val content = node.content
    OnEvents(node, (content as? StatefulContent)?.onEvent)
    StateBinding(node)

    val interaction = content.interaction
    val enabledHere = interaction?.enabled?.let { node.bool(it) } ?: true
    val zIndex = interaction?.zIndex?.let { node.number(it) }?.toFloat()
    val style = content.style as? BaseStyle
    val props = content.properties as? ViewProperties

    // Animated alpha / transforms (view properties) on top of the static style values.
    // A `repeat` animation loops from the property's start value (see animatedDslFloat).
    val styleAlpha = (style?.alpha ?: 1.0).toFloat()
    val alphaTarget = (props?.alpha?.let { node.number(it.value) } ?: 1.0).toFloat() * styleAlpha
    val alpha by animatedDslFloat(alphaTarget, props?.alpha?.animation, styleAlpha, node, "alpha")
    val styleScale = (style?.transform?.scale ?: 1.0).toFloat()
    val scaleTarget = (props?.transformScale?.let { node.number(it.value) } ?: 1.0).toFloat() * styleScale
    val scale by animatedDslFloat(scaleTarget, props?.transformScale?.animation, styleScale, node, "scale")
    val txTarget = (props?.transformTranslateX?.let { node.number(it.value) } ?: 0.0).toFloat()
    val tx by animatedDslFloat(txTarget, props?.transformTranslateX?.animation, 0f, node, "tx")
    val styleTy = (style?.transform?.translateY ?: 0.0).toFloat()
    val tyTarget = (props?.transformTranslateY?.let { node.number(it.value) } ?: 0.0).toFloat() + styleTy
    val ty by animatedDslFloat(tyTarget, props?.transformTranslateY?.animation, styleTy, node, "ty")
    val styleRotation = (style?.transform?.rotation ?: 0.0).toFloat()
    val rotationTarget = (props?.transformRotation?.let { node.number(it.value) } ?: 0.0).toFloat() + styleRotation
    val rotation by animatedDslFloat(rotationTarget, props?.transformRotation?.animation, styleRotation, node, "rotation")
    val blend = style?.blendMode?.toCompose()

    val material = env.material(style?.material, node)
    val bgColor = node.hexColor(props?.backgroundColor?.value)
    val bgTarget = bgColor ?: Color.Transparent
    val bg by animateColorAsState(bgTarget, props?.backgroundColor?.animation.toSpecOf(node), label = "bg")

    // A deferred component that just arrived fades in over its placeholder's slot.
    var m: Modifier = Modifier.layoutId(node.key).testTag(node.id).deferredFadeIn(node)
    if (transition != null) {
        // Fades on the transition's timing; in a stack, clipped to the animating bounds
        // around content measured at its laid-out size.
        val key = node.key
        m = m.graphicsLayer {
            this.alpha = transition.alpha(key)
            clip = transition.clips(key)
        }.revealLayout(transition, key)
    }
    // `content.transition.key`: morphs with its counterpart during a `shared` transition.
    val element = (content as? dev.app8.engine.core.model.ComponentContent<*, *>)?.elementTransition
    m = m.elementTransition(element)
    // A keyed element is a possible source of a native zoom (`zoom` + `sourceKey`).
    if (element != null) {
        val corner = cornerOf(node, style, props, env).toFloat()
        val photo = if (kind == dev.app8.engine.render.components.ImageKind) dev.app8.engine.render.components.imageRef(node) else null
        m = m.zoomSource(node.runtime.zoomSources, node.session, node.string(element.key), corner * env.density.density, photo)
    }
    if (zIndex != null) m = m.zIndex(zIndex)
    // Stretchy top: the hero of the enclosing scroll view scales to fill the pull.
    val stretch = LocalHeroStretch.current
    if (stretch != null && stretch.heroKey == node.key) m = m.heroStretch(stretch.pull)
    m = m.graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
        translationX = tx * density
        translationY = ty * density
        rotationZ = rotation
        // `style.blendMode` (iOS `layer.compositingFilter`): the layer draws offscreen, then
        // blends onto what its parent drew before it (siblings below, a video, the background).
        if (blend != null) {
            blendMode = blend
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        }
    }
    if (material != null) m = m.dslMaterial(material, env.appearance, style?.glassAppearance)
    if (bgColor != null || bg != Color.Transparent) {
        val corner = props?.cornerRadius
        m = if (corner != null && corner > 0) m.background(bg, androidx.compose.foundation.shape.RoundedCornerShape(corner.toFloat().let { androidx.compose.ui.unit.Dp(it) }))
        else m.background(bg)
    }
    if (material != null && kind.clipsToMaterial) m = m.dslClip(material)

    val parentEnabled = LocalInteractionEnabled.current
    // Touches stop the moment a component starts disappearing.
    val enabled = parentEnabled && enabledHere && !hidden
    if (enabled && !kind.handlesTaps) {
        // Android press feedback, clipped to the view's corners, unless the DSL has its own touch state.
        val indication = if (PressFeedback.wanted(content)) pressIndication(cornerOf(node, style, props, env)) else null
        m = m.then(tapModifier(node, content, indication = indication))
    }

    CompositionLocalProvider(LocalInteractionEnabled provides enabled) {
        kind.Render(node, m)
    }
}

/** A view's corner radius in points: its material's fixed `corner`, else `cornerRadius`, else 0. */
internal fun cornerOf(node: RenderNode, style: BaseStyle?, props: ViewProperties?, env: RenderEnv): Double =
    style?.material?.let { env.material(it, node) }?.layers
        ?.firstNotNullOfOrNull { ((it as? dev.app8.engine.core.model.style.StyleEntity<*>)?.content as? dev.app8.engine.core.model.style.Corner)?.radius as? dev.app8.engine.core.model.style.Corner.Radius.Fixed }?.value
        ?: props?.cornerRadius ?: 0.0

/**
 * Tap / long-press / press-state handling for a node, through the dispatcher. Null when
 * the component declares nothing to do on touch (it then lets touches through, like a UIView
 * without gesture recognizers).
 */
@Composable
internal fun tapModifier(node: RenderNode, content: EntityContent, indication: Indication?, always: Boolean = false): Modifier {
    val actions = content.actions
    val analytics = content.analytics
    val hasTap = always || actions?.containsKey(ActionTrigger.tap) == true || analytics?.containsKey(ActionTrigger.tap) == true
    var hasLong = actions?.containsKey(ActionTrigger.longPress) == true || analytics?.containsKey(ActionTrigger.longPress) == true
    val triggers = (content as? StatefulContent)?.triggers
    // `contextMenu` replaces a `longPress` action on the same component.
    val menu = node.activeContextMenu(content)
    if (menu != null && hasLong) {
        node.runtime.diagnostics.warn("contextMenu.longPressIgnored", "'${node.id}' has both contextMenu and longPress; contextMenu wins")
        hasLong = false
    }
    if (!hasTap && !hasLong && menu == null && triggers.isNullOrEmpty()) return Modifier
    val dispatcher = LocalDispatcher.current
    val source = remember { MutableInteractionSource() }
    var m: Modifier = Modifier
    val menuState = remember(node) { ContextMenuState() }
    val openMenu = rememberOpenMenu(menuState)
    if (menu != null) {
        ContextMenuSheet(node, menu, menuState)
        m = m.contextMenuSemantics(node, menu, openMenu) { item -> runMenuActions(dispatcher, node, item.actions) }
        // Without a tap of its own, leave taps to the parent (a collection cell's onItemTap).
        if (!hasTap) m = m.longPressOnly(node, openMenu)
    }
    if (!triggers.isNullOrEmpty()) {
        m = m.pointerInput(node) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                triggers[Trigger.touchDown]?.let { dispatcher.setState(node, it) }
                waitForUpOrCancellation()
                triggers[Trigger.touchUp]?.let { dispatcher.setState(node, it) }
            }
        }
    }
    if (hasTap || hasLong) {
        // `accessibility.traits: ["tab"]` (custom tab bar items) reads as a tab.
        val tab = node.content.accessibility?.traits?.contains(dev.app8.engine.core.model.Accessibility.Trait.TAB) == true
        m = m.combinedClickable(
            interactionSource = source,
            indication = indication,
            role = if (tab) androidx.compose.ui.semantics.Role.Tab else null,
            onLongClick = when {
                menu != null -> openMenu
                hasLong -> ({ dispatcher.dispatchTrigger(node, ActionTrigger.longPress) })
                else -> null
            },
            onLongClickLabel = if (menu != null) "Show actions" else null,
            onClick = { if (hasTap) dispatcher.dispatchTrigger(node, ActionTrigger.tap) },
        )
    }
    return m
}

/** A `{{…}}` `defaultStateName`: re-evaluated when a variable it reads changes (iOS `setupStateBinding`). */
@Composable
private fun StateBinding(node: RenderNode) {
    val expr = node.stateBinding ?: return
    val dispatcher = LocalDispatcher.current
    LaunchedEffect(node) {
        snapshotFlow { node.string(expr) }.collect { name ->
            if (!name.isNullOrEmpty() && name != node.stateName) dispatcher.setState(node, name)
        }
    }
}

/** `onEvent`: appear (on first composition), timer (delay, interval, repeats), disappear. */
@Composable
private fun OnEvents(node: RenderNode, events: List<EventTrigger>?) {
    if (events.isNullOrEmpty()) return
    val dispatcher = LocalDispatcher.current
    LaunchedEffect(node) {
        for (e in events) {
            when (e.event) {
                EventTrigger.EventType.appear -> {
                    if ((e.delay ?: 0.0) > 0) delay(((e.delay ?: 0.0) * 1000).toLong())
                    dispatcher.execute(node, e.action)
                }
                EventTrigger.EventType.timer -> Unit
                EventTrigger.EventType.disappear -> Unit
            }
        }
    }
    for ((i, e) in events.withIndex()) {
        if (e.event != EventTrigger.EventType.timer) continue
        LaunchedEffect(node, i) {
            // A pending one-shot timer counts as outstanding work (tooling waits for it).
            val runtime = node.runtime
            runtime.beginWork()
            try {
                delay(((e.delay ?: 0.0) * 1000).toLong())
                runtime.tickClock()
                dispatcher.execute(node, e.action)
            } finally {
                runtime.endWork()
            }
            if (e.repeats == true) {
                val interval = ((e.interval ?: e.delay ?: 1.0) * 1000).toLong().coerceAtLeast(16)
                while (true) {
                    delay(interval)
                    runtime.tickClock()
                    dispatcher.execute(node, e.action)
                }
            }
        }
    }
    DisposableEffect(node) {
        onDispose { events.filter { it.event == EventTrigger.EventType.disappear }.forEach { dispatcher.execute(node, it.action) } }
    }
}

/**
 * Places [node]'s children at their solved frames (UIView subviews). The frames are read
 * here, in the measure block: a new solve only re-lays-out.
 */
@Composable
internal fun ChildrenLayout(node: RenderNode, modifier: Modifier = Modifier) {
    val solved = LocalSolved.current
    Layout(
        content = { for (child in node.children) key(child.key) { NodeView(child) } },
        modifier = modifier,
    ) { measurables, constraints ->
        val frames = solved.value.local
        val d = density
        val placeables = measurables.map { m ->
            val f = frames[m.layoutId as? String]
            val w = f?.let { pxSize(it.width, d) } ?: 0
            val h = f?.let { pxSize(it.height, d) } ?: 0
            m.measure(fixedConstraints(w, h)) to f
        }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        layout(width, height) {
            for ((p, f) in placeables) {
                if (f == null) continue
                p.place(px(f.x, d), px(f.y, d))
            }
        }
    }
}

/** Snap a point coordinate to the pixel grid the solver snapped to (a NaN frame places at 0). */
internal fun px(points: Double, density: Float): Int = (points * density).let { if (it.isNaN()) 0 else it.roundToInt() }

/** A solved length in whole pixels for measuring: never negative, NaN as 0, at most [MAX_LAYOUT_PX]. */
internal fun pxSize(points: Double, density: Float): Int = px(points, density).coerceIn(0, MAX_LAYOUT_PX)

/**
 * A DSL inset or padding in dp. Compose rejects negative padding (it throws), so a negative
 * (or NaN) value pads nothing; iOS would shift the content instead.
 */
internal val Double.padDp: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(if (this > 0.0) toFloat() else 0f)

/**
 * A DSL length for a size modifier, in dp: never negative (NaN as 0) and at most
 * [MAX_SIZE_DP]. Compose throws for a size it cannot pack into its constraints (about
 * 262,000 px), which an out-of-range DSL value like `itemHeight: 999999` would reach.
 */
internal val Double.sizeDp: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(if (this > 0.0) minOf(this, MAX_SIZE_DP).toFloat() else 0f)

/** Largest size modifier [sizeDp] produces: 262,000 px at 5x density. */
internal const val MAX_SIZE_DP: Double = 50_000.0

/** Warns (once per message) when an inset [what] of this node has a negative side, padded as 0 ([padDp]). */
internal fun RenderNode.warnNegativeInsets(what: String, vararg sides: Double?) {
    if (sides.any { it != null && it < 0.0 }) runtime.diagnostics.warn("layout.negativeInset", "$what of $key is negative; Android pads 0 there")
}

/** The largest width or height Compose's `layout(width, height)` accepts. */
internal const val MAX_LAYOUT_PX: Int = (1 shl 24) - 1

/**
 * Fixed constraints for a solved frame. Compose packs constraints into 64 bits and throws for a
 * size it cannot represent (an out-of-range DSL length like `height: 999999`); such a frame is
 * clamped to the largest representable size instead, keeping the longer side.
 */
internal fun fixedConstraints(width: Int, height: Int): Constraints =
    if (width >= height) Constraints.fitPrioritizingWidth(width, width, height, height)
    else Constraints.fitPrioritizingHeight(width, width, height, height)

internal fun Frame.pxWidth(density: Float) = (width * density).roundToInt()

/** DSL animation → Compose spec. Null keeps the change instantaneous (iOS without a descriptor). */
internal fun Animation?.toSpec(node: RenderNode): AnimationSpec<Float> = toSpecOf(node)

internal fun <T> Animation?.toSpecOf(node: RenderNode): AnimationSpec<T> = this?.toSpecOrNull(node.runtime) ?: snap()

/** DSL animation → Compose spec; null when it does not resolve (a pointer to nothing). */
internal fun <T> Animation.toSpecOrNull(runtime: dev.app8.engine.runtime.App8Runtime): AnimationSpec<T>? {
    val inline = inline { id -> runtime.app?.animations?.firstNotNullOfOrNull { a -> (a as? Animation.Inline)?.takeIf { it.id == id } } }
        ?: return null
    val ms = (inline.duration * 1000).roundToInt()
    val delayMs = (inline.delay * 1000).roundToInt()
    return when (val t = inline.timing) {
        is Animation.Timing.SpringTiming -> spring(dampingRatio = t.spring.damping.toFloat().coerceIn(0.05f, 2f))
        is Animation.Timing.CubicBezier -> tween(ms, delayMs, CubicBezierEasing(t.x1.toFloat(), t.y1.toFloat(), t.x2.toFloat(), t.y2.toFloat()))
        is Animation.Timing.Curve -> tween(
            ms, delayMs,
            when (t.curve) {
                Animation.NamedCurve.linear -> LinearEasing
                Animation.NamedCurve.easeIn -> FastOutLinearInEasing
                Animation.NamedCurve.easeOut -> LinearOutSlowInEasing
                Animation.NamedCurve.easeInOut -> FastOutSlowInEasing
            },
        )
    }
}

/** DSL `blendMode` → Compose (CSS / Figma semantics); `normal` draws as usual. */
internal fun dev.app8.engine.core.model.style.BlendMode.toCompose(): androidx.compose.ui.graphics.BlendMode? {
    val B = androidx.compose.ui.graphics.BlendMode
    return when (this) {
        dev.app8.engine.core.model.style.BlendMode.normal -> null
        dev.app8.engine.core.model.style.BlendMode.multiply -> B.Multiply
        dev.app8.engine.core.model.style.BlendMode.screen -> B.Screen
        dev.app8.engine.core.model.style.BlendMode.overlay -> B.Overlay
        dev.app8.engine.core.model.style.BlendMode.darken -> B.Darken
        dev.app8.engine.core.model.style.BlendMode.lighten -> B.Lighten
        dev.app8.engine.core.model.style.BlendMode.colorDodge -> B.ColorDodge
        dev.app8.engine.core.model.style.BlendMode.colorBurn -> B.ColorBurn
        dev.app8.engine.core.model.style.BlendMode.softLight -> B.Softlight
        dev.app8.engine.core.model.style.BlendMode.hardLight -> B.Hardlight
        dev.app8.engine.core.model.style.BlendMode.difference -> B.Difference
        dev.app8.engine.core.model.style.BlendMode.exclusion -> B.Exclusion
        dev.app8.engine.core.model.style.BlendMode.hue -> B.Hue
        dev.app8.engine.core.model.style.BlendMode.saturation -> B.Saturation
        dev.app8.engine.core.model.style.BlendMode.color -> B.Color
        dev.app8.engine.core.model.style.BlendMode.luminosity -> B.Luminosity
    }
}
