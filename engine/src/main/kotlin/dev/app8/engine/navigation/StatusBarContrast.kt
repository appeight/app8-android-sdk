package dev.app8.engine.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Status bar icons that stay readable over the content beneath them, like the iOS 26
 * status bar (a light screen in a dark-appearance app gets dark icons). Starts from the
 * appearance, then samples the pixels under the status bar whenever the visible screen
 * changes, loading settles or a top bar turns opaque / transparent on scroll
 * ([FlowController.chromeEpoch]). The navigation bar follows the appearance.
 */
@Composable
internal fun AdaptiveSystemBars(flow: FlowController, dark: Boolean, pendingWork: StateFlow<Int>) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    val pending by pendingWork.collectAsState()
    val top = flow.layers.map { it.stack.entries.lastOrNull()?.id }
    LaunchedEffect(top, dark, pending == 0, flow.chromeEpoch) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightNavigationBars = !dark
        controller.isAppearanceLightStatusBars = !dark
        // After the first frames, then once more for late images and entry transitions.
        for (wait in longArrayOf(32, 350)) {
            delay(wait)
            val luminance = sampleStatusBar(window, view) ?: continue
            controller.isAppearanceLightStatusBars = lightIconsUnreadable(luminance)
        }
    }
}

/** Dark icons above this mean luminance (sRGB-weighted, 0…1). */
internal fun lightIconsUnreadable(luminance: Double): Boolean = luminance > 0.6

/** Mean luminance of the window content under the status bar, or null when it can't be read. */
private suspend fun sampleStatusBar(window: Window, view: View): Double? {
    val inset = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: return null
    val width = view.rootView.width
    if (inset <= 0 || width <= 0) return null
    val bitmap = createBitmap(48, 6)
    val ok = suspendCancellableCoroutine { cont ->
        runCatching {
            PixelCopy.request(window, Rect(0, 0, width, inset), bitmap, { cont.resume(it == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
        }.onFailure { cont.resume(false) }
    }
    if (!ok) return null
    val px = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    bitmap.recycle()
    return px.sumOf { c ->
        val r = (c shr 16 and 0xFF) / 255.0
        val g = (c shr 8 and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        0.2126 * r + 0.7152 * g + 0.0722 * b
    } / px.size
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
