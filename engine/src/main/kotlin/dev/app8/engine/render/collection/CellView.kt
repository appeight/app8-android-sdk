package dev.app8.engine.render.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import dev.app8.engine.render.LocalSolved
import dev.app8.engine.render.NodeView
import dev.app8.engine.render.Solved
import dev.app8.engine.render.px
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a [Cell] at its solved size: its own [LocalSolved] (the cell is a small layout root,
 * like a `UICollectionViewCell` content view), the template placed at its frame. The frames are
 * read in the measure block only, so a re-solve re-lays-out the cell without recomposing it.
 *
 * @param fixedHeight the cell's height when the layout gives one (`itemHeight`, grid row height).
 */
@Composable
internal fun CellView(cell: Cell, solved: State<Solved>, modifier: Modifier = Modifier, fixedHeight: Double? = null) {
    CompositionLocalProvider(LocalSolved provides solved) {
        Layout(content = { NodeView(cell.root) }, modifier = modifier) { measurables, constraints ->
            val s = solved.value
            val d = density
            val root = s.absolute[Cell.ROOT]
            val f = s.local[cell.root.key]
            val w = root?.let { (it.width * d).roundToInt() } ?: constraints.minWidth
            val h = fixedHeight?.let { (it * d).roundToInt() } ?: (ceil(s.contentHeight * d).toInt())
            val width = w.coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = h.coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeables = measurables.map { m ->
                m.measure(
                    Constraints.fixed(
                        f?.let { (it.width * d).roundToInt().coerceAtLeast(0) } ?: 0,
                        f?.let { (it.height * d).roundToInt().coerceAtLeast(0) } ?: 0,
                    ),
                )
            }
            layout(width, height) {
                if (f != null) placeables.forEach { it.place(px(f.x, d), px(f.y, d)) }
            }
        }
    }
}

/** The height in points a solved cell occupies (fixed or self-sized). */
internal fun Solved.cellHeight(fixed: Double?): Double = fixed ?: contentHeight
