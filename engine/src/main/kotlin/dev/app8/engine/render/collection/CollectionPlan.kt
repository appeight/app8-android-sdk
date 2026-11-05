package dev.app8.engine.render.collection

import dev.app8.engine.core.model.CollectionLayout
import kotlin.math.max
import kotlin.math.roundToInt

// Pure collection geometry: the parts of iOS `CCollectionView`'s compositional layouts
// (createVerticalListLayout / createGridLayout / createHorizontalLayout /
// createMixedSectionsLayout) that do not need Compose, so they are unit-tested on the JVM.

internal data class Insets(val top: Double = 0.0, val left: Double = 0.0, val bottom: Double = 0.0, val right: Double = 0.0) {
    companion object {
        val ZERO = Insets()

        fun of(i: dev.app8.engine.core.model.EdgeInsets?): Insets =
            if (i == null) ZERO else Insets(i.top ?: 0.0, i.left ?: 0.0, i.bottom ?: 0.0, i.right ?: 0.0)
    }
}

internal enum class SectionKind { VERTICAL, GRID, HORIZONTAL }

/** `UICollectionLayoutSectionOrthogonalScrollingBehavior`: continuous, groupPaging, groupPagingCentered. */
internal enum class Paging { CONTINUOUS, LEADING, CENTERED }

/**
 * One section's layout, resolved from the DSL (`NSCollectionLayoutSection`).
 *
 * @property insets the section's `contentInsets` (left/right/top/bottom around its items).
 * @property gridHalfSpacing mixed-sections grids put half the item spacing on each side of
 *   every item and take it back from the section insets (`buildGridSection`); top-level grids
 *   space items with `interItemSpacing` only (`createGridLayout`).
 * @property headerInset extra horizontal inset of the header (grid outer spacing).
 */
internal data class SectionStyle(
    val kind: SectionKind = SectionKind.VERTICAL,
    val insets: Insets = Insets.ZERO,
    val itemSpacing: Double = 0.0,
    val lineSpacing: Double = 0.0,
    val columns: Int = 1,
    val gridHalfSpacing: Boolean = false,
    val itemHeight: Double? = null,
    val aspectRatio: Double? = null,
    val itemWidth: Double? = null,
    val selfSizingWidth: Boolean = false,
    val rows: Int = 1,
    val paging: Paging = Paging.CONTINUOUS,
    val hasHeader: Boolean = false,
    val headerInset: Double = 0.0,
)

/** A row of the outer (vertical) lazy list. Gaps are padding inside the row, so they scroll with it. */
internal sealed interface RowSpec {
    val key: String
    val contentType: String
    val section: Int
    val padTop: Double
    val padBottom: Double

    data class Header(
        override val section: Int,
        override val key: String,
        override val padTop: Double,
        override val padBottom: Double,
    ) : RowSpec {
        override val contentType: String get() = "header"
    }

    /** One item (vertical) or one grid row ([items] are indices in the section). */
    data class Items(
        override val section: Int,
        val items: IntRange,
        override val key: String,
        override val contentType: String,
        override val padTop: Double,
        override val padBottom: Double,
    ) : RowSpec

    /** A horizontally scrolling section (orthogonal scrolling). */
    data class Strip(
        override val section: Int,
        override val key: String,
        override val padTop: Double,
        override val padBottom: Double,
    ) : RowSpec {
        override val contentType: String get() = "strip"
    }
}

internal object CollectionPlan {

    fun paging(config: CollectionLayout?): Paging = when (config?.pagingStyle) {
        CollectionLayout.PagingStyle.continuous -> Paging.CONTINUOUS
        CollectionLayout.PagingStyle.paging -> Paging.LEADING
        CollectionLayout.PagingStyle.pagingCentered -> Paging.CENTERED
        // Legacy snapToItem (iOS also reads pagingEnabled through the same fallback in the docs).
        null -> if (config?.snapToItem == true || config?.pagingEnabled == true) Paging.LEADING else Paging.CONTINUOUS
    }

    /**
     * A top-level horizontal collection of full-width pages (`pagingStyle: paging`, no item
     * width, one row): a photo-gallery pager scrolled page by page (iOS `usesNativeHorizontalPaging`).
     */
    fun usesNativePaging(config: CollectionLayout?): Boolean =
        config?.type == CollectionLayout.LayoutType.horizontal &&
            config.pagingStyle == CollectionLayout.PagingStyle.paging &&
            config.itemWidth == null && config.estimatedItemWidth == null && (config.rows ?: 1) <= 1

    /** `createVerticalListLayout` / `createGridLayout` for the single (or groupBy) section layout. */
    fun topLevelStyle(config: CollectionLayout?, hasHeader: Boolean): SectionStyle {
        val i = Insets.of(config?.contentInsets)
        // Left/right/bottom are section insets; top is the collection view's contentInset.
        val sectionInsets = Insets(0.0, i.left, i.bottom, i.right)
        return when (config?.type) {
            CollectionLayout.LayoutType.grid -> SectionStyle(
                kind = SectionKind.GRID,
                insets = sectionInsets,
                itemSpacing = config.itemSpacing ?: 0.0,
                lineSpacing = config.lineSpacing ?: 0.0,
                columns = (config.columns ?: 2).coerceAtLeast(1),
                itemHeight = config.itemHeight,
                aspectRatio = config.aspectRatio?.takeIf { it > 0 },
                // createGridLayout adds no header.
            )
            CollectionLayout.LayoutType.horizontal -> SectionStyle(
                kind = SectionKind.HORIZONTAL,
                insets = sectionInsets,
                itemSpacing = config.itemSpacing ?: 0.0,
                lineSpacing = config.lineSpacing ?: config.itemSpacing ?: 0.0,
                itemWidth = config.itemWidth,
                itemHeight = config.itemHeight ?: 100.0,
                selfSizingWidth = config.itemWidth == null && config.estimatedItemWidth != null,
                paging = paging(config),
            )
            else -> SectionStyle(
                kind = SectionKind.VERTICAL,
                insets = sectionInsets,
                itemSpacing = config?.itemSpacing ?: 0.0,
                itemHeight = null,
                hasHeader = hasHeader,
            )
        }
    }

    /**
     * `createMixedSectionsLayout` for one section: [config] is `sectionLayouts[key] ??
     * defaultSectionLayout`, [outer] the collection's own layout (its `itemSpacing` separates
     * sections). [templateSize] is the fixed width/height of the section's first template.
     */
    fun mixedStyle(config: CollectionLayout?, outer: CollectionLayout?, hasHeader: Boolean, templateSize: Pair<Double?, Double?>): SectionStyle {
        var insets = Insets.of(config?.contentInsets)
        val interSection = outer?.itemSpacing ?: 0.0
        return when (config?.type) {
            CollectionLayout.LayoutType.horizontal -> {
                insets = insets.copy(bottom = insets.bottom + interSection)
                SectionStyle(
                    kind = SectionKind.HORIZONTAL,
                    insets = insets,
                    itemSpacing = config.itemSpacing ?: 0.0,
                    lineSpacing = config.lineSpacing ?: config.itemSpacing ?: 0.0,
                    itemWidth = templateSize.first ?: config.itemWidth ?: 120.0,
                    itemHeight = templateSize.second ?: config.itemHeight ?: 160.0,
                    rows = (config.rows ?: 1).coerceAtLeast(1),
                    paging = paging(config),
                    hasHeader = hasHeader,
                )
            }
            CollectionLayout.LayoutType.grid -> {
                val half = (config.itemSpacing ?: 0.0) / 2
                insets = insets.copy(bottom = insets.bottom + interSection)
                SectionStyle(
                    kind = SectionKind.GRID,
                    insets = insets,
                    itemSpacing = config.itemSpacing ?: 0.0,
                    lineSpacing = config.lineSpacing ?: 0.0,
                    columns = (config.columns ?: 2).coerceAtLeast(1),
                    gridHalfSpacing = true,
                    itemHeight = config.itemHeight,
                    aspectRatio = config.aspectRatio?.takeIf { it > 0 },
                    hasHeader = hasHeader,
                    headerInset = half,
                )
            }
            else -> SectionStyle(
                kind = SectionKind.VERTICAL,
                insets = insets.copy(bottom = insets.bottom + interSection),
                itemSpacing = config?.itemSpacing ?: 0.0,
                itemHeight = config?.itemHeight,
                hasHeader = hasHeader,
            )
        }
    }

    /**
     * Section/index mapping: the outer list's rows for [counts] items per section. Item gaps
     * are the top padding of the following row; section insets pad the first and last rows.
     */
    fun rows(
        counts: List<Int>,
        styles: List<SectionStyle>,
        sectionKey: (Int) -> String,
        itemKey: (section: Int, item: Int) -> String,
        contentType: (section: Int, item: Int) -> String,
    ): List<RowSpec> {
        val out = ArrayList<RowSpec>()
        for ((s, count) in counts.withIndex()) {
            val st = styles[s]
            val sk = sectionKey(s)
            val start = out.size
            if (st.hasHeader) out += RowSpec.Header(s, "h:$s:$sk", 0.0, 0.0)
            when (st.kind) {
                SectionKind.VERTICAL -> for (i in 0 until count) {
                    out += RowSpec.Items(s, i..i, itemKey(s, i), contentType(s, i), if (i == 0) 0.0 else st.itemSpacing, 0.0)
                }
                SectionKind.GRID -> {
                    var i = 0
                    while (i < count) {
                        val end = minOf(count, i + st.columns) - 1
                        out += RowSpec.Items(s, i..end, "g:" + itemKey(s, i), "grid", if (i == 0) 0.0 else st.lineSpacing, 0.0)
                        i = end + 1
                    }
                }
                SectionKind.HORIZONTAL -> if (count > 0) out += RowSpec.Strip(s, "s:$s:$sk", 0.0, 0.0)
            }
            if (out.size == start) continue
            // Section insets: top above the first item (below the header), bottom after the last row.
            val firstItem = if (st.hasHeader) start + 1 else start
            if (firstItem < out.size) out[firstItem] = out[firstItem].withPad(top = out[firstItem].padTop + st.insets.top)
            else out[start] = out[start].withPad(bottom = out[start].padBottom + st.insets.top)
            val last = out.size - 1
            out[last] = out[last].withPad(bottom = out[last].padBottom + st.insets.bottom)
        }
        return out
    }

    private fun RowSpec.withPad(top: Double = padTop, bottom: Double = padBottom): RowSpec = when (this) {
        is RowSpec.Header -> copy(padTop = top, padBottom = bottom)
        is RowSpec.Items -> copy(padTop = top, padBottom = bottom)
        is RowSpec.Strip -> copy(padTop = top, padBottom = bottom)
    }

    /**
     * Cell width, fixed cell height (null = self-sizing), the row's minimum height and the
     * leading x of each column.
     */
    data class GridGeometry(val cellWidth: Double, val cellHeight: Double?, val xs: List<Double>, val rowHeight: Double = cellHeight ?: 0.0)

    fun grid(width: Double, st: SectionStyle): GridGeometry {
        val c = st.columns.coerceAtLeast(1)
        val sp = st.itemSpacing
        return if (st.gridHalfSpacing) {
            // buildGridSection + applySectionInsets: half spacing inside each item, taken back from the insets.
            val half = sp / 2
            val l = max(0.0, st.insets.left - half)
            val r = max(0.0, st.insets.right - half)
            val group = (width - l - r).coerceAtLeast(0.0)
            val slot = group / c
            val h = st.aspectRatio?.let { group / c / it } ?: st.itemHeight
            GridGeometry((slot - sp).coerceAtLeast(0.0), h, List(c) { l + it * slot + half })
        } else {
            // `repeatingSubitem:count:` with interItemSpacing: outer columns flush with the insets.
            val inner = (width - st.insets.left - st.insets.right).coerceAtLeast(0.0)
            val cw = ((inner - (c - 1) * sp) / c).coerceAtLeast(0.0)
            // The group (row) is `inner / columns / aspectRatio` tall, but the rendered item keeps
            // the aspect ratio of its own width (measured on iOS 26: 118pt tiles, 131.3pt pitch at
            // 370pt / 3 columns / 8pt spacing).
            val h = st.aspectRatio?.let { cw / it } ?: st.itemHeight
            val row = st.aspectRatio?.let { inner / c / it } ?: st.itemHeight ?: 0.0
            GridGeometry(cw, h, List(c) { st.insets.left + it * (cw + sp) }, row)
        }
    }

    /** Top-level horizontal item width: fixed, self-sizing (null) or the section's full width. */
    fun horizontalItemWidth(width: Double, st: SectionStyle): Double? = when {
        st.itemWidth != null -> st.itemWidth
        st.selfSizingWidth -> null
        else -> (width - st.insets.left - st.insets.right).coerceAtLeast(0.0)
    }

    /** iOS `handlePageChange`: the page under the offset, by container width. */
    fun pageIndex(offset: Double, containerWidth: Double): Int =
        if (containerWidth <= 0.0) 0 else (offset / containerWidth).roundToInt().coerceAtLeast(0)

    /**
     * Absolute scroll offset of a lazy list in px: the sizes of the rows above the first
     * visible one (measured ones, else [estimate]) plus its scroll offset.
     */
    fun absoluteOffset(firstIndex: Int, firstOffset: Int, sizeOf: (Int) -> Int?, estimate: Int): Int {
        var sum = firstOffset
        for (i in 0 until firstIndex) sum += sizeOf(i) ?: estimate
        return sum
    }

    /**
     * Distance in px from the viewport end to the content end: what remains of the last visible
     * row plus an estimate for the rows not laid out yet.
     */
    fun remaining(lastIndex: Int, lastEnd: Int, viewportEnd: Int, afterPadding: Int, totalCount: Int, averageSize: Int): Int =
        (lastEnd + afterPadding - viewportEnd) + (totalCount - 1 - lastIndex).coerceAtLeast(0) * averageSize

    /** iOS `scrollViewDidScroll` pagination: within `threshold × 44pt` of the end (threshold defaults to 5). */
    fun nearEnd(remainingPoints: Double, threshold: Int?): Boolean = remainingPoints < (threshold ?: 5) * 44.0

    /**
     * Stable lazy-list keys for a section's items: their `id` when every item has a distinct
     * one (so a deletion animates), else the index (iOS index paths).
     */
    fun itemKeys(sectionKey: String, items: List<Any?>): List<String> {
        val ids = items.map { (it as? Map<*, *>)?.get("id")?.let(::idString) }
        val unique = ids.all { it != null } && ids.toSet().size == ids.size
        return if (unique) ids.map { "$sectionKey#$it" } else items.indices.map { "$sectionKey@$it" }
    }

    private fun idString(v: Any): String = when (v) {
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /**
     * `selection.mode: multiple`: toggles [id] in [current] (iOS compares string and integer
     * ids only).
     */
    fun toggled(current: List<Any?>, id: Any?): List<Any?>? {
        val match: (Any?) -> Boolean = when (id) {
            is String -> { v -> v is String && v == id }
            is Long, is Int, is Short, is Byte -> { v -> (v is Long || v is Int || v is Short || v is Byte) && (v as Number).toLong() == (id as Number).toLong() }
            is Double -> if (id % 1.0 == 0.0) { v -> v is Number && v.toDouble() == id } else return null
            else -> return null
        }
        val i = current.indexOfFirst(match)
        return if (i >= 0) current.filterIndexed { j, _ -> j != i } else current + id
    }
}
