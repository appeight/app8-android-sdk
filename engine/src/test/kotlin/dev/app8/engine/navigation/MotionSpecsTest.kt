package dev.app8.engine.navigation

import dev.app8.engine.navigation.transitions.MaterialTransitions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Android system motion numbers (docs/android-design-rubric.md §6, android-chrome-spec "Motion"). */
class MotionSpecsTest {
    @Test
    fun tabSwitchIsAFadeThroughOf300ms() {
        assertEquals(90, MotionSpecs.TAB_FADE_OUT_MS)
        assertEquals(210, MotionSpecs.TAB_FADE_IN_MS)
        assertEquals(300, MotionSpecs.TAB_FADE_OUT_MS + MotionSpecs.TAB_FADE_IN_MS)
        assertEquals(0.92f, MotionSpecs.TAB_SCALE_FROM)
    }

    @Test
    fun pushIsSharedAxisXOf450ms() {
        assertEquals(450, MaterialTransitions.DURATION_MS)
        assertEquals(30, MaterialTransitions.SLIDE_DP)
        assertEquals(0.35f, MaterialTransitions.FADE_SPLIT)
    }

    @Test
    fun coverExitIsShorterThanItsEnterAndWithinTheSheetBand() {
        assertTrue(MotionSpecs.COVER_EXIT_MS in 200..250)
        assertTrue(MotionSpecs.COVER_EXIT_MS < MotionSpecs.COVER_ENTER_MS)
    }

    @Test
    fun predictiveBackPreviewReachesMaterialTargets() {
        val density = 2.625f
        val width = 412 * density
        assertEquals(0f, MotionSpecs.backEased(0f))
        assertEquals(1f, MotionSpecs.backEased(1f), 1e-4f)
        // Material's easing is fast early: 40% of the gesture is well past 40% of the preview.
        assertTrue(MotionSpecs.backEased(0.4f) > 0.6f)
        assertEquals(0.9f, MotionSpecs.backScale(1f), 1e-6f)
        assertEquals(1f, MotionSpecs.backScale(0f))
        // width / 20 − 8dp = 20.6 − 8 = 12.6dp on a 412dp screen.
        assertEquals(12.6f * density, MotionSpecs.backDriftPx(1f, width, density), 0.01f)
        assertEquals(0f, MotionSpecs.backDriftPx(1f, 100f, density), "never drifts the wrong way on a narrow window")
        assertEquals(28f * density, MotionSpecs.backCornerPx(1f, MotionSpecs.BACK_CORNER_FALLBACK_DP * density))
        assertEquals(0f, MotionSpecs.backCornerPx(0f, 80f))
    }

    @Test
    fun springsAreTheStandardScheme() {
        assertEquals(0.9f, MotionSpecs.SPATIAL_DAMPING)
        assertEquals(700f, MotionSpecs.SPATIAL_STIFFNESS)
        assertEquals(1f, MotionSpecs.EFFECTS_DAMPING)
        assertEquals(1600f, MotionSpecs.EFFECTS_STIFFNESS)
    }
}
