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
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.number

/**
 * `pageControl`: the media look of the iOS renderer (MediaPageControl), so the dots read on
 * any photo: a dark capsule the size of the control (26pt tall, 16pt per page plus 10) with
 * 7pt dots 16pt apart. The DSL tints color the dots when set; otherwise white, the current one
 * solid and the others at half opacity. Android has no backdrop blur here, so the capsule is a
 * little darker than iOS's blurred one. Tapping a dot writes `bindVariable`.
 */
internal object PageControlKind : ComponentKind {
    private const val DOT = 7.0
    private const val PITCH = 16.0
    private val CAPSULE = Color(0f, 0f, 0f, 0.38f)

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
        val on = node.hexColor(style?.currentPageIndicatorTintColor) ?: Color.White
        val off = node.hexColor(style?.pageIndicatorTintColor) ?: Color.White.copy(alpha = 0.5f)
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
                if (n > 0) drawRoundRect(CAPSULE, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
                val pitch = (PITCH * density).toFloat()
                val r = (DOT * density / 2).toFloat()
                val start = (size.width - pitch * n) / 2 + pitch / 2
                for (i in 0 until n) drawCircle(if (i == cur) on else off, r, Offset(start + i * pitch, size.height / 2))
            }
        }
    }
}
