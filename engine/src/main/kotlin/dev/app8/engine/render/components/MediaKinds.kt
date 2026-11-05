package dev.app8.engine.render.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import dev.app8.engine.core.layout.IntrinsicMeasure
import dev.app8.engine.core.layout.Size
import dev.app8.engine.core.model.IconProperties
import dev.app8.engine.core.model.ImageProperties
import dev.app8.engine.core.model.style.ColorHex
import dev.app8.engine.core.model.style.ContentMode
import dev.app8.engine.core.model.style.IconStyle
import dev.app8.engine.core.model.style.ImageStyle
import dev.app8.engine.icons.MaterialSymbols
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LayoutTraits
import dev.app8.engine.render.LocalRenderEnv
import dev.app8.engine.render.RenderEnv
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.string
import dev.app8.engine.runtime.AssetRef
import dev.app8.engine.style.DslCornerShape
import dev.app8.engine.style.IosSystemColors

/** UIView.ContentMode → Compose scale + alignment. */
internal fun ContentMode?.toScale(default: ContentMode): Pair<ContentScale, Alignment> = when (this ?: default) {
    ContentMode.scaleToFill, ContentMode.redraw -> ContentScale.FillBounds to Alignment.Center
    ContentMode.scaleAspectFit -> ContentScale.Fit to Alignment.Center
    ContentMode.scaleAspectFill -> ContentScale.Crop to Alignment.Center
    ContentMode.center -> ContentScale.None to Alignment.Center
    ContentMode.top -> ContentScale.None to Alignment.TopCenter
    ContentMode.bottom -> ContentScale.None to Alignment.BottomCenter
    ContentMode.left -> ContentScale.None to Alignment.CenterStart
    ContentMode.right -> ContentScale.None to Alignment.CenterEnd
    ContentMode.topLeft -> ContentScale.None to Alignment.TopStart
    ContentMode.topRight -> ContentScale.None to Alignment.TopEnd
    ContentMode.bottomLeft -> ContentScale.None to Alignment.BottomStart
    ContentMode.bottomRight -> ContentScale.None to Alignment.BottomEnd
}

/** `image` (UIImageView, clipsToBounds, aspect fill by default), loaded with Coil. */
internal object ImageKind : ComponentKind {
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val props = node.content.properties as? ImageProperties
        val style = node.content.style as? ImageStyle
        val ref: AssetRef? = when (val m = props?.model) {
            is ImageProperties.Model.LocalAsset -> AssetRef(null, node.string(m.name), null)
            is ImageProperties.Model.RemoteAsset -> {
                val url = m.asset.url?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                AssetRef(m.asset.id, m.asset.name?.let { node.string(it) }?.takeIf { it.isNotEmpty() }, url)
            }
            else -> null
        }
        val (scale, alignment) = style?.contentMode.toScale(ContentMode.scaleAspectFill)
        val tint = env.color(style?.tintColor)
        val template = style?.renderingMode == ImageStyle.RenderingMode.alwaysTemplate
        var m = modifier.clipToBounds()
        style?.corner?.let { m = m.clip(DslCornerShape(it)) }
        Box(m) {
            if (ref != null && (ref.id != null || ref.name != null || ref.url != null)) {
                AsyncImage(
                    model = ref,
                    contentDescription = node.content.accessibility?.label,
                    imageLoader = node.runtime.imageLoader,
                    contentScale = scale,
                    alignment = alignment,
                    colorFilter = if (tint != null && template) ColorFilter.tint(tint) else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }
}

/**
 * `icon`: SF Symbols map to Material Symbols glyphs (see [MaterialSymbols]); asset and remote
 * icons load like images. Symbol size follows `symbolFontSize` (UIImage.SymbolConfiguration
 * pointSize, 17 by default).
 */
internal object IconKind : ComponentKind {
    private fun pointSize(node: RenderNode): Double = (node.content.style as? IconStyle)?.symbolFontSize ?: 17.0

    override fun traits(node: RenderNode, env: RenderEnv): LayoutTraits {
        val props = node.content.properties as? IconProperties ?: return LayoutTraits.NONE
        if (props.model !is IconProperties.Model.Symbol) return LayoutTraits.NONE
        val pt = pointSize(node)
        // An SF Symbol image at point size P is about 1.1P × 1.2P (web render.ts measures the same).
        return LayoutTraits(intrinsic = IntrinsicMeasure { Size(pt * 1.1, pt * 1.2) }, signature = "icon@$pt")
    }

    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val env = LocalRenderEnv.current
        val props = node.content.properties as? IconProperties
        val style = node.content.style as? IconStyle
        val tint = props?.tintColor?.let { node.string(it.value) }?.takeIf { it.isNotEmpty() }?.let { Color(ColorHex.parseOrFallback(it).argb) }
            ?: env.color(style?.color) ?: env.color(style?.tint)
            ?: IosSystemColors.of("systemBlue", env.appearance)
        Box(modifier, contentAlignment = Alignment.Center) {
            when (val model = props?.model) {
                is IconProperties.Model.Symbol -> {
                    val name = node.string(model.name) ?: model.name
                    val glyph = MaterialSymbols.resolve(LocalContext.current, name)
                    if (glyph == null) {
                        node.runtime.diagnostics.warn("icon.unmapped", "SF Symbol '$name' has no Material Symbol (${node.id})")
                    } else {
                        val pt = pointSize(node).toFloat()
                        // Material glyphs fill their em box; SF glyphs are ~1.2× the point size.
                        val size = env.textSize(pt * 1.2f)
                        BasicText(
                            glyph.name,
                            // Centred on the box and never clipped by it: one em tall, as the
                            // Material glyph fills its em box (an SF symbol overflows its frame too).
                            modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true),
                            style = TextStyle(
                                color = tint,
                                fontSize = size,
                                lineHeight = size,
                                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                                fontFamily = MaterialSymbols.family(glyph.filled, 400, pt * 1.2f),
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                is IconProperties.Model.AssetModel -> AsyncImage(
                    model = AssetRef(null, node.string(model.name), null), contentDescription = null,
                    imageLoader = node.runtime.imageLoader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    colorFilter = if (style?.renderingMode == IconStyle.RenderingMode.template) ColorFilter.tint(tint) else null,
                )
                is IconProperties.Model.Remote -> AsyncImage(
                    model = AssetRef(null, null, node.string(model.url)), contentDescription = null,
                    imageLoader = node.runtime.imageLoader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
                else -> Unit
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }
}
