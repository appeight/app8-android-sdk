package dev.app8.engine.navigation.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeZoomGeometryTest {
    private val screen = Size(1000f, 2000f)
    private val source = Rect(100f, 900f, 500f, 1300f) // 400 x 400 card photo, 24 px corner
    private val hero = Rect(0f, 200f, 1000f, 1450f) // 1000 x 1250 carousel image

    private fun ZoomGeometry.toScreen(p: Offset) = translation + p * scale
    private fun near(e: Float, a: Float) = assertEquals(e, a, 0.01f)

    @Test
    fun startMapsTheAlignmentElementOntoTheSource() {
        val g = zoomGeometry(0f, screen, source, 24f, hero)
        near(400f / 1000f, g.scale) // max(400/1000, 400/1250)
        // Clip: source-shaped, centred on the hero.
        near(1000f, g.clip.width)
        near(1000f, g.clip.height)
        near(hero.center.x, g.clip.center.x)
        near(hero.center.y, g.clip.center.y)
        val tl = g.toScreen(g.clip.topLeft)
        val br = g.toScreen(g.clip.bottomRight)
        near(source.left, tl.x); near(source.top, tl.y)
        near(source.right, br.x); near(source.bottom, br.y)
        near(24f, g.radius * g.scale)
    }

    @Test
    fun endIsIdentity() {
        val g = zoomGeometry(1f, screen, source, 24f, hero)
        near(1f, g.scale)
        assertEquals(Offset.Zero, g.translation)
        assertEquals(Rect(0f, 0f, 1000f, 2000f), g.clip)
        near(0f, g.radius)
    }

    @Test
    fun withoutAlignmentTheWholeScreenFillsTheSource() {
        val g = zoomGeometry(0f, screen, source, 0f, null)
        near(0.4f, g.scale) // max(400/1000, 400/2000)
        near(500f, g.clip.center.x); near(1000f, g.clip.center.y)
        near(source.left, g.toScreen(g.clip.topLeft).x)
        near(source.bottom, g.toScreen(g.clip.bottomRight).y)
    }

    @Test
    fun midwayInterpolatesLinearly() {
        val a = zoomGeometry(0f, screen, source, 24f, hero)
        val m = zoomGeometry(0.5f, screen, source, 24f, hero)
        near((a.scale + 1f) / 2, m.scale)
        near(a.translation.x / 2, m.translation.x)
        near(a.clip.left / 2, m.clip.left)
        near((a.clip.bottom + 2000f) / 2, m.clip.bottom)
    }

    @Test
    fun ghostFadesOutEarlyAndInLate() {
        near(1f, zoomGhostAlpha(0f, dismissing = false))
        // 40% of the time along a cubic ease-out is 1 - 0.6^3 of the way.
        near(0f, zoomGhostAlpha(1f - 0.216f, dismissing = false))
        near(0f, zoomGhostAlpha(0.6f, dismissing = true))
        near(0.5f, zoomGhostAlpha(0.25f, dismissing = true))
        near(1f, zoomGhostAlpha(0f, dismissing = true))
    }

    @Test
    fun dragKeepsTheClipCentreUnderTheFinger() {
        val g = zoomGeometry(1f, screen, source, 0f, null)
        val d = g.dragged(Offset(0f, 300f), screen, 0f)
        val centre = d.toScreen(g.clip.center)
        near(500f, centre.x); near(1300f, centre.y)
    }
}
