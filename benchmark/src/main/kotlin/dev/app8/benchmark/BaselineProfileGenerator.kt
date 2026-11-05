package dev.app8.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.app8.benchmark.Journeys.openProductAndBack
import dev.app8.benchmark.Journeys.openShop
import dev.app8.benchmark.Journeys.scrollLongList
import dev.app8.benchmark.Journeys.scrollShop
import dev.app8.benchmark.Journeys.startFacetheory
import dev.app8.benchmark.Journeys.startLongList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the engine's Baseline Profile from the sample's journeys:
 * `./gradlew :engine:generateBaselineProfile` (ANDROID_SERIAL picks the device) writes
 * `engine/src/main/generated/baselineProfiles/baseline-prof.txt`, filtered to `dev.app8.**`,
 * which ships in the engine AAR.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startFacetheory()
        openShop()
        scrollShop(flings = 2)
        openProductAndBack()
        pressHome()
        startLongList()
        scrollLongList(flings = 2)
    }
}
