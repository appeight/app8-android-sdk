package dev.app8.engine.render

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.ThemedColor
import dev.app8.engine.core.model.style.Wrapped
import dev.app8.engine.render.style.StyleResolver
import dev.app8.engine.runtime.App8Runtime
import dev.app8.engine.style.App8Appearance
import dev.app8.engine.style.App8FontProvider
import dev.app8.engine.style.IosSystemColors
import dev.app8.engine.style.ResolvedText
import dev.app8.engine.style.TextSizer
import dev.app8.engine.style.colorOrNull
import dev.app8.engine.style.toResolvedText
import kotlin.math.ceil

/**
 * Everything rendering a screen depends on besides the node tree: the runtime, styles
 * resolved for the current appearance, and text measurement. Recreated when the appearance,
 * density or fonts change, which re-lays-out everything.
 */
@Stable
internal class RenderEnv(
    val runtime: App8Runtime,
    val appearance: App8Appearance,
    val density: Density,
    private val measurer: TextMeasurer,
    private val fonts: App8FontProvider,
    private val sizer: TextSizer,
) {
    val styles = StyleResolver(runtime.styles)
    val isDark: Boolean get() = appearance.isDark

    /** UIColor.label. */
    val labelColor: Color get() = IosSystemColors.of("label", appearance)

    fun material(w: Wrapped<Material>?): Material? =
        (w?.value ?: w?.pointerId?.let { runtime.styles.content(it) as? Material })?.let { styles.material(it) }

    fun color(w: Wrapped<ThemedColor>?): Color? = styles.color(w)?.let { Wrapped.of(it).colorOrNull(appearance) }

    fun textModel(w: Wrapped<TextModel>?): TextModel? =
        (w?.value ?: w?.pointerId?.let { runtime.styles.content(it) as? TextModel })?.let { styles.text(it) }

    /**
     * The text style of a label/button: the DSL's, or UIKit's default ([defaultSize] pt system
     * font, [defaultColor], [defaultLines] lines) when the DSL gives none.
     */
    fun text(model: TextModel?, defaultSize: Float, defaultColor: Color, defaultLines: Int): ResolvedText {
        if (model == null) {
            return ResolvedText(
                style = TextStyle(color = defaultColor, fontSize = sizer.size(defaultSize), fontFamily = FontFamily.Default),
                maxLines = if (defaultLines <= 0) Int.MAX_VALUE else defaultLines,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
            )
        }
        val r = model.toResolvedText(appearance, fonts, sizer)
        return if (r.style.color == Color.Unspecified) r.copy(style = r.style.copy(color = defaultColor)) else r
    }

    fun textSize(points: Float) = sizer.size(points)

    /**
     * UILabel `intrinsicContentSize` in points: the natural single-line size, or wrapped at
     * [maxWidth]. Rounded up to whole pixels so the solved frame never wraps the text again.
     */
    fun measure(text: AnnotatedString, style: ResolvedText, maxWidth: Double?): Size {
        if (text.isEmpty()) return Size(0.0, 0.0)
        val d = density.density
        val constraints = if (maxWidth == null || style.maxLines == 1) Constraints() else Constraints(maxWidth = ceil(maxWidth * d - 0.01).toInt().coerceAtLeast(0))
        val r: TextLayoutResult = measurer.measure(
            text, style.style, overflow = style.overflow, softWrap = style.softWrap && style.maxLines != 1,
            maxLines = style.maxLines, constraints = constraints, density = density,
        )
        val w = if (maxWidth == null || style.maxLines == 1) r.size.width else {
            // The widest line, not the constraint (UILabel hugs its text).
            var max = 0f
            for (i in 0 until r.lineCount) max = maxOf(max, r.getLineRight(i) - r.getLineLeft(i))
            ceil(max).toInt()
        }
        return Size(ceil(w.toDouble()) / d, ceil(r.size.height.toDouble()) / d)
    }
}

internal val LocalRenderEnv = staticCompositionLocalOf<RenderEnv> { error("No App8 RenderEnv") }
