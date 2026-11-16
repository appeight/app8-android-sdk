package dev.app8.engine.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether scroll content currently rests under a screen's `bottomBar` (DSL 0.6.1): vertical
 * scroll views and collections that reach under the bar report whether they can still scroll
 * toward it. The bar takes its Material 3 surface color while any does.
 */
@Stable
internal class BottomEdge {
    private val under = mutableStateMapOf<String, Boolean>()

    val contentUnder: Boolean get() = under.isNotEmpty()

    fun report(key: String, value: Boolean) {
        if (value) under[key] = true else under.remove(key)
    }
}

/** The enclosing screen's [BottomEdge]; null when the screen has no bottom bar. */
internal val LocalBottomEdge = staticCompositionLocalOf<BottomEdge?> { null }

/** Reports [under] (read in a snapshot: scroll and layout state) for [key] while composed. */
@Composable
internal fun ReportContentUnderBottomBar(key: String, under: () -> Boolean) {
    val edge = LocalBottomEdge.current ?: return
    LaunchedEffect(edge, key) { snapshotFlow(under).collect { edge.report(key, it) } }
    DisposableEffect(edge, key) { onDispose { edge.report(key, false) } }
}

/** Points of [nodeKey]'s frame that reach into the screen's bottom safe area (where the bar is). */
internal fun State<Solved>.coveredBottom(nodeKey: String, screenKey: String): Double {
    val s = value
    val self = s.absolute[nodeKey] ?: return 0.0
    val root = s.absolute[screenKey] ?: return 0.0
    return (self.maxY - (root.maxY - s.safeArea.bottom)).coerceIn(0.0, self.height)
}
