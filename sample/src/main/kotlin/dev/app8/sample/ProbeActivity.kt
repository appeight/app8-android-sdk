package dev.app8.sample

import android.app.Activity
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Measures the device for the Studio device catalogue (the Android
 * counterpart of safearea.info): screen size, density, system bar and
 * cutout insets, display corner radii and the camera cutout shape.
 * Logs one JSON line tagged "A8Probe".
 *
 *   adb shell am start -n dev.app8.sample/.ProbeActivity
 *   adb logcat -d -s A8Probe
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val view = View(this)
        setContentView(view)
        view.setOnApplyWindowInsetsListener { v, insets ->
            Log.i("A8Probe", measure(insets).toString())
            v.onApplyWindowInsets(insets)
        }
    }

    private fun measure(insets: WindowInsets): JSONObject {
        val dm = resources.displayMetrics
        val d = dm.density
        val bounds = windowManager.maximumWindowMetrics.bounds
        fun dp(px: Int) = Math.round(px / d * 100) / 100.0
        fun box(t: Int) = insets.getInsets(t).let {
            JSONObject().put("top", dp(it.top)).put("left", dp(it.left)).put("bottom", dp(it.bottom)).put("right", dp(it.right))
        }
        val corners = JSONObject()
        listOf(
            "topLeft" to RoundedCorner.POSITION_TOP_LEFT,
            "topRight" to RoundedCorner.POSITION_TOP_RIGHT,
            "bottomRight" to RoundedCorner.POSITION_BOTTOM_RIGHT,
            "bottomLeft" to RoundedCorner.POSITION_BOTTOM_LEFT,
        ).forEach { (name, pos) ->
            insets.getRoundedCorner(pos)?.let { corners.put(name, dp(it.radius)) }
        }
        val cutout = JSONObject()
        insets.displayCutout?.let { c ->
            val rects = JSONArray()
            c.boundingRects.forEach {
                rects.put(JSONObject().put("x", dp(it.left)).put("y", dp(it.top)).put("width", dp(it.width())).put("height", dp(it.height())))
            }
            cutout.put("rects", rects)
            c.cutoutPath?.let { cutout.put("path", svg(it, d)) }
        }
        return JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("device", android.os.Build.DEVICE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("px", JSONObject().put("width", bounds.width()).put("height", bounds.height()))
            .put("density", d.toDouble())
            .put("densityDpi", dm.densityDpi)
            .put("dp", JSONObject().put("width", dp(bounds.width())).put("height", dp(bounds.height())))
            .put("statusBars", box(WindowInsets.Type.statusBars()))
            .put("navigationBars", box(WindowInsets.Type.navigationBars()))
            .put("displayCutout", box(WindowInsets.Type.displayCutout()))
            .put("systemGestures", box(WindowInsets.Type.systemGestures()))
            .put("tappableElement", box(WindowInsets.Type.tappableElement()))
            .put("corners", corners)
            .put("cutout", cutout)
    }

    /** Polyline approximation of the cutout outline, in dp. */
    private fun svg(path: Path, d: Float): String {
        val pts = path.approximate(0.5f)
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < pts.size) {
            val x = Math.round(pts[i + 1] / d * 100) / 100.0
            val y = Math.round(pts[i + 2] / d * 100) / 100.0
            sb.append(if (i == 0) "M" else "L").append(x).append(' ').append(y)
            i += 3
        }
        return sb.append("Z").toString()
    }
}
