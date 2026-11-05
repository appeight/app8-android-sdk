package dev.app8.engine.core.layout

import dev.app8.engine.core.layout.LayoutAttribute.BOTTOM
import dev.app8.engine.core.layout.LayoutAttribute.LEADING
import dev.app8.engine.core.layout.LayoutAttribute.TOP
import dev.app8.engine.core.layout.LayoutAttribute.TRAILING
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A timing harness for [solveLayout] (no JMH: the solver is plain Kotlin and a warm JIT,
 * repeated runs and a median are enough to compare changes). Skipped by `test`; run with
 *
 * ```
 * ./gradlew :engine-core:layoutBenchmark
 * ```
 *
 * which prints one line per fixture: median, p90 and min wall time over [RUNS] solves after
 * [WARMUP] warm-up solves, the node count and solver passes. Numbers in `docs/performance.md`.
 */
class LayoutSolverBenchmark {
    @Test
    fun benchmark() {
        assumeTrue("set -Dapp8.bench=true (./gradlew :engine-core:layoutBenchmark)", System.getProperty("app8.bench") == "true")
        val results = listOf(
            run("stackList750", PHONE, SAFE) { stackList(150) },
            run("constraintCards750", PHONE, SAFE) { constraintCards(125) },
            run("productCardCell", LayoutBounds(240.0, 430.0), EdgeInsets.ZERO, runs = 2000) { productCard() },
            run("wrappingTextCell", LayoutBounds(380.0, null), EdgeInsets.ZERO, runs = 2000) { wrappingCell() },
        )
        println("\n| fixture | nodes | passes | median ms | p90 ms | min ms |\n|---|---:|---:|---:|---:|---:|")
        for (r in results) println(r)
    }

    private class Result(val name: String, val nodes: Int, val passes: Int, val times: List<Double>) {
        override fun toString(): String {
            val s = times.sorted()
            fun f(v: Double) = "%.3f".format(v)
            return "| $name | $nodes | $passes | ${f(s[s.size / 2])} | ${f(s[(s.size * 9) / 10])} | ${f(s.first())} |"
        }
    }

    private fun run(name: String, bounds: LayoutBounds, safe: EdgeInsets, runs: Int = RUNS, tree: () -> LayoutNode): Result {
        val root = tree()
        var passes = 0
        repeat(WARMUP) { passes = solveLayout(root, bounds, safe).passes }
        val times = ArrayList<Double>(runs)
        repeat(runs) {
            val t = System.nanoTime()
            solveLayout(root, bounds, safe)
            times += (System.nanoTime() - t) / 1e6
        }
        return Result(name, count(root), passes, times)
    }

    private fun count(n: LayoutNode): Int = 1 + n.children.sumOf { count(it) }

    companion object {
        const val WARMUP = 30
        const val RUNS = 60

        /** A scroll view over a vertical stack of [rows] icon + title/subtitle rows (5 nodes each). */
        fun stackList(rows: Int): LayoutNode {
            val items = (0 until rows).map { i ->
                LayoutNode(
                    "row$i", stack = StackSpec(StackAxis.HORIZONTAL, 8.0, StackAlignment.CENTER),
                    children = listOf(
                        LayoutNode("row$i.icon", layout = Layout(width = fixed(24), height = fixed(24))),
                        LayoutNode(
                            "row$i.texts", stack = StackSpec(StackAxis.VERTICAL, 2.0),
                            children = listOf(label("row$i.title", 120.0), label("row$i.sub", 90.0, height = 16.0)),
                        ),
                    ),
                )
            }
            val list = LayoutNode(
                "list", stack = StackSpec(StackAxis.VERTICAL, 8.0),
                layout = Layout(leading = fixed(16), trailing = fixed(-16), top = fixed(0), bottom = fixed(0)),
                children = items,
            )
            return screen(LayoutNode("scroll", scroll = ScrollSpec(), children = listOf(list)))
        }

        /**
         * A scroll view over [cards] constraint-laid-out cards chained by sibling constraints
         * (image, title, price, variant, button: 6 nodes each), like a non-lazy product list.
         */
        fun constraintCards(cards: Int): LayoutNode {
            val items = (0 until cards).map { i ->
                val k = "card$i"
                LayoutNode(
                    k, id = k,
                    layout = Layout(
                        height = fixed(180),
                        constraints = listOf(
                            if (i == 0) c(TOP, "superview") else c(TOP, "card${i - 1}", attribute = BOTTOM, constant = 12),
                            c(LEADING, "superview", constant = 16),
                            c(TRAILING, "superview", constant = -16),
                        ) + if (i == cards - 1) listOf(c(BOTTOM, "superview")) else emptyList(),
                    ),
                    children = cardChildren(k),
                )
            }
            return screen(LayoutNode("scroll", scroll = ScrollSpec(), children = items))
        }

        private fun cardChildren(k: String) = listOf(
            LayoutNode("$k.image", id = "image", intrinsicSize = fixedSize(400.0, 400.0), layout = Layout(width = fixed(120), constraints = listOf(c(TOP, "superview"), c(LEADING, "superview"), c(BOTTOM, "superview")))),
            LayoutNode("$k.title", id = "title", intrinsicSize = text(260.0), hugging = LABEL_HUGGING, layout = Layout(constraints = listOf(c(TOP, "superview", constant = 8), c(LEADING, "image", attribute = TRAILING, constant = 12), c(TRAILING, "superview", constant = -8)))),
            label("$k.price", 60.0, id = "price", layout = Layout(constraints = listOf(c(TOP, "title", attribute = BOTTOM, constant = 4), c(LEADING, "title")))),
            label("$k.variant", 110.0, id = "variant", layout = Layout(constraints = listOf(c(TOP, "price", attribute = BOTTOM, constant = 4), c(LEADING, "title")))),
            LayoutNode("$k.button", id = "button", intrinsicSize = fixedSize(90.0, 36.0), layout = Layout(constraints = listOf(c(BOTTOM, "superview", constant = -8), c(TRAILING, "superview", constant = -8)))),
        )

        /** The Facetheory `ProductCard` cell (image, title, price, variant, add button). */
        fun productCard(): LayoutNode = LayoutNode(
            "#cell",
            children = listOf(
                LayoutNode(
                    "ProductCard", id = "ProductCard",
                    layout = Layout(constraints = listOf(c(TOP, "superview"), c(LEADING, "superview"), c(TRAILING, "superview"), c(BOTTOM, "superview", priority = 750f))),
                    children = listOf(
                        LayoutNode("ProductCard.image", id = "productImage", intrinsicSize = fixedSize(800.0, 800.0), layout = Layout(height = fixed(299.43), constraints = listOf(c(TOP, "superview"), c(LEADING, "superview"), c(TRAILING, "superview")))),
                        LayoutNode("ProductCard.title", id = "productTitle", intrinsicSize = text(300.0), hugging = LABEL_HUGGING, layout = Layout(constraints = listOf(c(TOP, "productImage", attribute = BOTTOM, constant = 16), c(LEADING, "superview"), c(TRAILING, "superview")))),
                        label("ProductCard.price", 50.0, id = "productPrice", layout = Layout(constraints = listOf(c(TOP, "productTitle", attribute = BOTTOM, constant = 4), c(LEADING, "superview"), c(TRAILING, "superview")))),
                        label("ProductCard.variant", 120.0, id = "productVariant", layout = Layout(constraints = listOf(c(TOP, "productPrice", attribute = BOTTOM, constant = 8), c(LEADING, "superview")))),
                        LayoutNode("ProductCard.add", id = "addButton", intrinsicSize = fixedSize(36.0, 36.0), layout = Layout(constraints = listOf(c(LEADING, "productVariant", attribute = TRAILING, constant = 8, op = LayoutRelation.GREATER_OR_EQUAL), c(TRAILING, "superview"), c(BOTTOM, "superview")))),
                    ),
                ),
            ),
        )

        /** A self-sizing cell whose wrapping text needs a second pass. */
        fun wrappingCell(): LayoutNode = LayoutNode(
            "#cell",
            children = listOf(
                LayoutNode(
                    "Row", id = "Row",
                    layout = Layout(constraints = listOf(c(TOP, "superview"), c(LEADING, "superview"), c(TRAILING, "superview"), c(BOTTOM, "superview", priority = 750f))),
                    children = listOf(
                        LayoutNode("Row.avatar", id = "avatar", layout = Layout(width = fixed(40), height = fixed(40), constraints = listOf(c(TOP, "superview", constant = 12), c(LEADING, "superview", constant = 16)))),
                        LayoutNode("Row.body", id = "body", intrinsicSize = text(900.0), hugging = LABEL_HUGGING, layout = Layout(constraints = listOf(c(TOP, "superview", constant = 12), c(LEADING, "avatar", attribute = TRAILING, constant = 12), c(TRAILING, "superview", constant = -16), c(BOTTOM, "superview", constant = -12)))),
                    ),
                ),
            ),
        )
    }
}
