package dev.app8.engine.debug

import androidx.compose.runtime.Composable

/** Release no-op of the debug recomposition counter (`src/debug`). */
internal object Recompositions {
    @Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")
    @Composable
    inline fun Count(key: String) = Unit

    @Suppress("NOTHING_TO_INLINE")
    @Composable
    inline fun Reporter() = Unit

    fun reset() = Unit
}
