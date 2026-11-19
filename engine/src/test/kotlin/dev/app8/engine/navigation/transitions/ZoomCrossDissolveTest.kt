package dev.app8.engine.navigation.transitions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The zoom's moving photo: the source's at the source, the destination's on the screen, a dissolve between. */
class ZoomCrossDissolveTest {
    @Test fun eachEndShowsOnePhoto() {
        assertEquals(0f, zoomDestinationAlpha(0f), 0f)
        assertEquals(0f, zoomDestinationAlpha(0.1f), 0f)
        assertEquals(1f, zoomDestinationAlpha(0.9f), 0f)
        assertEquals(1f, zoomDestinationAlpha(1f), 0f)
        assertEquals(0.5f, zoomDestinationAlpha(0.5f), 0.001f)
    }

    @Test fun dissolvesMonotonically() {
        var last = -1f
        for (i in 0..100) {
            val a = zoomDestinationAlpha(i / 100f)
            assertTrue(a >= last)
            last = a
        }
    }
}
