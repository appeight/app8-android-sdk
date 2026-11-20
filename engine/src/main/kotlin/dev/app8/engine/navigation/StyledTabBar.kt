package dev.app8.engine.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.Component
import dev.app8.engine.core.model.Tab
import dev.app8.engine.core.model.TabBarStyle
import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.collection.CellView
import dev.app8.engine.style.dslMaterial
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** Default height of a `text` bar above the bottom safe area (thinner than the 64dp platform bar; same as the web renderer). */
internal const val TEXT_TAB_BAR_HEIGHT = 56.0

/** Default height of a `component` bar above the bottom safe area. */
internal const val COMPONENT_TAB_BAR_HEIGHT = 56.0

/** The indicator's move between tabs (tab-bar-screen.md: 0.3 s cubic ease-out). */
internal const val TAB_INDICATOR_MILLIS = 300

/** Indicator geometry (dp), shared with the iOS and web renderers. */
private const val CAPSULE_PAD_X = 28f
private const val CAPSULE_PAD_Y = 14f
private const val CAPSULE_MAX_HEIGHT = 34f
private const val BOX_PAD_X = 20f
private const val BOX_PAD_Y = 12f
private const val BOX_MAX_HEIGHT = 28f
private const val UNDERLINE_GAP = 5f
private const val UNDERLINE_THICKNESS = 2f

/** A capsule without `indicatorColor`: the tint at this alpha (a faint pill, like the system's selected pill). */
private const val CAPSULE_DEFAULT_ALPHA = 0.12f

/**
 * The selection as a fractional tab index: it follows [TabState.selected] with a 0.3 s cubic
 * ease-out; a new selection mid-move retargets from where it is (the running animation is
 * cancelled, its value kept). Also plays the selection haptic.
 */
@Composable
internal fun rememberSelectionProgress(state: TabState): Animatable<Float, *> {
    val progress = remember(state) { Animatable(state.selected.toFloat()) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state, progress) {
        snapshotFlow { state.selected }.drop(1).collect { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    LaunchedEffect(state, progress) {
        snapshotFlow { state.selected }.collectLatest { target ->
            progress.animateTo(target.toFloat(), tween(TAB_INDICATOR_MILLIS, easing = EaseOutCubic))
        }
    }
    return progress
}

/**
 * The capsule, underline or box at fractional tab [progress], centred on its slot (equal-width
 * slots, one per tab) and sized to the label ([label] px, interpolated between the two tabs).
 */
private fun DrawScope.drawTabIndicator(selection: TabBarStyle.Selection, progress: Float, count: Int, color: Color, label: (Int) -> Size) {
    if (selection == TabBarStyle.Selection.opacity || count == 0) return
    val p = progress.coerceIn(0f, (count - 1).toFloat())
    val lo = floor(p).toInt()
    val hi = min(lo + 1, count - 1)
    val t = p - lo
    val a = label(lo)
    val b = label(hi)
    val labelW = a.width + (b.width - a.width) * t
    val labelH = a.height + (b.height - a.height) * t
    val slot = size.width / count
    val cx = (p + 0.5f) * slot
    val cy = size.height / 2f
    when (selection) {
        TabBarStyle.Selection.capsule -> {
            val h = min(labelH + CAPSULE_PAD_Y.dp.toPx(), CAPSULE_MAX_HEIGHT.dp.toPx())
            val w = labelW + CAPSULE_PAD_X.dp.toPx()
            drawRoundRect(color, Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(h / 2f))
        }
        TabBarStyle.Selection.box -> {
            val h = min(labelH + BOX_PAD_Y.dp.toPx(), BOX_MAX_HEIGHT.dp.toPx())
            val w = labelW + BOX_PAD_X.dp.toPx()
            drawRect(color, Offset(cx - w / 2f, cy - h / 2f), Size(w, h))
        }
        TabBarStyle.Selection.underline -> {
            drawRect(color, Offset(cx - labelW / 2f, cy + labelH / 2f + UNDERLINE_GAP.dp.toPx()), Size(labelW, UNDERLINE_THICKNESS.dp.toPx()))
        }
        TabBarStyle.Selection.opacity -> Unit
    }
}

/** A component bar's host indicator has no label to size to: this stand-in label (dp), a slot wide less this margin. */
private const val COMPONENT_LABEL_HEIGHT = 16f
private const val COMPONENT_LABEL_MARGIN = 44f

/** How much tab [index] is selected at [progress]: 1 on it, 0 one tab or more away. */
private fun selectedness(progress: Float, index: Int): Float = 1f - min(1f, abs(progress - index))

/**
 * `barStyle.variant: text`: labels only, in equal-width slots, with a capsule, underline or box
 * that slides to the selected slot and sizes to its label, or (`opacity`) no shape and
 * cross-fading label opacity. Items are tabs to accessibility (Role.Tab, selected state).
 */
@Composable
internal fun TextTabBar(
    tabs: List<Tab>,
    selected: Int,
    progress: Animatable<Float, *>,
    style: TabBarStyle,
    labelStyle: TextStyle,
    selectedLabelStyle: TextStyle,
    height: Double,
    indicator: Color,
    badge: @Composable (Int) -> Unit,
    onTap: (Int) -> Unit,
) {
    val count = tabs.size
    // Label sizes in px, read in the draw phase only.
    val labels = remember(count) { mutableStateListOf<IntSize>().apply { repeat(count) { add(IntSize.Zero) } } }
    val selection = style.effectiveSelection
    val unselectedAlpha = style.unselectedOpacity.toFloat()
    Row(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .selectableGroup()
            .drawBehind {
                if (selection == TabBarStyle.Selection.opacity || count == 0) return@drawBehind
                drawTabIndicator(selection, progress.value, count, indicator) { i -> labels.getOrNull(i)?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero }
            },
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = isSelected, interactionSource = null, indication = null, role = Role.Tab) { onTap(index) }
                    .graphicsLayer {
                        if (selection == TabBarStyle.Selection.opacity) {
                            alpha = unselectedAlpha + (1f - unselectedAlpha) * selectedness(progress.value, index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // M3's BadgedBox overlaps an icon's corner; past a text label the badge
                // sits clear of the last letter, right and up (as iOS and web draw it).
                BadgedBox(badge = { Box(Modifier.offset(x = 12.dp, y = (-4).dp)) { badge(index) } }) {
                    BasicText(
                        tab.label,
                        style = if (isSelected) selectedLabelStyle else labelStyle,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.onSizeChanged { if (index < labels.size) labels[index] = it },
                    )
                }
            }
        }
    }
}

/**
 * `barStyle.component`: the bar drawn by a component template, full width and [height] tall,
 * with `tabs`, `selectedTab`, `selectedIndex` and `selectionProgress` in its scope. Its items
 * select tabs with `selectTab`.
 */
@Composable
internal fun ComponentTabBar(
    session: ScreenSession,
    template: Component,
    tabs: List<Tab>,
    badges: List<String?>,
    state: TabState,
    progress: Animatable<Float, *>,
    height: Double,
    /** Explicit `selection` (capsule / underline / box) draws a host indicator under the template; opacity is ignored. */
    indicator: Pair<TabBarStyle.Selection, Color>?,
) {
    val env = dev.app8.engine.render.LocalRenderEnv.current
    val tabValues: List<Map<String, Any?>> = tabs.mapIndexed { i, t ->
        linkedMapOf("id" to t.id, "label" to t.label, "icon" to t.icon, "badge" to (badges.getOrNull(i) ?: ""), "index" to i.toLong())
    }
    val store = remember(session, template) {
        ScopedVariableStore(session.root.scope.store).apply {
            fun def(name: String, value: Any?) = defineVariable(name, VariableDefinition(VariableType.inferType(value), value))
            def("tabs", tabValues)
            def("selectedTab", tabs.getOrNull(state.selected)?.id ?: "")
            def("selectedIndex", state.selected.toLong())
            def("selectionProgress", progress.value.toDouble())
        }
    }
    val cell = remember(store) { Cell.create(session.root, template, store, null, null) }
    DisposableEffect(cell) { onDispose { cell?.dispose() } }
    // Only a real change (a badge) is pushed into the scope.
    val lastTabs = remember(store) { arrayOf<Any?>(tabValues) }
    SideEffect {
        if (lastTabs[0] != tabValues) {
            lastTabs[0] = tabValues
            runCatching { store.setValue("tabs", tabValues) }
        }
    }
    LaunchedEffect(store) {
        snapshotFlow { state.selected }.collect { i ->
            runCatching {
                store.setValue("selectedTab", tabs.getOrNull(i)?.id ?: "")
                store.setValue("selectedIndex", i.toLong())
            }
        }
    }
    LaunchedEffect(store) {
        snapshotFlow { progress.value }.collect { runCatching { store.setValue("selectionProgress", it.toDouble()) } }
    }
    cell ?: return
    val count = tabs.size
    val drawIndicator = Modifier.drawBehind {
        val (selection, color) = indicator ?: return@drawBehind
        val slot = size.width / count.coerceAtLeast(1)
        val stand = Size((slot - COMPONENT_LABEL_MARGIN.dp.toPx()).coerceAtLeast(0f), COMPONENT_LABEL_HEIGHT.dp.toPx())
        drawTabIndicator(selection, progress.value, count, color) { stand }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height.dp).then(drawIndicator)) {
        val solved = cell.layout(env, width = maxWidth.value.toDouble(), height = height)
        CellView(cell, solved, fixedHeight = height)
    }
}

/** A tab badge: the bar's `badgeColor` / the tab's own; text `badgeTextColor`, default white on a custom color. */
@Composable
internal fun TabBadge(text: String, custom: Color?, textColor: Color?) {
    val container = custom ?: androidx.compose.material3.BadgeDefaults.containerColor
    Badge(
        containerColor = container,
        contentColor = textColor ?: if (custom != null) Color.White else androidx.compose.material3.contentColorFor(container),
    ) { Text(text) }
}

/** The indicator color: `indicatorColor`, else the tint (a capsule at 12%). */
internal fun indicatorColorFor(style: TabBarStyle, authored: Color?, tint: Color): Color =
    authored ?: if (style.effectiveSelection == TabBarStyle.Selection.capsule) tint.copy(alpha = CAPSULE_DEFAULT_ALPHA) else tint

/**
 * `barStyle.background` behind the bar and the bottom safe area: `glass` (default) is the
 * Material 3 surface container, `solid` the screen background, else a fill / material style.
 */
@Composable
internal fun Modifier.tabBarBackground(background: String?, env: RenderEnv): Modifier = when (background) {
    null, "glass" -> background(MaterialTheme.colorScheme.surfaceContainer)
    "solid" -> background(MaterialTheme.colorScheme.surface)
    else -> {
        val entity = env.runtime.styles.entity(background)
        val material = when (val c = entity?.content) {
            is Material -> c
            is Fill -> Material(listOf(entity))
            else -> null
        }
        if (material == null) {
            SideEffect {
                if (env.runtime.warnedNames.add("tabBar.background:$background")) {
                    env.runtime.diagnostics.warn("tabBar.background", "barStyle.background '$background' is not glass, solid or a fill / material style; using glass")
                }
            }
            background(MaterialTheme.colorScheme.surfaceContainer)
        } else {
            dslMaterial(env.styles.material(material), env.appearance)
        }
    }
}
