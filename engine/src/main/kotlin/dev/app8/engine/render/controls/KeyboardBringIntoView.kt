package dev.app8.engine.render.controls

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/** Gap kept between a focused input and the keyboard, in points (iOS `scrollToFocusedView`). */
private const val KEYBOARD_PADDING = 20f

/**
 * The nearest vertical `scrollView` (iOS `CScrollView.scrollToFocusedView`): scrolls a
 * focused input into the part of its viewport the keyboard does not cover.
 */
internal class KeyboardScroller(private val state: ScrollState) {
    /** The scroll viewport's coordinates (set while laid out). */
    var viewport: LayoutCoordinates? = null

    suspend fun reveal(field: Rect, keyboardHeightPx: Int, padPx: Float) {
        val vp = viewport?.takeIf { it.isAttached } ?: return
        val view = vp.boundsInWindow()
        val keyboardTop = vp.findRootCoordinates().size.height - keyboardHeightPx.toFloat()
        val visibleBottom = minOf(view.bottom, keyboardTop)
        val delta = when {
            field.bottom + padPx > visibleBottom -> field.bottom + padPx - visibleBottom
            field.top < view.top -> field.top - view.top
            else -> return
        }
        state.animateScrollBy(delta)
    }
}

internal val LocalKeyboardScroller = compositionLocalOf<KeyboardScroller?> { null }

/**
 * Keeps a focused text input above the keyboard. The window does not resize for the IME
 * (edge-to-edge), so scroll views would think a field under the keyboard is visible; when
 * the field is focused and the keyboard's target height changes, the enclosing vertical
 * scroll view scrolls it into the uncovered part of its viewport, 20pt above the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.bringIntoViewAboveKeyboard(focused: Boolean): Modifier {
    val scroller = LocalKeyboardScroller.current ?: return this
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val density = LocalDensity.current
    val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density)
    LaunchedEffect(focused, imeTarget, scroller) {
        if (!focused || imeTarget <= 0) return@LaunchedEffect
        // Let the keyboard animation start first, like iOS.
        delay(100)
        val c = holder[0]?.takeIf { it.isAttached } ?: return@LaunchedEffect
        scroller.reveal(c.boundsInWindow(), imeTarget, KEYBOARD_PADDING * density.density)
    }
    return this.onGloballyPositioned { holder[0] = it }
}
