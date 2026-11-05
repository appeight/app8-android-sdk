package dev.app8.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
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
 * Macrobenchmarks of the sample's release build (profileable, R8-minified). Frame benchmarks
 * restart the app in their setup (a `COLD` startup mode would kill it after the setup). Each journey runs
 * twice: without ahead-of-time compilation (`None`, what a fresh install without profiles
 * gets) and with the shipped Baseline Profiles (`BaselineProfile`). Pick one with
 * `-e class dev.app8.benchmark.Benchmarks#scrollShopBaselineProfile`.
 */
@RunWith(AndroidJUnit4::class)
class Benchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun startupNone() = startup(CompilationMode.None())
    @Test fun startupBaselineProfile() = startup(BASELINE)

    @Test fun scrollShopNone() = scrollShop(CompilationMode.None())
    @Test fun scrollShopBaselineProfile() = scrollShop(BASELINE)

    @Test fun openProductNone() = openProduct(CompilationMode.None())
    @Test fun openProductBaselineProfile() = openProduct(BASELINE)

    @Test fun scrollLongListNone() = scrollLongList(CompilationMode.None())
    @Test fun scrollLongListBaselineProfile() = scrollLongList(BASELINE)

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()) + engineSections,
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = STARTUP_ITERATIONS,
        setupBlock = { pressHome() },
    ) { startFacetheory() }

    private fun scrollShop(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()) + engineSections,
        compilationMode = mode,
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            startFacetheory()
            openShop()
        },
    ) { scrollShop() }

    private fun openProduct(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()) + engineSections,
        compilationMode = mode,
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            startFacetheory()
            openShop()
        },
    ) { openProductAndBack() }

    private fun scrollLongList(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()) + engineSections,
        compilationMode = mode,
        startupMode = null,
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            startLongList()
        },
    ) { scrollLongList() }

    private companion object {
        /** Main-thread time in the engine's own trace sections (`traced` in the engine), per iteration. */
        @OptIn(ExperimentalMetricApi::class)
        val engineSections: List<Metric> = listOf("App8.solve", "App8.layoutTree", "App8.cell").flatMap { name ->
            listOf(
                TraceSectionMetric(name, TraceSectionMetric.Mode.Sum, label = "${name}Sum"),
                TraceSectionMetric(name, TraceSectionMetric.Mode.Max, label = "${name}Max"),
            )
        }
        val BASELINE = CompilationMode.Partial(BaselineProfileMode.Require)
        const val STARTUP_ITERATIONS = 10
        const val ITERATIONS = 5
    }
}
