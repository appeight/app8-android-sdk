package dev.app8.engine.render.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.app8.engine.render.LocalSolved
import dev.app8.engine.render.NodeView
import dev.app8.engine.render.Solved
import dev.app8.engine.render.fixedConstraints
import dev.app8.engine.render.px
import dev.app8.engine.render.pxSize
import kotlin.math.ceil

/**
 * Draws a [Cell] at its solved size: its own [LocalSolved] (the cell is a small layout root,
 * like a `UICollectionViewCell` content view), the template placed at its frame. The frames are
 * read in the measure block only, so a re-solve re-lays-out the cell without recomposing it.
 *
 * @param fixedHeight the cell's height when the layout gives one (`itemHeight`, grid row height).
 */
@Composable
internal fun CellView(cell: Cell, solved: State<Solved>, modifier: Modifier = Modifier, fixedHeight: Double? = null) {
    // Native visibility: a cell counts as on screen once placed with some part inside the
    // window (a prefetched, recycled or scrolled-out cell is not).
    val onScreen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val parentOnScreen = LocalCellOnScreen.current
    CompositionLocalProvider(LocalSolved provides solved, dev.app8.engine.render.LocalLayoutTransition provides null, LocalCellOnScreen provides (parentOnScreen && onScreen.value)) {
        Layout(
            content = { NodeView(cell.root) },
            modifier = modifier.onGloballyPositioned { onScreen.value = it.isAttached && it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } },
        ) { measurables, constraints ->
            val s = solved.value
            val d = density
            val root = s.absolute[Cell.ROOT]
            val f = s.local[cell.root.key]
            val w = root?.let { pxSize(it.width, d) } ?: constraints.minWidth
            val h = fixedHeight?.let { pxSize(it, d) } ?: (ceil(s.contentHeight * d).toInt())
            val width = w.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = h.coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeables = measurables.map { m ->
                m.measure(
                    fixedConstraints(
                        f?.let { pxSize(it.width, d) } ?: 0,
                        f?.let { pxSize(it.height, d) } ?: 0,
                    ),
                )
            }
            layout(width, height) {
                if (f != null) placeables.forEach { it.place(px(f.x, d), px(f.y, d)) }
            }
        }
    }
}

/**
 * Whether the enclosing collection cells are on screen (DSL 0.6.1 native visibility); `true`
 * outside any cell. Only cells track their own on-screen state: a `scrollView` scrolled away
 * does not change visibility.
 */
internal val LocalCellOnScreen = androidx.compose.runtime.compositionLocalOf { true }

/** The height in points a solved cell occupies (fixed or self-sized). */
internal fun Solved.cellHeight(fixed: Double?): Double = fixed ?: contentHeight
