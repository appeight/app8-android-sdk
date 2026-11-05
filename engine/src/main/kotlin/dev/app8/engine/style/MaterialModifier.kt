package dev.app8.engine.style

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.shadow.ShadowContext
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Material
import kotlin.math.ceil
import kotlin.math.max
import androidx.compose.ui.Modifier.Node

/**
 * Draws a DSL [Material] behind the content, the way iOS `MaterialView` stacks its
 * layers (see [resolve]). Nothing is clipped: shadows and outside outlines extend past
 * the bounds as on iOS. Clip children separately with [dslClip] when the component clips.
 *
 * [appearance] `null` follows [LocalApp8Appearance].
 */
internal fun Modifier.dslMaterial(material: Material, appearance: App8Appearance? = null): Modifier =
    this then DslMaterialElement(material, appearance)

/** Clips content to the material's first corner (iOS `clipsToBounds` with the corner's radius and curve). */
internal fun Modifier.dslClip(material: Material): Modifier {
    val corner = material.layers.firstNotNullOfOrNull { (it as? dev.app8.engine.core.model.style.StyleEntity<*>)?.content as? Corner }
    return if (corner == null) this else clip(DslCornerShape(corner))
}

private data class DslMaterialElement(val material: Material, val appearance: App8Appearance?) :
    ModifierNodeElement<DslMaterialNode>() {
    override fun create() = DslMaterialNode(material, appearance)
    override fun update(node: DslMaterialNode) = node.update(material, appearance)
    override fun InspectorInfo.inspectableProperties() {
        name = "dslMaterial"
        properties["material"] = material
        properties["appearance"] = appearance
    }
}

private val GLASS_LIFT = ShadowSpec(Color.Black, radius = 4f, offsetX = 0f, offsetY = 2f, opacity = 0.12f)

/** Downsampling of the backdrop before blurring: blur hides it, and it keeps the effect cheap. */
private const val BACKDROP_SCALE = 0.25f

private class DslMaterialNode(
    private var material: Material,
    private var appearance: App8Appearance?,
) : Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var cachedPlan: MaterialPlan? = null
    private var cachedFor: App8Appearance? = null
    private var positionInRoot = Offset.Zero
    private val effectLayers = mutableListOf<GraphicsLayer>()
    private val renderEffects = HashMap<Pair<EffectSpec, Float>, androidx.compose.ui.graphics.RenderEffect>()

    fun update(material: Material, appearance: App8Appearance?) {
        if (material != this.material || appearance != this.appearance) {
            this.material = material
            this.appearance = appearance
            cachedPlan = null
            invalidateDraw()
        }
    }

    private fun plan(): MaterialPlan {
        val a = appearance ?: currentValueOf(LocalApp8Appearance)
        cachedPlan?.let { if (cachedFor == a) return it }
        return material.resolve(a).also { cachedPlan = it; cachedFor = a }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != positionInRoot) {
            positionInRoot = p
            if (cachedPlan?.hasEffects == true) invalidateDraw()
        }
    }

    override fun onDetach() {
        val gc = currentValueOf(LocalGraphicsContext)
        effectLayers.forEach(gc::releaseGraphicsLayer)
        effectLayers.clear()
        renderEffects.clear()
    }

    override fun ContentDrawScope.draw() {
        val plan = plan()
        if (plan.layers.isNotEmpty() && size.width > 0f && size.height > 0f) {
            var effectIndex = 0
            drawPlan(plan, currentValueOf(LocalGraphicsContext).shadowContext) { spec, shape -> drawEffect(spec, shape, effectIndex++) }
        }
        drawContent()
    }

    private fun DrawScope.drawEffect(spec: EffectSpec, shape: ShapePaths, index: Int) {
        if (spec.glass) drawGlassLift(shape)
        val backdrop = currentValueOf(LocalApp8Backdrop)
        val source = backdrop?.layer
        shape.clip(this) {
            if (source == null) {
                drawRect(spec.fallback)
            } else {
                val layer = effectLayer(index)
                val offset = positionInRoot - backdrop.positionInRoot
                val k = BACKDROP_SCALE
                val scaled = IntSize(max(1, ceil(size.width * k).toInt()), max(1, ceil(size.height * k).toInt()))
                layer.renderEffect = renderEffect(spec, density * k)
                layer.record(this, layoutDirection, scaled) {
                    scale(k, k, pivot = Offset.Zero) {
                        translate(-offset.x, -offset.y) { drawLayer(source) }
                    }
                }
                scale(1f / k, 1f / k, pivot = Offset.Zero) { drawLayer(layer) }
                drawRect(spec.tint)
            }
        }
        if (spec.glass) drawGlassRim(shape)
    }

    private fun effectLayer(index: Int): GraphicsLayer {
        while (effectLayers.size <= index) effectLayers += currentValueOf(LocalGraphicsContext).createGraphicsLayer()
        return effectLayers[index]
    }

    /** Blur (sigma in layer px) then saturation and brightness, as UIKit materials do. */
    private fun renderEffect(spec: EffectSpec, pxPerDp: Float): androidx.compose.ui.graphics.RenderEffect =
        renderEffects.getOrPut(spec to pxPerDp) {
            val r = blurMaskRadiusForSigma(spec.blurSigmaDp * pxPerDp).coerceAtLeast(0.01f)
            val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            val matrix = ColorMatrix().apply { setSaturation(spec.saturation) }
            if (spec.brightness != 1f) matrix.postConcat(ColorMatrix().apply { setScale(spec.brightness, spec.brightness, spec.brightness, 1f) })
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), blur).asComposeRenderEffect()
        }

    /** Web `GLASS`: `0 2px 8px rgba(0,0,0,.12)` outside the shape only. */
    private fun DrawScope.drawGlassLift(shape: ShapePaths) {
        clipPath(shape.path, ClipOp.Difference) {
            drawShadow(GLASS_LIFT, shape.corner, currentValueOf(LocalGraphicsContext).shadowContext)
        }
    }

    /** Web `GLASS` specular rim: bright on top, softer below, hairline all round. */
    private fun DrawScope.drawGlassRim(shape: ShapePaths) {
        val w = 1.dp.toPx()
        val rim = Path().apply { addRoundedRect(Rect(Offset.Zero, size).deflate(w / 2), CornerRadii(max(0f, shape.radius - w / 2)), shape.smoothing) }
        drawPath(
            rim,
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.85f), 0.5f to Color.White.copy(alpha = 0.22f), 1f to Color.White.copy(alpha = 0.35f)),
            style = Stroke(w),
        )
    }
}

/** The material's outline in px, built once per draw. */
internal class ShapePaths(val corner: Corner?, val radius: Float, val smoothing: Float, val bounds: Rect) {
    val path: Path by lazy { Path().apply { if (radius > 0f) addRoundedRect(bounds, CornerRadii(radius), smoothing) else addRect(bounds) } }

    inline fun clip(scope: DrawScope, block: DrawScope.() -> Unit) {
        if (radius > 0f) scope.clipPath(path, block = block) else scope.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, block = block)
    }
}

/** px radius of [corner] for the current draw size, clamped like `CALayer.cornerRadius`. */
internal fun DrawScope.cornerRadiusPx(corner: Corner?): Float {
    corner ?: return 0f
    val r = corner.radius.resolved((size.width / density).toDouble(), (size.height / density).toDouble()).toFloat() * density
    return clampRadius(r, size.width, size.height)
}

/** Draws the layers of [plan] in order; effects are delegated to [effect] (they need node state). */
internal fun DrawScope.drawPlan(plan: MaterialPlan, shadows: ShadowContext, effect: DrawScope.(EffectSpec, ShapePaths) -> Unit) {
    val bounds = Rect(Offset.Zero, size)
    val radius = cornerRadiusPx(plan.corner)
    val smoothing = plan.corner?.smoothing ?: 0f
    val shape = ShapePaths(plan.corner, radius, smoothing, bounds)
    for (layer in plan.layers) {
        when (layer) {
            is MaterialLayer.FillLayer -> {
                val brush = layer.paint.toBrush(bounds)
                if (radius > 0f) drawPath(shape.path, brush) else drawRect(brush)
            }
            is MaterialLayer.OutlineLayer -> {
                if (layer.width <= 0f) continue
                val g = outlineGeometry(bounds, layer.width * density, layer.position, radius)
                val ring = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundedRect(g.outer, CornerRadii(g.outerRadius), smoothing)
                    addRoundedRect(g.inner, CornerRadii(g.innerRadius), smoothing)
                }
                drawPath(ring, layer.paint.toBrush(g.outer))
            }
            is MaterialLayer.ShadowLayer -> layer.shadows.forEach { s ->
                drawShadow(s, plan.corner, shadows)
                if (!s.hollow) {
                    if (radius > 0f) drawPath(shape.path, s.color) else drawRect(s.color)
                }
            }
            is MaterialLayer.EffectLayer -> effect(layer.spec, shape)
            is MaterialLayer.Nested -> drawPlan(layer.plan, shadows, effect)
        }
    }
}

/** One `CALayer`-style shadow of the box with [corner] (see [ShadowSpec]). */
internal fun DrawScope.drawShadow(spec: ShadowSpec, corner: Corner?, shadows: ShadowContext) {
    if (spec.opacity <= 0f || spec.color.alpha <= 0f) return
    val shape = if (corner == null) {
        androidx.compose.ui.graphics.RectangleShape
    } else {
        ContinuousRoundedShape((cornerRadiusPx(corner) / density).dp, corner.smoothing)
    }
    // Compose feeds `radius` (px) straight into BlurMaskFilter; CA's radius is a sigma.
    val blurRadius = blurMaskRadiusForSigma(spec.radius * density) / density
    val shadow = Shadow(
        radius = blurRadius.dp,
        color = spec.color,
        spread = spec.spread.dp,
        offset = DpOffset(spec.offsetX.dp, spec.offsetY.dp),
        alpha = spec.opacity,
    )
    // The platform context caches the blurred bitmap per shape, size and shadow.
    val painter = shadows.createDropShadowPainter(shape, shadow)
    with(painter) { draw(size) }
}

