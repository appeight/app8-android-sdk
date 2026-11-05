package dev.app8.engine.render.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.CollectionContent
import dev.app8.engine.core.model.CollectionLayout
import dev.app8.engine.core.model.CollectionProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.LocalSolved
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.collection.CellView
import dev.app8.engine.render.collection.CollectionItem
import dev.app8.engine.render.collection.CollectionModel
import dev.app8.engine.render.collection.CollectionPlan
import dev.app8.engine.render.collection.CollectionSection
import dev.app8.engine.render.collection.Paging
import dev.app8.engine.render.collection.RowSpec
import dev.app8.engine.render.collection.SectionKind
import dev.app8.engine.render.collection.SectionStyle
import dev.app8.engine.render.collection.SwipeActionsBox
import dev.app8.engine.render.collection.cellHeight
import dev.app8.engine.render.string
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * `collection` (iOS `CCollectionView`, a compositional-layout `UICollectionView`).
 *
 * Vertical lists, grids and mixed sections are one [LazyColumn] whose rows come from
 * [CollectionPlan.rows] (header, item, grid row, or a horizontally scrolling section); a
 * top-level horizontal collection is a [LazyRow], or a [HorizontalPager] when it pages. Every
 * cell is its own layout root ([Cell]) solved by the Cassowary solver at the width the layout
 * gives it, cached by signature, and keyed by item id (or index) with the template as content
 * type, so Compose reuses cell compositions of the same template.
 */
internal object CollectionKind : ComponentKind {
    /** Shared by [traits] (self-sizing height, computed in the screen's layout pass) and [Render]. */
    private val models = WeakHashMap<RenderNode, CollectionModel>()

    private fun model(node: RenderNode): CollectionModel = synchronized(models) { models.getOrPut(node) { CollectionModel(node) } }

    private fun content(node: RenderNode) = node.content as CollectionContent

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val p = content(node).properties
        val config = p.layout
        val vertical = config?.type == null || config.type == CollectionLayout.LayoutType.vertical || config.type == CollectionLayout.LayoutType.grid
        // `scrollEnabled: false` on a vertical collection: it is as tall as its content (iOS self-sizing height constraint).
        if (!vertical || config?.scrollEnabled != false) return LayoutTraits(opaque = true)
        val model = model(node)
        return LayoutTraits(
            opaque = true,
            intrinsic = IntrinsicMeasure { maxWidth ->
                Size(Size.NO_INTRINSIC_METRIC, contentHeight(model, env, maxWidth, p))
            },
            signature = "collection-self-sizing@${model.sections.value.hashCode()}",
        )
    }

    /** The self-sizing collection's content height at [width] (estimated before the width is known). */
    private fun contentHeight(model: CollectionModel, env: RenderEnv, width: Double?, p: CollectionProperties): Double {
        val sections = model.sections.value
        val config = p.layout
        val insets = config?.contentInsets
        val pad = (insets?.top ?: 0.0) + (insets?.bottom ?: 0.0)
        if (width == null) {
            val n = sections.sumOf { it.items.size }
            return n * (config?.estimatedItemHeight ?: 44.0) + (n - 1).coerceAtLeast(0) * (config?.itemSpacing ?: 0.0) + pad
        }
        val styles = styles(model, sections, p)
        val rows = rows(model, sections, styles)
        var total = pad
        for (row in rows) {
            total += row.padTop + row.padBottom
            total += rowHeight(model, env, sections, styles, row, width)
        }
        return total
    }

    private fun rowHeight(model: CollectionModel, env: RenderEnv, sections: List<CollectionSection>, styles: List<SectionStyle>, row: RowSpec, width: Double): Double {
        val st = styles[row.section]
        val s = sections[row.section]
        return when (row) {
            is RowSpec.Header -> model.headerTemplate(s.key)?.let { t -> model.header(row.section, s.key, t)?.layout(env, width - st.insets.left - st.insets.right, null)?.value?.contentHeight } ?: 0.0
            is RowSpec.Strip -> st.rows * (st.itemHeight ?: 0.0) + (st.rows - 1) * st.lineSpacing
            is RowSpec.Items -> {
                val (w, fixed) = itemSize(st, width)
                val minRow = if (st.kind == SectionKind.GRID) CollectionPlan.grid(width, st).rowHeight else 0.0
                row.items.maxOfOrNull { i ->
                    val item = s.items[i]
                    val t = model.template(item) ?: return@maxOfOrNull 0.0
                    model.cell(s.keys[i], item, t)?.layout(env, w, fixed)?.value?.cellHeight(fixed) ?: 0.0
                }?.coerceAtLeast(minRow) ?: 0.0
            }
        }
    }

    private fun itemSize(st: SectionStyle, width: Double): Pair<Double, Double?> = when (st.kind) {
        SectionKind.GRID -> CollectionPlan.grid(width, st).let { it.cellWidth to it.cellHeight }
        else -> (width - st.insets.left - st.insets.right).coerceAtLeast(0.0) to st.itemHeight
    }

    private fun isMixed(c: CollectionContent): Boolean =
        c.properties.defaultSectionLayout != null || c.properties.sectionLayouts != null || c.sectionHeaders != null

    private fun styles(model: CollectionModel, sections: List<CollectionSection>, p: CollectionProperties): List<SectionStyle> {
        val c = content(model.node)
        if (isMixed(c) && p.layout?.type.let { it == null || it == CollectionLayout.LayoutType.vertical }) {
            return sections.map { s ->
                val cfg = p.sectionLayouts?.get(s.key) ?: p.defaultSectionLayout
                val hasHeader = c.defaultSectionHeader != null || c.sectionHeaders?.get(s.key) != null
                CollectionPlan.mixedStyle(cfg, p.layout, hasHeader, model.templateSize(s.items.firstOrNull()))
            }
        }
        val st = CollectionPlan.topLevelStyle(p.layout, c.defaultSectionHeader != null)
        return sections.map { st }
    }

    private fun rows(model: CollectionModel, sections: List<CollectionSection>, styles: List<SectionStyle>): List<RowSpec> =
        CollectionPlan.rows(
            counts = sections.map { it.items.size },
            styles = styles,
            sectionKey = { sections[it].key },
            itemKey = { s, i -> sections[s].keys[i] },
            contentType = { s, i -> model.template(sections[s].items[i])?.id ?: "none" },
        )

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val model = remember(node) { model(node) }
        DisposableEffect(model) {
            onDispose {
                model.dispose()
                synchronized(models) { models.remove(node) }
            }
        }
        val c = content(node)
        val p = c.properties
        val sections = model.sections.value
        SideEffect { model.sync(sections) }

        var m = modifier
        if (p.clipsToBounds ?: true) m = m.clipToBounds()
        c.style?.backgroundColor?.let { node.string(it) }?.takeIf { it.isNotEmpty() }?.let { m = m.background(Color(ColorHex.parseOrFallback(it).argb)) }

        BoxWithConstraints(m) {
            val width = maxWidth.value.toDouble()
            val height = maxHeight.value.toDouble()
            val empty = c.emptyState
            if (empty != null && sections.all { it.items.isEmpty() }) {
                EmptyState(model, width, height)
                return@BoxWithConstraints
            }
            if (p.layout?.type == CollectionLayout.LayoutType.horizontal) {
                TopLevelHorizontal(model, sections, p, width)
            } else {
                Vertical(model, sections, p, width)
            }
        }
    }

    // region empty state

    @Composable
    private fun EmptyState(model: CollectionModel, width: Double, height: Double) {
        val env = LocalRenderEnv.current
        val template = content(model.node).emptyState ?: return
        val cell = remember(model, template) { Cell.inParentScope(model.node, template) } ?: return
        DisposableEffect(cell) { onDispose { cell.dispose() } }
        CellView(cell, cell.layout(env, width, height, edges = false), Modifier.fillMaxSize(), fixedHeight = height)
    }

    // endregion

    // region vertical (lists, grids, mixed sections)

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Vertical(model: CollectionModel, sections: List<CollectionSection>, p: CollectionProperties, width: Double) {
        val node = model.node
        val env = LocalRenderEnv.current
        val c = content(node)
        val config = p.layout
        val styles = remember(sections, p) { styles(model, sections, p) }
        val rows = remember(sections, styles) { rows(model, sections, styles) }
        val insets = config?.contentInsets
        // adjustedContentInset: contentInset + the part of the safe area the collection covers.
        val solved = LocalSolved.current
        val auto = remember(node, solved) {
            derivedStateOf {
                val s = solved.value
                val self = s.absolute[node.key]
                val root = s.absolute[node.session.screenId]
                if (self == null || root == null) 0.0 to 0.0
                else (root.y + s.safeArea.top - self.y).coerceIn(0.0, self.height) to
                    (self.maxY - (root.maxY - s.safeArea.bottom)).coerceIn(0.0, self.height)
            }
        }.value
        val inverted = p.inverted == true
        var top = (insets?.top ?: 0.0)
        var bottom = (insets?.bottom ?: 0.0)
        var autoTop = auto.first
        var autoBottom = auto.second
        // iOS flips the whole collection view (transform scaleY -1), so both contentInset and
        // the safe-area adjustment swap ends: the bottom safe area pads the visual top.
        if (inverted) {
            top = bottom.also { bottom = top }
            autoTop = autoBottom.also { autoBottom = autoTop }
        }
        val padding = PaddingValues(top = (top + autoTop).dp, bottom = (bottom + autoBottom).dp)
        val state = rememberLazyListState()
        val sticky = p.stickyHeaders == true
        val dispatcher = LocalDispatcher.current
        val swipe = p.swipeActions?.takeIf { !isMixed(c) && config?.type != CollectionLayout.LayoutType.grid }

        ScrollEffects(model, p, state, rows, if (inverted) autoBottom else autoTop)

        val list: @Composable () -> Unit = {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                reverseLayout = inverted,
                userScrollEnabled = config?.scrollEnabled ?: true,
            ) {
                for (row in rows) {
                    val content: @Composable () -> Unit = {
                        RowContent(model, env, sections, styles, row, width, swipe, dispatcher)
                    }
                    if (row is RowSpec.Header && sticky) {
                        stickyHeader(key = row.key, contentType = row.contentType) { content() }
                    } else {
                        item(key = row.key, contentType = row.contentType) {
                            Box(if (swipe != null) Modifier.animateItem() else Modifier) { content() }
                        }
                    }
                }
            }
        }
        if (p.pullToRefresh == true) {
            PullToRefreshBox(isRefreshing = false, onRefresh = { dispatcher.dispatchTrigger(node, ActionTrigger.onRefresh) }, modifier = Modifier.fillMaxSize()) {
                list()
            }
        } else {
            list()
        }
    }

    @Composable
    private fun RowContent(
        model: CollectionModel,
        env: RenderEnv,
        sections: List<CollectionSection>,
        styles: List<SectionStyle>,
        row: RowSpec,
        width: Double,
        swipe: CollectionProperties.SwipeActions?,
        dispatcher: ActionDispatcher,
    ) {
        val st = styles.getOrNull(row.section) ?: return
        val s = sections.getOrNull(row.section) ?: return
        val pad = Modifier.padding(top = row.padTop.dp, bottom = row.padBottom.dp)
        when (row) {
            is RowSpec.Header -> {
                val template = model.headerTemplate(s.key) ?: return
                val cell = model.header(row.section, s.key, template) ?: return
                val w = (width - st.insets.left - st.insets.right).coerceAtLeast(0.0)
                Box(pad.padding(start = st.insets.left.dp)) { CellView(cell, cell.layout(env, w, null)) }
            }
            is RowSpec.Strip -> Box(pad) {
                HorizontalStrip(model, s.items, s.keys, st, width, pageVariable = null)
            }
            is RowSpec.Items -> if (st.kind == SectionKind.GRID) {
                val g = CollectionPlan.grid(width, st)
                Layout(
                    content = {
                        for (i in row.items) ItemCell(model, env, s.items[i], s.keys[i], g.cellWidth, g.cellHeight, Modifier)
                    },
                    modifier = pad.fillMaxWidth(),
                ) { measurables, constraints ->
                    val placeables = measurables.map { it.measure(Constraints()) }
                    val h = maxOf(placeables.maxOfOrNull { it.height } ?: 0, (g.rowHeight * density).roundToInt())
                    layout(constraints.maxWidth, h) {
                        placeables.forEachIndexed { j, pl -> pl.place((g.xs[j] * density).roundToInt(), 0) }
                    }
                }
            } else {
                val i = row.items.first
                val (w, fixed) = itemSize(st, width)
                val cellModifier = Modifier.padding(start = st.insets.left.dp)
                if (swipe != null) {
                    val item = s.items[i]
                    SwipeActionsBox(swipe, pad.fillMaxWidth(), onAction = { action -> runSwipe(model, s.keys[i], item, action, dispatcher) }) {
                        ItemCell(model, env, item, s.keys[i], w, fixed, cellModifier)
                    }
                } else {
                    Box(pad) { ItemCell(model, env, s.items[i], s.keys[i], w, fixed, cellModifier) }
                }
            }
        }
    }

    /** iOS `handleSwipeAction`: the action runs in the swiped item's cell scope. */
    private fun runSwipe(model: CollectionModel, key: String, item: CollectionItem, action: CollectionProperties.SwipeAction, dispatcher: ActionDispatcher) {
        val a = action.action ?: return
        val template = model.template(item) ?: return
        val cell = model.cell(key, item, template) ?: return
        dispatcher.execute(model.node, a, cell.scope.untracked, cell.scope.store)
    }

    @Composable
    private fun ItemCell(model: CollectionModel, env: RenderEnv, item: CollectionItem, key: String, width: Double, fixedHeight: Double?, modifier: Modifier, selfSizingWidth: Boolean = false) {
        val template = model.template(item)
        if (template == null) {
            // No template for this item: an empty cell (iOS dequeues the plain reuse cell).
            Box(modifier.width(width.dp).height((fixedHeight ?: 0.0).dp))
            return
        }
        val cell = model.cell(key, item, template) ?: return
        val solved = cell.layout(env, width, fixedHeight, selfSizingWidth)
        CellView(cell, solved, modifier.then(tapModifier(model, cell, item)), fixedHeight)
    }

    /** `onItemTap` (through the dispatcher, for analytics) then selection, like `didSelectItemAt`. */
    @Composable
    private fun tapModifier(model: CollectionModel, cell: Cell, item: CollectionItem): Modifier {
        val node = model.node
        val c = content(node)
        val selection = c.properties.selection?.takeIf { (it.mode ?: CollectionProperties.Selection.Mode.none) != CollectionProperties.Selection.Mode.none }
        val tappable = c.actions?.containsKey(ActionTrigger.onItemTap) == true || c.analytics?.containsKey(ActionTrigger.onItemTap) == true || selection != null
        if (!tappable || !LocalInteractionEnabled.current) return Modifier
        val dispatcher = LocalDispatcher.current
        val current = rememberUpdatedState(item)
        return Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            dispatcher.dispatchTrigger(node, ActionTrigger.onItemTap, cell.scope.untracked, cell.scope.store)
            if (selection != null) select(node, selection, current.value)
        }
    }

    /** iOS `handleSelectionChange`: single sets the binding to the item id, multiple toggles it. */
    private fun select(node: RenderNode, selection: CollectionProperties.Selection, item: CollectionItem) {
        val id = (item.value as? Map<*, *>)?.get("id") ?: return
        val name = selection.binding?.replace("{{", "")?.replace("}}", "")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val store = node.scope.store
        runCatching {
            when (selection.mode ?: CollectionProperties.Selection.Mode.single) {
                CollectionProperties.Selection.Mode.single -> store.setValue(name, id)
                CollectionProperties.Selection.Mode.multiple -> {
                    val currentSelection = store.getValue(name) as? List<*> ?: return
                    CollectionPlan.toggled(currentSelection, id)?.let { store.setValue(name, it) }
                }
                CollectionProperties.Selection.Mode.none -> Unit
            }
        }.onFailure { node.runtime.diagnostics.warn("collection.selection", "${node.key}: ${it.message}") }
    }

    /** `scrollOffsetVariable` (0 at rest, adjusted for contentInset) and `onLoadMore` (pagination). */
    @Composable
    private fun ScrollEffects(model: CollectionModel, p: CollectionProperties, state: LazyListState, rows: List<RowSpec>, autoTop: Double) {
        val node = model.node
        val offsetVar = p.scrollOffsetVariable
        val pagination = p.pagination?.takeIf { it.enabled == true }
        if (offsetVar == null && pagination == null) return
        val dispatcher = LocalDispatcher.current
        val density = LocalDensity.current.density
        val rowCount = rows.size
        LaunchedEffect(state, rowCount) {
            val sizes = HashMap<Int, Int>()
            var armed = true
            snapshotFlow { state.layoutInfo }.collect { info ->
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) return@collect
                for (v in visible) sizes[v.index] = v.size
                if (offsetVar != null) {
                    val avg = visible.sumOf { it.size } / visible.size
                    val px = CollectionPlan.absoluteOffset(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, sizes::get, avg)
                    // UIKit: contentOffset.y + contentInset.top; the safe-area part of the inset stays negative at rest.
                    val value = px / density - autoTop
                    runCatching { node.scope.store.setValue(offsetVar, value) }
                }
                if (pagination != null) {
                    val last = visible.last()
                    val avg = visible.sumOf { it.size } / visible.size
                    val remaining = CollectionPlan.remaining(last.index, last.offset + last.size, info.viewportEndOffset, info.afterContentPadding, info.totalItemsCount, avg)
                    val near = CollectionPlan.nearEnd(remaining / density.toDouble(), pagination.threshold)
                    // Fires once each time the end comes near; re-arms when the data grows (rowCount key) or the user scrolls away.
                    if (near && armed) dispatcher.dispatchTrigger(node, ActionTrigger.onLoadMore)
                    armed = !near
                }
            }
        }
    }

    // endregion

    // region horizontal

    @Composable
    private fun TopLevelHorizontal(model: CollectionModel, sections: List<CollectionSection>, p: CollectionProperties, width: Double) {
        val config = p.layout
        val st = CollectionPlan.topLevelStyle(config, false)
        val items = remember(sections) { sections.flatMap { it.items } }
        val keys = remember(sections) { sections.flatMap { it.keys } }
        val top = config?.contentInsets?.top ?: 0.0
        Box(Modifier.fillMaxSize().padding(top = top.dp)) {
            if (CollectionPlan.usesNativePaging(config)) {
                NativePager(model, items, keys, st, width, config?.currentPageVariable)
            } else {
                HorizontalStrip(model, items, keys, st, width, config?.currentPageVariable, topLevel = true)
            }
        }
    }

    /** A full-width pager (iOS `isPagingEnabled` on the collection view). */
    @Composable
    private fun NativePager(model: CollectionModel, items: List<CollectionItem>, keys: List<String>, st: SectionStyle, width: Double, pageVariable: String?) {
        val env = LocalRenderEnv.current
        val state = rememberPagerState { items.size }
        PageVariable(model, pageVariable) { CollectionPlan.pageIndex((state.currentPage + state.currentPageOffsetFraction) * width, width) }
        val w = (width - st.insets.left - st.insets.right).coerceAtLeast(0.0)
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize(),
            key = { keys.getOrElse(it) { "$it" } },
            beyondViewportPageCount = 1,
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            Box(Modifier.fillMaxSize().padding(start = st.insets.left.dp)) {
                ItemCell(model, env, item, keys[page], w, st.itemHeight, Modifier)
            }
        }
    }

    /**
     * A horizontally scrolling row of items (top-level horizontal collection or an orthogonal
     * section): continuous scrolling in a [LazyRow]; group paging in a [HorizontalPager] with
     * fixed page size (one group per swipe, leading or centered), or snap flinging for
     * self-sizing widths. `rows > 1` stacks items in columns (vertical groups).
     */
    @Composable
    private fun HorizontalStrip(
        model: CollectionModel,
        items: List<CollectionItem>,
        keys: List<String>,
        st: SectionStyle,
        width: Double,
        pageVariable: String?,
        topLevel: Boolean = false,
    ) {
        val env = LocalRenderEnv.current
        val itemW = if (topLevel) CollectionPlan.horizontalItemWidth(width, st) else st.itemWidth
        val itemH = st.itemHeight
        val rows = st.rows.coerceAtLeast(1)
        val groups = remember(items, rows) { items.indices.chunked(rows) }
        val stripHeight = itemH?.let { rows * it + (rows - 1) * st.lineSpacing }
        val contentPadding = PaddingValues(start = st.insets.left.dp, end = st.insets.right.dp)
        var modifier = Modifier.fillMaxWidth()
        modifier = if (stripHeight != null) modifier.height(stripHeight.dp) else modifier
        val group: @Composable (Int) -> Unit = { g ->
            val indices = groups[g]
            if (indices.size == 1) {
                ItemCell(model, env, items[indices[0]], keys[indices[0]], itemW ?: 0.0, itemH, Modifier, selfSizingWidth = itemW == null)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(st.lineSpacing.dp)) {
                    for (i in indices) ItemCell(model, env, items[i], keys[i], itemW ?: 0.0, itemH, Modifier, selfSizingWidth = itemW == null)
                }
            }
        }
        val groupKey: (Int) -> String = { g -> "c:" + keys[groups[g][0]] }
        if (st.paging != Paging.CONTINUOUS && itemW != null) {
            val state = rememberPagerState { groups.size }
            PageVariable(model, pageVariable) {
                CollectionPlan.pageIndex((state.currentPage + state.currentPageOffsetFraction) * (itemW + st.itemSpacing), width)
            }
            HorizontalPager(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                pageSize = PageSize.Fixed(itemW.dp),
                pageSpacing = st.itemSpacing.dp,
                beyondViewportPageCount = 1,
                snapPosition = if (st.paging == Paging.CENTERED) SnapPosition.Center else SnapPosition.Start,
                flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(1)),
                key = groupKey,
            ) { g -> group(g) }
        } else {
            val state = rememberLazyListState()
            val fling = if (st.paging == Paging.CONTINUOUS) androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
            else rememberSnapFlingBehavior(state, if (st.paging == Paging.CENTERED) SnapPosition.Center else SnapPosition.Start)
            if (pageVariable != null) {
                val density = LocalDensity.current.density
                PageVariable(model, pageVariable) {
                    val step = (itemW ?: 0.0) + st.itemSpacing
                    val offset = if (itemW != null) state.firstVisibleItemIndex * step + state.firstVisibleItemScrollOffset / density
                    else state.firstVisibleItemIndex.toDouble() * width
                    CollectionPlan.pageIndex(offset, width)
                }
            }
            LazyRow(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(st.itemSpacing.dp),
                flingBehavior = fling,
            ) {
                itemsIndexed(groups, key = { g, _ -> groupKey(g) }, contentType = { g, _ -> model.template(items[groups[g][0]])?.id ?: "none" }) { g, _ -> group(g) }
            }
        }
    }

    /** `currentPageVariable`: written whenever the page under the offset changes (iOS `handlePageChange`). */
    @Composable
    private fun PageVariable(model: CollectionModel, name: String?, page: () -> Int) {
        if (name == null) return
        val node = model.node
        val current = rememberUpdatedState(page)
        LaunchedEffect(node, name) {
            var last = -1
            snapshotFlow { current.value() }.collect { p ->
                if (p != last) {
                    last = p
                    runCatching { node.scope.store.setValue(name, p.toLong()) }
                }
            }
        }
    }

    // endregion
}
