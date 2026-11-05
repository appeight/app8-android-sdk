package dev.app8.engine.render.collection

import dev.app8.engine.core.layout.LayoutBounds
import dev.app8.engine.core.layout.LayoutNode
import dev.app8.engine.core.layout.Layout
import dev.app8.engine.core.layout.Dimension
import dev.app8.engine.core.layout.solveLayout
import dev.app8.engine.core.model.CollectionLayout
import dev.app8.engine.core.model.EdgeInsets
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectionPlanTest {
    private fun rows(counts: List<Int>, styles: List<SectionStyle>) = CollectionPlan.rows(
        counts, styles, sectionKey = { "s$it" }, itemKey = { s, i -> "$s/$i" }, contentType = { _, _ -> "t" },
    )

    @Test
    fun `vertical list rows carry item spacing and section insets as padding`() {
        val st = CollectionPlan.topLevelStyle(CollectionLayout(type = CollectionLayout.LayoutType.vertical, itemSpacing = 8.0, contentInsets = EdgeInsets(8.0, 16.0, 20.0, 16.0)), hasHeader = false)
        val r = rows(listOf(3), listOf(st))
        assertEquals(listOf("0/0", "0/1", "0/2"), r.map { it.key })
        assertEquals(listOf(0.0, 8.0, 8.0), r.map { it.padTop }) // top inset is the list's contentPadding
        assertEquals(listOf(0.0, 0.0, 20.0), r.map { it.padBottom })
        assertEquals(16.0, st.insets.left)
    }

    @Test
    fun `sections map to header and item rows in order`() {
        val st = SectionStyle(hasHeader = true, itemSpacing = 10.0, insets = Insets(top = 4.0, bottom = 20.0))
        val r = rows(listOf(2, 0, 1), listOf(st, st, st))
        assertEquals(listOf("h:0:s0", "0/0", "0/1", "h:1:s1", "h:2:s2", "2/0"), r.map { it.key })
        assertEquals(listOf(0, 0, 0, 1, 2, 2), r.map { it.section })
        // The empty section keeps its header and insets.
        assertEquals(24.0, r[3].padBottom)
        assertEquals(4.0, r[5].padTop)
        assertEquals(0..0, (r[5] as RowSpec.Items).items)
    }

    @Test
    fun `grid rows chunk items by columns with line spacing`() {
        val st = SectionStyle(kind = SectionKind.GRID, columns = 3, lineSpacing = 8.0)
        val r = rows(listOf(7), listOf(st))
        assertEquals(listOf(0..2, 3..5, 6..6), r.map { (it as RowSpec.Items).items })
        assertEquals(listOf(0.0, 8.0, 8.0), r.map { it.padTop })
        assertEquals("grid", r[0].contentType)
    }

    @Test
    fun `horizontal section is one strip row`() {
        val st = CollectionPlan.mixedStyle(
            CollectionLayout(type = CollectionLayout.LayoutType.horizontal, itemWidth = 120.0, itemHeight = 160.0, itemSpacing = 12.0),
            CollectionLayout(itemSpacing = 24.0),
            hasHeader = true,
            templateSize = null to null,
        )
        val r = rows(listOf(5), listOf(st))
        assertEquals(2, r.size)
        assertTrue(r[1] is RowSpec.Strip)
        assertEquals(24.0, r[1].padBottom) // outer itemSpacing separates sections
        assertEquals(120.0, st.itemWidth)
    }

    @Test
    fun `mixed horizontal section sizes items from the template first`() {
        val st = CollectionPlan.mixedStyle(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, itemWidth = 100.0), null, false, 140.0 to null)
        assertEquals(140.0, st.itemWidth)
        assertEquals(160.0, st.itemHeight)
    }

    @Test
    fun `top-level grid keeps outer columns flush and rows at group height`() {
        // PhotoGrid on iPhone 17: 402pt wide, 16pt insets, 3 columns, 8pt spacing, aspect 1.
        val st = CollectionPlan.topLevelStyle(
            CollectionLayout(type = CollectionLayout.LayoutType.grid, columns = 3, itemSpacing = 8.0, lineSpacing = 8.0, aspectRatio = 1.0, contentInsets = EdgeInsets(8.0, 16.0, 8.0, 16.0)),
            hasHeader = false,
        )
        val g = CollectionPlan.grid(402.0, st)
        assertEquals(118.0, g.cellWidth, 1e-9)
        assertEquals(118.0, g.cellHeight!!, 1e-9)
        assertEquals(370.0 / 3, g.rowHeight, 1e-9)
        assertEquals(listOf(16.0, 142.0, 268.0), g.xs)
    }

    @Test
    fun `mixed grid splits spacing around items and takes it back from the insets`() {
        val st = CollectionPlan.mixedStyle(
            CollectionLayout(type = CollectionLayout.LayoutType.grid, columns = 2, itemSpacing = 10.0, itemHeight = 50.0, contentInsets = EdgeInsets(0.0, 16.0, 0.0, 16.0)),
            null, false, null to null,
        )
        val g = CollectionPlan.grid(400.0, st)
        // insets 16 - 5 = 11 each side; slot (400 - 22) / 2 = 189; item 189 - 10 = 179.
        assertEquals(179.0, g.cellWidth, 1e-9)
        assertEquals(listOf(16.0, 205.0), g.xs)
        assertEquals(50.0, g.cellHeight)
    }

    @Test
    fun `horizontal item width is fixed, self-sizing or the full content width`() {
        val full = CollectionPlan.topLevelStyle(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, contentInsets = EdgeInsets(left = 10.0, right = 10.0)), false)
        assertEquals(380.0, CollectionPlan.horizontalItemWidth(400.0, full))
        assertEquals(100.0, full.itemHeight)
        val fixed = CollectionPlan.topLevelStyle(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, itemWidth = 240.0), false)
        assertEquals(240.0, CollectionPlan.horizontalItemWidth(400.0, fixed))
        val chips = CollectionPlan.topLevelStyle(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, estimatedItemWidth = 80.0), false)
        assertNull(CollectionPlan.horizontalItemWidth(400.0, chips))
    }

    @Test
    fun `paging styles and the native pager`() {
        assertEquals(Paging.LEADING, CollectionPlan.paging(CollectionLayout(pagingStyle = CollectionLayout.PagingStyle.paging)))
        assertEquals(Paging.CENTERED, CollectionPlan.paging(CollectionLayout(pagingStyle = CollectionLayout.PagingStyle.pagingCentered)))
        assertEquals(Paging.LEADING, CollectionPlan.paging(CollectionLayout(snapToItem = true)))
        assertEquals(Paging.CONTINUOUS, CollectionPlan.paging(CollectionLayout()))
        assertTrue(CollectionPlan.usesNativePaging(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, pagingStyle = CollectionLayout.PagingStyle.paging)))
        assertFalse(CollectionPlan.usesNativePaging(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, pagingStyle = CollectionLayout.PagingStyle.paging, itemWidth = 200.0)))
        assertFalse(CollectionPlan.usesNativePaging(CollectionLayout(type = CollectionLayout.LayoutType.horizontal, pagingStyle = CollectionLayout.PagingStyle.paging, rows = 2)))
    }

    @Test
    fun `page index rounds the offset by container width`() {
        assertEquals(0, CollectionPlan.pageIndex(0.0, 400.0))
        assertEquals(0, CollectionPlan.pageIndex(199.0, 400.0))
        assertEquals(1, CollectionPlan.pageIndex(200.0, 400.0))
        assertEquals(3, CollectionPlan.pageIndex(1210.0, 400.0))
        assertEquals(0, CollectionPlan.pageIndex(50.0, 0.0))
    }

    @Test
    fun `absolute offset sums measured rows and estimates the rest`() {
        val sizes = mapOf(0 to 100, 1 to 50)
        assertEquals(100 + 50 + 80 + 20, CollectionPlan.absoluteOffset(3, 20, sizes::get, 80))
        assertEquals(7, CollectionPlan.absoluteOffset(0, 7, sizes::get, 80))
    }

    @Test
    fun `load more fires within threshold rows of 44pt from the end`() {
        // Last visible row ends 100px below the viewport, 2 more rows of ~90px, 20px padding.
        val remaining = CollectionPlan.remaining(lastIndex = 7, lastEnd = 1100, viewportEnd = 1000, afterPadding = 20, totalCount = 10, averageSize = 90)
        assertEquals(100 + 20 + 180, remaining)
        assertTrue(CollectionPlan.nearEnd(100.0, threshold = 3)) // 132pt
        assertFalse(CollectionPlan.nearEnd(140.0, threshold = 3))
        assertTrue(CollectionPlan.nearEnd(219.0, threshold = null)) // default 5 → 220pt
    }

    @Test
    fun `item keys use unique ids, else indices`() {
        assertEquals(listOf("s#a", "s#2"), CollectionPlan.itemKeys("s", listOf(mapOf("id" to "a"), mapOf("id" to 2.0))))
        assertEquals(listOf("s@0", "s@1"), CollectionPlan.itemKeys("s", listOf(mapOf("id" to "a"), mapOf("id" to "a"))))
        assertEquals(listOf("s@0", "s@1"), CollectionPlan.itemKeys("s", listOf("x", mapOf("id" to "b"))))
    }

    @Test
    fun `multiple selection toggles string and integer ids`() {
        assertEquals(listOf("a", "b"), CollectionPlan.toggled(listOf("a"), "b"))
        assertEquals(listOf("b"), CollectionPlan.toggled(listOf("a", "b"), "a"))
        assertEquals(listOf<Any?>(1L), CollectionPlan.toggled(listOf<Any?>(1L, 2L), 2L))
        assertEquals(listOf<Any?>(), CollectionPlan.toggled(listOf<Any?>(3L), 3))
        assertNull(CollectionPlan.toggled(listOf("a"), true))
    }

    @Test
    fun `cell edges pin the template and let a fixed height win over the bottom edge`() {
        val template = LayoutNode(key = "t", layout = Layout(height = Dimension.Fixed(60.0)))
        val r = solveLayout(Cell.wrap(template, edges = true, weakTrailing = false), LayoutBounds(300.0, null))
        assertEquals(300.0, r.frames.getValue("t").width)
        assertEquals(60.0, r.frames.getValue("t").height)
        assertEquals(60.0, r.contentHeight)
    }
}
