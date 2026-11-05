package dev.app8.engine.render.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.layout.AxisValues
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.ButtonProperties
import dev.app8.engine.core.model.LabelProperties
import dev.app8.engine.core.model.style.ButtonStyle
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.LabelStyle
import dev.app8.engine.core.model.style.SystemButtonStyle
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalInteractionEnabled
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.bool
import dev.app8.engine.render.localized
import dev.app8.engine.render.string
import dev.app8.engine.render.tapModifier
import dev.app8.engine.style.IosSystemColors
import dev.app8.engine.style.ResolvedText

/** `label` (UILabel): system 17pt, one line, unless the DSL gives a text style. */
internal object LabelKind : ComponentKind {
    private val LABEL_HUGGING = AxisValues(251f, 251f)

    private class Resolved(val text: AnnotatedString, val style: ResolvedText)

    private fun resolve(node: RenderNode, env: RenderEnv): Resolved {
        val props = node.content.properties as? LabelProperties ?: LabelProperties()
        val model = env.textModel((node.content.style as? LabelStyle)?.text)
        var style = env.text(model, 17f, env.labelColor, defaultLines = 1)
        props.numberOfLines?.let { n -> style = style.copy(maxLines = if (n <= 0) Int.MAX_VALUE else n) }
        val raw = node.localized(props.text)
        val spans = props.spans
        val text = if (spans.isNullOrEmpty()) AnnotatedString(raw) else buildAnnotatedString {
            append(raw)
            for (s in spans) {
                val from = s.from.coerceIn(0, raw.length)
                val to = s.to.coerceIn(from, raw.length)
                if (to <= from) continue
                val color = s.color?.let { ColorHex.parseOrFallback(it) }?.let { Color(it.argb) }
                addStyle(SpanStyle(color = color ?: Color.Unspecified), from, to)
            }
        }
        return Resolved(text, style)
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val r = resolve(node, env)
        return LayoutTraits(
            intrinsic = IntrinsicMeasure { maxWidth -> env.measure(r.text, r.style, maxWidth) },
            signature = "${r.text}${r.text.spanStyles}${r.style}",
            hugging = LABEL_HUGGING,
        )
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val r = resolve(node, env)
        val props = node.content.properties as? LabelProperties
        val bg = props?.backgroundColor?.let { node.string(it.value) }?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }
        Box(if (bg != null) modifier.background(bg) else modifier, contentAlignment = Alignment.CenterStart) {
            DslText(r.text, r.style, Modifier.fillMaxWidth())
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun DslText(text: AnnotatedString, style: ResolvedText, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.style,
        overflow = style.overflow,
        softWrap = style.softWrap && style.maxLines != 1,
        maxLines = style.maxLines,
    )
}

/**
 * `button`. Without `style.system`: UIButton with a title (15pt, tint color, ripple instead
 * of iOS's dimming). With `style.system`: a native-looking filled / tinted / bordered /
 * plain button in the DSL tint, sized like `UIButton.Configuration`.
 */
internal object ButtonKind : ComponentKind {
    override val handlesTaps: Boolean get() = true

    private class Resolved(val title: String, val text: ResolvedText, val system: SystemButtonStyle?, val padH: Double, val padV: Double)

    private fun systemInsets(size: SystemButtonStyle.Size?): Pair<Double, Double> = when (size) {
        SystemButtonStyle.Size.mini -> 8.0 to 3.0
        SystemButtonStyle.Size.small -> 10.0 to 5.0
        SystemButtonStyle.Size.large -> 20.0 to 14.0
        else -> 12.0 to 7.0
    }

    private fun systemFontSize(size: SystemButtonStyle.Size?): Float = when (size) {
        SystemButtonStyle.Size.mini -> 12f
        SystemButtonStyle.Size.small -> 15f
        else -> 17f
    }

    private fun tint(system: SystemButtonStyle, env: RenderEnv): Color = env.color(system.tint) ?: when (system.role) {
        SystemButtonStyle.Role.destructive -> IosSystemColors.of("systemRed", env.appearance)
        else -> IosSystemColors.of("systemBlue", env.appearance)
    }

    private fun foreground(system: SystemButtonStyle, env: RenderEnv): Color {
        env.color(system.foreground)?.let { return it }
        return when (system.variant) {
            SystemButtonStyle.Variant.filled, SystemButtonStyle.Variant.borderedProminent, SystemButtonStyle.Variant.prominentGlass, null -> Color.White
            else -> tint(system, env)
        }
    }

    private fun resolve(node: RenderNode, env: RenderEnv): Resolved {
        val props = node.content.properties as? ButtonProperties ?: ButtonProperties()
        val style = node.content.style as? ButtonStyle
        val system = env.styles.let { style?.system?.value ?: style?.system?.pointerId?.let { id -> node.runtime.styles.content(id) as? SystemButtonStyle } }
        val model = env.textModel(style?.text)
        val title = node.string(props.text) ?: ""
        return if (system == null) {
            Resolved(title, env.text(model, 15f, IosSystemColors.of("systemBlue", env.appearance), 1).copy(maxLines = 1), null, 0.0, 6.0)
        } else {
            val (h, v) = systemInsets(system.size)
            val t = env.text(model, systemFontSize(system.size), foreground(system, env), 1).copy(maxLines = 1)
            Resolved(title, t, system, h, v)
        }
    }

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val r = resolve(node, env)
        val text = AnnotatedString(r.title)
        return LayoutTraits(
            intrinsic = IntrinsicMeasure {
                val s = env.measure(text, r.text, null)
                Size(s.width + r.padH * 2, s.height + r.padV * 2)
            },
            signature = "${r.title}${r.text}${r.system}",
        )
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val r = resolve(node, env)
        val props = node.content.properties as? ButtonProperties
        val enabled = (props?.isEnabled?.let { node.bool(it) } ?: true) && LocalInteractionEnabled.current
        val system = r.system
        var m = modifier
        val shape: Shape
        if (system != null) {
            shape = when (system.cornerStyle) {
                SystemButtonStyle.CornerStyle.fixed, SystemButtonStyle.CornerStyle.small -> RoundedCornerShape(6.dp)
                SystemButtonStyle.CornerStyle.medium -> RoundedCornerShape(8.dp)
                SystemButtonStyle.CornerStyle.large -> RoundedCornerShape(12.dp)
                else -> RoundedCornerShape(percent = 50)
            }
            val tint = tint(system, env)
            val fill: Color? = when (system.variant) {
                SystemButtonStyle.Variant.plain -> null
                SystemButtonStyle.Variant.gray -> IosSystemColors.of("secondarySystemFill", env.appearance)
                SystemButtonStyle.Variant.tinted, SystemButtonStyle.Variant.borderedTinted, SystemButtonStyle.Variant.glass -> tint.copy(alpha = 0.15f)
                SystemButtonStyle.Variant.bordered -> IosSystemColors.of("tertiarySystemFill", env.appearance)
                else -> tint
            }
            m = m.clip(shape)
            if (fill != null) m = m.background(fill)
        } else {
            shape = RectangleShape
        }
        if (!enabled) m = m.graphicsLayer { alpha = 0.4f }
        else m = m.clip(shape).then(tapModifier(node, node.content, ripple(), always = true))
        Box(m, contentAlignment = Alignment.Center) {
            DslText(AnnotatedString(r.title), r.text.copy(style = r.text.style.copy(textAlign = r.text.style.textAlign.takeUnless { it == TextAlign.Unspecified } ?: TextAlign.Center)))
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }
}
