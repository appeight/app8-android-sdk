package dev.app8.benchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The sample app; its `MainActivity` runs Facetheory, or the bundle named by the `app` extra. */
const val PACKAGE: String = "dev.app8.sample"
private const val ACTIVITY = "dev.app8.sample.MainActivity"
private const val TIMEOUT_MS = 10_000L

/**
 * The user journeys shared by the macrobenchmarks and the Baseline Profile generator. DSL ids
 * are resource ids (`testTagsAsResourceId` in the sample).
 */
object Journeys {
    /** Cold start of Facetheory to its first frame (the launch screen). */
    fun MacrobenchmarkScope.startFacetheory() {
        startActivityAndWait()
        device.wait(Until.hasObject(By.res("continueAsGuest")), TIMEOUT_MS)
    }

    /** Launch screen → "Continue as guest" → the shop tab with its product rows. */
    fun MacrobenchmarkScope.openShop() {
        val guest = device.wait(Until.findObject(By.res("continueAsGuest")), TIMEOUT_MS)
            ?: error("launch screen did not show continueAsGuest")
        guest.click()
        device.wait(Until.hasObject(By.res("productRow")), TIMEOUT_MS) || error("shop did not show product rows")
        device.waitForIdle()
    }

    /** Flings the shop's section list down and back up. */
    fun MacrobenchmarkScope.scrollShop(flings: Int = 3) {
        fling("sectionList", flings)
    }

    /** Opens the first visible product card and returns to the shop. */
    fun MacrobenchmarkScope.openProductAndBack() {
        val card = device.wait(Until.findObject(By.res("ProductCard")), TIMEOUT_MS) ?: error("no ProductCard")
        card.click()
        device.wait(Until.gone(By.res("sectionList")), TIMEOUT_MS)
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.res("sectionList")), TIMEOUT_MS)
        device.waitForIdle()
    }

    /** Cold start of the long list fixture (1000 self-sizing rows). */
    fun MacrobenchmarkScope.startLongList() {
        startActivityAndWait(Intent(Intent.ACTION_MAIN).setClassName(PACKAGE, ACTIVITY).putExtra("app", "longlist"))
        device.wait(Until.hasObject(By.res("Row")), TIMEOUT_MS) || error("long list did not show rows")
    }

    fun MacrobenchmarkScope.scrollLongList(flings: Int = 4) {
        fling("itemList", flings)
    }

    /** Flings the scrollable [id] down [flings] times and back up, finding it again each time (lazy content replaces nodes). */
    private fun MacrobenchmarkScope.fling(id: String, flings: Int) {
        fun list(): UiObject2 = (device.wait(Until.findObject(By.res(id)), TIMEOUT_MS) ?: error("no $id")).also {
            // Keep the gesture clear of the system gesture areas.
            it.setGestureMargin(device.displayWidth / 5)
        }
        repeat(flings) {
            list().fling(Direction.DOWN)
            device.waitForIdle()
        }
        repeat(flings) {
            list().fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
