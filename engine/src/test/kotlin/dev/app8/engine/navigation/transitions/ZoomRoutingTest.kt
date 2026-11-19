package dev.app8.engine.navigation.transitions

import dev.app8.engine.core.model.ScreenTransition
import dev.app8.engine.core.model.ScreenTransition.Mode
import dev.app8.engine.core.model.ScreenTransition.Preset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Push or modal per transition (iOS `TransitionPresets.resolve`). A card that zooms into its
 * detail screen (`zoom` + `sourceKey`, `presentation: push`) must land on the same stack,
 * so the detail screen gets the back arrow; it used to open as a full-screen modal.
 */
class ZoomRoutingTest {
    private fun inline(preset: Preset?, mode: Mode? = null, sourceKey: String? = null) =
        ScreenTransition.Inline(preset = preset, mode = mode, sourceKey = sourceKey)

    @Test fun nativeZoomPushesByDefault() {
        val zoom = inline(Preset.zoom, sourceKey = "{{'card-' + item.id}}")
        assertEquals(Mode.push, routingMode(zoom))
        assertEquals(Mode.push, ResolvedTransition.resolve(zoom, null).mode)
        assertEquals(true, ResolvedTransition.resolve(zoom, null).nativeZoom)
    }

    @Test fun nativeZoomHonoursModeModal() {
        val zoom = inline(Preset.zoom, mode = Mode.modal, sourceKey = "card")
        assertEquals(Mode.modal, routingMode(zoom))
        assertEquals(Mode.modal, ResolvedTransition.resolve(zoom, null).mode)
    }

    @Test fun zoomWithoutSourceKeepsThePresetDefault() {
        assertEquals(Mode.modal, routingMode(inline(Preset.zoom)))
        assertEquals(Mode.modal, routingMode(inline(Preset.zoom, sourceKey = "")))
    }

    @Test fun otherPresetsFollowTheirDefaults() {
        assertEquals(Mode.push, routingMode(inline(Preset.slide)))
        assertEquals(Mode.modal, routingMode(inline(Preset.cover)))
        assertEquals(Mode.push, routingMode(inline(Preset.cover, mode = Mode.push)))
        assertEquals(Mode.push, routingMode(inline(null)))
    }
}
