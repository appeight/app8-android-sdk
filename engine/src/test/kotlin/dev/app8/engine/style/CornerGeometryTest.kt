package dev.app8.engine.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The continuous-corner geometry against shoppu-web `corner.ts` (`continuousPath`) and
 * its `corner.test.ts`. The reference strings below are `continuousPath(...)` output.
 */
class CornerGeometryTest {

    private sealed interface Seg {
        val points: List<Pair<Float, Float>>
    }

    private data class Pts(val kind: String, override val points: List<Pair<Float, Float>>) : Seg

    private class Recorder : PathSink {
        val segs = mutableListOf<Pts>()
        val arcs = mutableListOf<FloatArray>()
        private var x = 0f
        private var y = 0f
        override fun moveTo(x: Float, y: Float) { segs += Pts("M", listOf(x to y)); this.x = x; this.y = y }
        override fun lineTo(x: Float, y: Float) { segs += Pts("L", listOf(x to y)); this.x = x; this.y = y }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            segs += Pts("C", listOf(x1 to y1, x2 to y2, x3 to y3)); x = x3; y = y3
        }
        override fun arc(cx: Float, cy: Float, radius: Float, startDeg: Float, sweepDeg: Float) {
            val s = Math.toRadians(startDeg.toDouble())
            val e = Math.toRadians((startDeg + sweepDeg).toDouble())
            // The arc must start where the path is.
            assertEquals("arc start x", x, cx + radius * cos(s).toFloat(), 0.01f)
            assertEquals("arc start y", y, cy + radius * sin(s).toFloat(), 0.01f)
            x = cx + radius * cos(e).toFloat()
            y = cy + radius * sin(e).toFloat()
            segs += Pts("A", listOf(x to y))
            arcs += floatArrayOf(cx, cy, radius, startDeg, sweepDeg)
        }
        override fun close() { segs += Pts("Z", emptyList()) }
    }

    /** Parses corner.ts output (absolute M/L/H/V, relative c/a, Z) to absolute points. */
    private fun parse(d: String): List<Pts> {
        val out = mutableListOf<Pts>()
        var x = 0f
        var y = 0f
        Regex("([MLHVZcaz])([^MLHVZcaz]*)").findAll(d).forEach { m ->
            val v = m.groupValues[2].trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toFloat() }
            when (m.groupValues[1]) {
                "M", "L" -> { x = v[0]; y = v[1]; out += Pts(m.groupValues[1], listOf(x to y)) }
                "H" -> { x = v[0]; out += Pts("L", listOf(x to y)) }
                "V" -> { y = v[0]; out += Pts("L", listOf(x to y)) }
                "c" -> {
                    out += Pts("C", listOf(x + v[0] to y + v[1], x + v[2] to y + v[3], x + v[4] to y + v[5]))
                    x += v[4]; y += v[5]
                }
                "a" -> { x += v[5]; y += v[6]; out += Pts("A", listOf(x to y)) }
                "Z", "z" -> out += Pts("Z", emptyList())
            }
        }
        return out
    }

    private fun assertSamePath(expected: List<Pts>, actual: List<Pts>, tol: Float = 0.002f) {
        assertEquals(expected.map { it.kind }, actual.map { it.kind })
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            e.points.zip(a.points).forEach { (pe, pa) ->
                assertEquals("segment $i x", pe.first, pa.first, tol)
                assertEquals("segment $i y", pe.second, pa.second, tol)
            }
        }
    }

    private fun record(w: Float, h: Float, r: Float, smoothing: Float = IOS_CORNER_SMOOTHING, x: Float = 0f, y: Float = 0f) =
        Recorder().also { continuousRoundedRect(it, x, y, w, h, CornerRadii(r), smoothing) }

    @Test
    fun `iPhone screen corner matches continuousPath exactly`() {
        val ref = "M302.8 0c34.723 0 52.085 0 65.347 6.758a62 62 0 0 1 27.095 27.095c6.758 13.263 6.758 30.624 6.758 65.347L402 774.8c0 34.723 0 52.085 -6.758 65.347a62 62 0 0 1 -27.095 27.095c-13.263 6.758 -30.624 6.758 -65.347 6.758L99.2 874c-34.723 0 -52.085 0 -65.347 -6.758a62 62 0 0 1 -27.095 -27.095c-6.758 -13.263 -6.758 -30.624 -6.758 -65.347L0 99.2c0 -34.723 0 -52.085 6.758 -65.347a62 62 0 0 1 27.095 -27.095c13.263 -6.758 30.624 -6.758 65.347 -6.758Z"
        assertSamePath(parse(ref), record(402f, 874f, 62f).segs, tol = 0.003f)
    }

    @Test
    fun `a continuous corner path spans its box and closes`() {
        val rec = record(402f, 874f, 62f)
        val pts = rec.segs.flatMap { it.points }
        assertEquals(0f, pts.minOf { it.first }, 0.01f)
        assertEquals(402f, pts.maxOf { it.first }, 0.01f)
        assertEquals(0f, pts.minOf { it.second }, 0.01f)
        assertEquals(874f, pts.maxOf { it.second }, 0.01f)
        // Each corner reaches 1.6 × the radius into its edges (smoothing 0.6).
        assertEquals(402f - 62f * 1.6f, rec.segs.first().points.first().first, 0.01f)
        assertEquals("Z", rec.segs.last().kind)
        // The last point before closing is back on the top edge, where the path started.
        val beforeClose = rec.segs[rec.segs.size - 2].points.last()
        assertEquals(62f * 1.6f, beforeClose.first, 0.01f)
        assertEquals(0f, beforeClose.second, 0.01f)
    }

    @Test
    fun `a capsule falls back to a circular end as UIKit does`() {
        val ref = "M100 0c0 0 0 0 0 0a20 20 0 0 1 20 20c0 0 0 0 0 0L120 20c0 0 0 0 0 0a20 20 0 0 1 -20 20c0 0 0 0 0 0L20 40c0 0 0 0 0 0a20 20 0 0 1 -20 -20c0 0 0 0 0 0L0 20c0 0 0 0 0 0a20 20 0 0 1 20 -20c0 0 0 0 0 0Z"
        val rec = record(120f, 40f, 20f)
        assertSamePath(parse(ref), rec.segs)
        assertEquals(100f, rec.segs.first().points.first().first, 0.01f)
        // Quarter circles.
        rec.arcs.forEach { assertEquals(90f, it[4], 0.001f) }
    }

    @Test
    fun `smoothing zero is a circular rounded rect`() {
        val ref = "M88 0c0 0 0 0 0 0a12 12 0 0 1 12 12c0 0 0 0 0 0L100 48c0 0 0 0 0 0a12 12 0 0 1 -12 12c0 0 0 0 0 0L12 60c0 0 0 0 0 0a12 12 0 0 1 -12 -12c0 0 0 0 0 0L0 12c0 0 0 0 0 0a12 12 0 0 1 12 -12c0 0 0 0 0 0Z"
        assertSamePath(parse(ref), record(100f, 60f, 12f, smoothing = 0f).segs)
    }

    @Test
    fun `zero radius is a plain rectangle`() {
        val rec = record(10f, 20f, 0f)
        val corners = rec.segs.flatMap { it.points }.toSet()
        assertEquals(setOf(10f to 0f, 10f to 20f, 0f to 20f, 0f to 0f), corners)
        assertTrue(rec.segs.none { it.kind == "C" || it.kind == "A" })
    }

    @Test
    fun `smoothing shrinks when the corner runs out of edge`() {
        // r = 40 on a 100-high box: budget 50, so s = min(0.6, 50/40 - 1) = 0.25.
        val k = cornerParams(40f, 0.6f, 50f)
        assertEquals(0.25f, k.smoothing, 1e-5f)
        assertEquals(50f, k.p, 1e-4f)
        // Radius above the budget is capped: a circle-ended capsule.
        val cap = cornerParams(80f, 0.6f, 50f)
        assertEquals(50f, cap.r, 1e-5f)
        assertEquals(0f, cap.smoothing, 1e-5f)
    }

    @Test
    fun `per-corner radii shape each corner independently`() {
        val rec = Recorder()
        continuousRoundedRect(rec, 10f, 20f, 200f, 100f, CornerRadii(topLeft = 0f, topRight = 20f, bottomRight = 40f, bottomLeft = 8f))
        // Starts 1.6 × 20 left of the right edge, on the top edge.
        assertEquals(10f + 200f - 32f, rec.segs.first().points.first().first, 0.01f)
        assertEquals(20f, rec.segs.first().points.first().second, 0.01f)
        // Arcs are centred r in from their corner, with their own radius.
        val (tr, br, bl) = rec.arcs
        assertEquals(listOf(190f, 40f, 20f), listOf(tr[0], tr[1], tr[2]))
        assertEquals(listOf(170f, 80f, 40f), listOf(br[0], br[1], br[2]))
        assertEquals(listOf(18f, 112f, 8f), listOf(bl[0], bl[1], bl[2]))
        // r = 40 on a 100-high box: smoothing 0.25 → the arc covers 90 × 0.75 degrees.
        assertEquals(67.5f, br[4], 0.001f)
        // Square top-left corner: the left edge runs straight into the start of the top edge.
        assertEquals(3, rec.arcs.size)
        val beforeClose = rec.segs[rec.segs.size - 2].points.last()
        assertEquals(10f to 20f, beforeClose)
        // Every point stays in the box.
        rec.segs.flatMap { it.points }.forEach { (x, y) ->
            assertTrue(x in 9.99f..210.01f && y in 19.99f..120.01f)
        }
    }

    @Test
    fun `curve is tangent-continuous where the arc meets the Beziers`() {
        val rec = record(300f, 200f, 50f)
        // First cubic's last control point → its end → the arc's start tangent.
        val c1 = rec.segs[1].points
        val (cx, cy, r, start) = rec.arcs[0].let { listOf(it[0], it[1], it[2], it[3]) }
        val dirBezier = (c1[2].first - c1[1].first) to (c1[2].second - c1[1].second)
        val s = Math.toRadians(start.toDouble())
        val tangent = (-sin(s)).toFloat() to cos(s).toFloat()
        val len = hypot(dirBezier.first, dirBezier.second)
        assertEquals(tangent.first, dirBezier.first / len, 1e-3f)
        assertEquals(tangent.second, dirBezier.second / len, 1e-3f)
        assertEquals(r, hypot(c1[2].first - cx, c1[2].second - cy), 1e-3f)
    }

    @Test
    fun `radius clamps to half the shorter side`() {
        assertEquals(20f, clampRadius(100f, 40f, 300f), 0f)
        assertEquals(0f, clampRadius(-3f, 40f, 300f), 0f)
        assertEquals(12f, clampRadius(12f, 40f, 300f), 0f)
    }
}
