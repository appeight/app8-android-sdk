package dev.app8.engine.render.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import dev.app8.engine.style.IosSystemColors

/** `pageControl` (UIPageControl): 7pt dots 16pt apart; tapping a dot writes `bindVariable`. */
internal object PageControlKind : ComponentKind {
    private const val DOT = 7.0
    private const val PITCH = 16.0

    private fun pages(node: RenderNode): Int = (node.number((node.content.properties as? PageControlProperties)?.numberOfPages) ?: 0.0).toInt().coerceAtLeast(0)

    private fun current(node: RenderNode): Int {
        val p = node.content.properties as? PageControlProperties ?: return 0
        val v = p.currentPage?.let { node.number(it) } ?: p.bindVariable?.let { node.number("{{${it}}}") }
        return (v ?: 0.0).toInt()
    }

    override fun isHidden(node: RenderNode): Boolean {
        val p = node.content.properties as? PageControlProperties ?: return false
        return p.hidesForSinglePage == true && pages(node) <= 1
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val n = pages(node)
        return LayoutTraits(intrinsic = IntrinsicMeasure { Size(if (n == 0) 0.0 else n * PITCH + 10, 26.0) }, signature = "pages:$n")
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val p = node.content.properties as? PageControlProperties
        val style = node.content.style as? PageControlStyle
        fun hex(s: String?) = s?.let { Color(ColorHex.parseOrFallback(it).argb) }
        val on = hex(style?.currentPageIndicatorTintColor) ?: IosSystemColors.of("label", env.appearance)
        val off = hex(style?.pageIndicatorTintColor) ?: IosSystemColors.of("tertiaryLabel", env.appearance)
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
                val pitch = (PITCH * density).toFloat()
                val r = (DOT * density / 2).toFloat()
                val start = (size.width - pitch * n) / 2 + pitch / 2
                for (i in 0 until n) drawCircle(if (i == cur) on else off, r, Offset(start + i * pitch, size.height / 2))
            }
        }
    }
}
