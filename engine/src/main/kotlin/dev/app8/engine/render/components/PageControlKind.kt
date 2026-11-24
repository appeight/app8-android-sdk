package dev.app8.engine.render.components

import dev.app8.engine.render.hexColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.PageControlProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.PageControlStyle
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.number

/**
 * `pageControl`: the media look of the iOS renderer (MediaPageControl), so the dots read on
 * any photo: a capsule the size of the control (26pt tall, 16pt per page plus 10) with
 * 7pt dots 16pt apart. `properties.style` (0.7.16) picks the look: dark (a dark capsule, white
 * dots), light (a light capsule, black dots) or automatic (by the appearance the screen renders
 * in, [RenderEnv.isDark]). The DSL tints color the dots when set; otherwise the look's color,
 * the current one solid and the others at half opacity. Android has no backdrop blur here, so
 * the capsule is a little more opaque than iOS's blurred one. Tapping a dot writes `bindVariable`.
 */
internal object PageControlKind : ComponentKind {
    private const val DOT = 7.0
    private const val PITCH = 16.0
    private val DARK_CAPSULE = Color(0f, 0f, 0f, 0.38f)
    private val LIGHT_CAPSULE = Color(1f, 1f, 1f, 0.72f)

    /** The capsule and default dot colors (current, others) for a look. */
    internal data class Look(val capsule: Color, val current: Color, val other: Color)

    /** The look for [style] on a screen that renders dark or not; explicit tints replace the dot colors. */
    internal fun look(style: PageControlProperties.Style, screenDark: Boolean, currentTint: Color? = null, otherTint: Color? = null): Look {
        val dark = style.isDark(screenDark)
        val dot = if (dark) Color.White else Color.Black
        return Look(if (dark) DARK_CAPSULE else LIGHT_CAPSULE, currentTint ?: dot, otherTint ?: dot.copy(alpha = 0.5f))
    }

    private fun pages(node: RenderNode): Int = (node.number((node.content.properties as? PageControlProperties)?.numberOfPages) ?: 0.0).toInt().coerceAtLeast(0)

    private fun current(node: RenderNode): Int {
        val p = node.content.properties as? PageControlProperties ?: return 0
        val v = p.currentPage?.let { node.number(it) } ?: p.bindVariable?.let { node.number("{{${it}}}") }
        return (v ?: 0.0).toInt()
    }

    override fun isHidden(node: RenderNode): Boolean {
        if (super.isHidden(node)) return true
        val p = node.content.properties as? PageControlProperties ?: return false
        return p.hidesForSinglePage == true && pages(node) <= 1
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val n = pages(node)
        return LayoutTraits(intrinsic = IntrinsicMeasure { Size(if (n == 0) 0.0 else n * PITCH + 10, 26.0) }, signature = "pages:$n")
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? PageControlProperties
        val style = node.content.style as? PageControlStyle
        val look = look(
            p?.style ?: PageControlProperties.Style.automatic,
            LocalRenderEnv.current.isDark,
            node.hexColor(style?.currentPageIndicatorTintColor),
            node.hexColor(style?.pageIndicatorTintColor),
        )
        val on = look.current
        val off = look.other
        val n = pages(node)
        val cur = current(node)
        var m = modifier
        val bind = p?.bindVariable
        if (bind != null) {
            m = m.pointerInput(node, n) {
                detectTapGestures { pos ->
                    val total = n * PITCH * density
                    val start = (size.width - total) / 2
                    val i = ((pos.x - start) / (PITCH * density)).toInt().coerceIn(0, (n - 1).coerceAtLeast(0))
                    runCatching { node.scope.store.setValue(bind, i.toLong()) }
                }
            }
        }
        Box(m) {
            Canvas(Modifier.fillMaxSize()) {
                if (n > 0) drawRoundRect(look.capsule, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
                val pitch = (PITCH * density).toFloat()
                val r = (DOT * density / 2).toFloat()
                val start = (size.width - pitch * n) / 2 + pitch / 2
                for (i in 0 until n) drawCircle(if (i == cur) on else off, r, Offset(start + i * pitch, size.height / 2))
            }
        }
    }
}
