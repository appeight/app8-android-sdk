package dev.app8.engine.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The vertical scrollers of one screen (a back stack entry), so reselecting its tab can
 * scroll the screen's main one to the top (navigation.md, "Reselecting the selected tab").
 * Scrollers register with their tree order; the outermost (lowest order) is the main one.
 */
internal class ScrollToTopSignal {
    private val targets = LinkedHashMap<Any, Pair<Int, suspend () -> Unit>>()

    fun register(key: Any, order: Int, scroll: suspend () -> Unit) {
        targets[key] = order to scroll
    }

    fun unregister(key: Any) {
        targets.remove(key)
    }

    /** Scrolls the screen's main vertical scroller to its top; false when it has none. */
    suspend fun scrollToTop(): Boolean {
        val target = targets.values.minByOrNull { it.first } ?: return false
        target.second()
        return true
    }
}

internal val LocalScrollToTop = staticCompositionLocalOf<ScrollToTopSignal?> { null }

/** Registers a vertical scroller of the current screen; [scroll] animates it to the top. */
@Composable
internal fun RegisterScrollToTop(key: Any, order: Int, scroll: suspend () -> Unit) {
    val signal = LocalScrollToTop.current ?: return
    val latest = rememberUpdatedState(scroll)
    DisposableEffect(signal, key, order) {
        signal.register(key, order) { latest.value() }
        onDispose { signal.unregister(key) }
    }
}
