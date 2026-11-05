package dev.app8.engine.render.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.core.model.ComponentEntity
import dev.app8.engine.core.model.ComponentType
import dev.app8.engine.core.model.TableViewContent
import dev.app8.engine.core.model.TableViewProperties
import dev.app8.engine.core.model.TableViewRow
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.LocalSolved
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.collection.Cell
import dev.app8.engine.render.collection.CellView
import dev.app8.engine.render.collection.TablePlan
import dev.app8.engine.style.ContinuousRoundedShape
import dev.app8.engine.style.IosSystemColors

/**
 * `tableView` (iOS `CTableViewView`, a static `UITableView`): sections of inline rows with
 * optional header/footer titles, in the `plain`, `grouped` or `insetGrouped` (default) style.
 *
 * Rows are brand content drawn with the iOS 26 look (rounded inset cards on
 * `secondarySystemGroupedBackground`, hairline separators at `separatorInset`); a tap gives
 * Android ripple feedback and goes through [dev.app8.engine.actions.ActionDispatcher.dispatchRowTap].
 */
internal object TableViewKind : ComponentKind {
    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits = LayoutTraits(opaque = true)

    override val clipsToMaterial: Boolean get() = true

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val c = node.content as TableViewContent
        val style = c.properties.tableStyle ?: TableViewProperties.TableStyle.insetGrouped
        val metrics = TablePlan.metrics(style)
        // Rows render once in the table's scope (iOS pre-renders every row's children).
        val rows = remember(node) {
            c.sections.map { s -> s.rows.map { row -> Cell.inParentScope(node, rowComponent(row)) } }
        }
        DisposableEffect(rows) { onDispose { rows.forEach { s -> s.forEach { it?.dispose() } } } }

        // adjustedContentInset: the part of the safe area the table covers.
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
        val cardColor = IosSystemColors.of("secondarySystemGroupedBackground", env.appearance)
        val secondary = IosSystemColors.of("secondaryLabel", env.appearance)
        val separator = Color.White.copy(alpha = 0.12f) // iOS hard-codes white @ 12%.
        val separatorInset = c.properties.separatorInset ?: 0.0
        val dispatcher = LocalDispatcher.current
        val enabled = LocalInteractionEnabled.current

        BoxWithConstraints(modifier.clipToBounds()) {
            val cardWidth = (maxWidth.value - 2 * metrics.margin).toDouble().coerceAtLeast(0.0)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = auto.first.dp, bottom = (auto.second + metrics.bottomPadding).dp),
            ) {
                for ((si, section) in c.sections.withIndex()) {
                    val header = section.header
                    item(key = "h:${section.id}", contentType = if (header != null) "header" else "gap") {
                        val gap = if (header != null) metrics.titledGap else metrics.sectionGap
                        Box(Modifier.fillMaxWidth().height(gap.dp), contentAlignment = Alignment.BottomStart) {
                            if (header != null) {
                                BasicText(
                                    header,
                                    Modifier.padding(start = (metrics.margin + metrics.textInset).dp, bottom = metrics.headerBottom.dp),
                                    style = TextStyle(color = secondary, fontSize = env.textSize(metrics.headerSize), fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                    for ((ri, row) in section.rows.withIndex()) {
                        item(key = "r:${section.id}:${row.id}:$ri", contentType = "row") {
                            val cell = rows.getOrNull(si)?.getOrNull(ri) ?: return@item
                            val first = ri == 0
                            val last = ri == section.rows.lastIndex
                            val shape: Shape = if (metrics.radius > 0 && (first || last)) ContinuousRoundedShape(
                                topLeft = if (first) metrics.radius.dp else 0.dp,
                                topRight = if (first) metrics.radius.dp else 0.dp,
                                bottomRight = if (last) metrics.radius.dp else 0.dp,
                                bottomLeft = if (last) metrics.radius.dp else 0.dp,
                            ) else RectangleShape
                            var m = Modifier.padding(horizontal = metrics.margin.dp).clip(shape)
                            if (row.clearBackground != true) m = m.background(cardColor)
                            val hasTap = row.actions?.containsKey(ActionTrigger.tap) == true || row.analytics?.containsKey(ActionTrigger.tap) == true
                            if (hasTap && enabled) {
                                m = m.clickable(interactionSource = remember { MutableInteractionSource() }, indication = ripple()) {
                                    dispatcher.dispatchRowTap(node, row.id, row.actions?.get(ActionTrigger.tap).orEmpty(), row.analytics?.get(ActionTrigger.tap))
                                }
                            }
                            if (!last) {
                                m = m.drawWithContent {
                                    drawContent()
                                    val y = size.height - 0.5f
                                    drawLine(separator, Offset((separatorInset * density).toFloat(), y), Offset(size.width, y), strokeWidth = 1f)
                                }
                            }
                            val height = row.height
                            Box(m) {
                                val solvedRow = cell.layout(env, cardWidth, height, edges = false)
                                CellView(cell, solvedRow, Modifier, fixedHeight = height ?: rowHeight(solvedRow.value.contentHeight))
                            }
                        }
                    }
                    section.footer?.let { footer ->
                        item(key = "f:${section.id}", contentType = "footer") {
                            BasicText(
                                footer,
                                Modifier.padding(start = (metrics.margin + metrics.textInset).dp, end = (metrics.margin + metrics.textInset).dp, top = 7.dp),
                                style = TextStyle(color = secondary, fontSize = env.textSize(13f)),
                            )
                        }
                    }
                }
            }
        }
    }

    /** `UITableView.automaticDimension`: the row's content height, 44pt when the content gives none. */
    private fun rowHeight(content: Double): Double = if (content > 0.5) content else 44.0

    /** A row as a plain `view` whose children are the row's children (the cell's content view). */
    private fun rowComponent(row: TableViewRow) = ComponentEntity(
        id = row.id,
        templateId = null,
        type = ComponentType.Known(ComponentType.Key.view),
        content = ComponentContent<ViewProperties, Any>(properties = ViewProperties(), children = row.children),
    )
}
