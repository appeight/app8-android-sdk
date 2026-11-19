package dev.app8.engine.navigation.transitions

import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.model.ScreenTransition.Preset
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An engine-driven transition hands Navigation 3 a real duration. Navigation 3 times a pop by
 * the transition's total duration when it starts, before the screens' keyframe animations
 * exist: with no duration of its own the zoom-pushed product page vanished in one frame on back.
 */
class EngineTransitionHoldTest {
    private val zoom = ResolvedTransition.resolve(ScreenTransition.Inline(preset = Preset.zoom, sourceKey = "{{'card-' + item.id}}"), null)!!

    @Test fun aZoomPopKeepsBothScreensForTheWholeZoomOut() {
        assertTrue(holdMillis(zoom, pop = true) >= NATIVE_ZOOM_OUT_MS)
    }

    @Test fun aZoomPushKeepsThemUntilTheZoomInCanHaveRun() {
        assertTrue(holdMillis(zoom, pop = false) >= NATIVE_ZOOM_IN_MS)
    }

    @Test fun otherPresetsHoldForTheirAnimation() {
        for (preset in listOf(Preset.scale, Preset.popup, Preset.cover, Preset.slide, Preset.fade)) {
            val t = ResolvedTransition.resolve(ScreenTransition.Inline(preset = preset), null) ?: continue
            if (!t.isAnimated) continue
            assertTrue("$preset", holdMillis(t, pop = true) >= (t.animation.duration * 1000).toInt())
        }
    }
}
