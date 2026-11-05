package dev.app8.engine.debug

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug builds only (the release source set has a no-op): counts how often each node's
 * composable body runs, and logs the totals under the `App8Recompose` tag once a second while
 * they change. Layout Inspector's recomposition counts, for headless emulators:
 *
 * ```
 * adb logcat -s App8Recompose
 * ```
 *
 * Lines read `total=<n> +<since last> | top: <node key>=<count> …`; a scroll that only brings
 * new cells in should add roughly one count per node of each new cell, and nothing for
 * cells already on screen.
 */
internal object Recompositions {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    private val total = AtomicLong()

    /** Counts one composition of [key] (call from the body; runs as a [SideEffect]). */
    @Composable
    fun Count(key: String) {
        SideEffect {
            total.incrementAndGet()
            counts.getOrPut(key) { AtomicLong() }.incrementAndGet()
        }
    }

    /** Logs the counts while they change, from a screen's composition. */
    @Composable
    fun Reporter() {
        LaunchedEffect(Unit) {
            var last = -1L
            while (true) {
                delay(1_000)
                val now = total.get()
                if (now != last) {
                    val top = counts.entries.sortedByDescending { it.value.get() }.take(12)
                        .joinToString(" ") { "${it.key}=${it.value.get()}" }
                    Log.d("App8Recompose", "total=$now +${if (last < 0) now else now - last} | top: $top")
                    last = now
                }
            }
        }
    }

    fun reset() {
        counts.clear()
        total.set(0)
    }
}
