package dev.app8.engine.style

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.Material
import dev.app8.engine.core.model.style.Outline
import dev.app8.engine.core.model.style.Shadow
import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StyleType
import dev.app8.engine.core.model.style.VisualEffect
import kotlin.math.max

/**
 * One shadow layer with `CALayer` semantics, all lengths in dp.
 *
 * - [radius] is `shadowRadius`: the Gaussian's standard deviation (Figma / CSS blur = 2 × radius).
 * - [opacity] multiplies the color's alpha (`shadowOpacity`).
 * - [spread] grows the shadow path; CALayer has none, so DSL shadows always use 0.
 * - A non-[hollow] layer also fills the shape with [color] (iOS sets the shadow
 *   layer's `backgroundColor` to the shadow color); a hollow one draws only the shadow.
 *   Either way the shadow is cast by the whole shape (`shadowPath`), not cut out.
 */
@Immutable
internal data class ShadowSpec(
    val color: Color,
    val radius: Float,
    val offsetX: Float,
    val offsetY: Float,
    val opacity: Float,
    val spread: Float = 0f,
    val hollow: Boolean = true,
)

/** A drawable layer of a material, in iOS `MaterialView` z-order. */
@Immutable
internal sealed interface MaterialLayer {
    data class FillLayer(val paint: DslPaint) : MaterialLayer
    data class OutlineLayer(val width: Float, val position: Outline.Position, val paint: DslPaint) : MaterialLayer
    data class ShadowLayer(val shadows: List<ShadowSpec>) : MaterialLayer
    data class EffectLayer(val spec: EffectSpec) : MaterialLayer

    /** A nested `material` style: its own layers and its own (first) corner, or none. */
    data class Nested(val plan: MaterialPlan) : MaterialLayer
}

/** A material resolved for one appearance: what [Modifier.dslMaterial] draws. */
@Immutable
internal data class MaterialPlan(val corner: Corner?, val layers: List<MaterialLayer>) {
    val hasEffects: Boolean get() = layers.any { it is MaterialLayer.EffectLayer || (it is MaterialLayer.Nested && it.plan.hasEffects) }

    companion object {
        val EMPTY: MaterialPlan = MaterialPlan(null, emptyList())
    }
}

/**
 * iOS `MaterialView.update(material:)`: the first `corner` applies to every layer;
 * fill / outline / shadow layers (CALayers) in array order; visual effects and nested
 * materials (UIViews) above all of them, also in array order. Unresolved pointers and
 * layers that produce nothing are skipped.
 */
internal fun Material.resolve(appearance: App8Appearance): MaterialPlan {
    val corner = layers.firstNotNullOfOrNull { it.contentIf<Corner>(StyleType.Key.corner) }
    val below = mutableListOf<MaterialLayer>()
    val above = mutableListOf<MaterialLayer>()
    for (style in layers) {
        val key = (style.type as? StyleType.Known)?.key ?: continue
        when (key) {
            StyleType.Key.fill -> style.contentIf<Fill>(key)?.toPaint(appearance)?.let { below += MaterialLayer.FillLayer(it) }
            StyleType.Key.outline -> style.contentIf<Outline>(key)?.let { o ->
                o.fill.toPaint(appearance)?.let { below += MaterialLayer.OutlineLayer(max(0f, o.lineWidth), o.position, it) }
            }
            StyleType.Key.shadow -> style.contentIf<Shadow>(key)?.let { s ->
                if (s.layers.isNotEmpty()) below += MaterialLayer.ShadowLayer(s.layers.map { it.toSpec(appearance) })
            }
            StyleType.Key.visualEffect -> style.contentIf<VisualEffect>(key)?.let { above += MaterialLayer.EffectLayer(it.effectSpec(appearance)) }
            StyleType.Key.material -> style.contentIf<Material>(key)?.let { above += MaterialLayer.Nested(it.resolve(appearance)) }
            else -> Unit
        }
    }
    return MaterialPlan(corner, below + above)
}

internal fun Shadow.Layer.toSpec(appearance: App8Appearance): ShadowSpec = ShadowSpec(
    color = color.toColor(appearance),
    radius = max(0.0, radius).toFloat(),
    offsetX = offset.x.toFloat(),
    offsetY = offset.y.toFloat(),
    opacity = opacity.toFloat().coerceIn(0f, 1f),
    hollow = isHollow,
)

private inline fun <reified T : Any> dev.app8.engine.core.model.style.StyleAny.contentIf(key: StyleType.Key): T? =
    if (type.isKeyed(key)) (this as? StyleEntity<*>)?.content as? T else null

/** Geometry of an outline ring (iOS `Outline.Layer.layoutSublayers`), px. */
internal data class OutlineGeometry(val outer: Rect, val outerRadius: Float, val inner: Rect, val innerRadius: Float)

/**
 * The ring grows outward by 0 (inside), w/2 (center) or w (outside); its outer radius is
 * the base radius plus that growth, the inner one `outer − w`, both floored at 0.
 */
internal fun outlineGeometry(bounds: Rect, width: Float, position: Outline.Position, baseRadius: Float): OutlineGeometry {
    val w = max(0f, width)
    val grow = when (position) {
        Outline.Position.inside -> 0f
        Outline.Position.center -> w * 0.5f
        Outline.Position.outside -> w
    }
    val outer = bounds.inflate(grow)
    val outerR = max(0f, baseRadius + grow)
    return OutlineGeometry(outer, outerR, outer.deflate(w), max(0f, outerR - w))
}

/** Skia's `BlurMaskFilter` radius → sigma is `0.57735 r + 0.5`; invert it for a sigma in px. */
internal fun blurMaskRadiusForSigma(sigmaPx: Float): Float = if (sigmaPx <= 0.5f) 0f else (sigmaPx - 0.5f) / 0.57735f
