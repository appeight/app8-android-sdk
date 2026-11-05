package dev.app8.engine.style

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.app8.engine.core.model.style.BackgroundBlur
import dev.app8.engine.core.model.style.Corner
import dev.app8.engine.core.model.style.Glass
import dev.app8.engine.core.model.style.Outline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialPlanTest {

    private val card = TestStyles.material(
        """
        [
          { "id": "fx", "type": "visualEffect", "content": { "blur": "systemMaterial" } },
          { "id": "bg", "type": "fill", "content": { "solid": { "light": "#FFFFFF", "dark": "#1C1C1E" } } },
          { "id": "c1", "type": "corner", "content": { "radius": 16, "curve": "continuous" } },
          { "id": "c2", "type": "corner", "content": { "radius": 4 } },
          { "id": "sh", "type": "shadow", "content": { "layers": [
              { "color": "#000000", "radius": 8, "offset": { "x": 0, "y": 4 }, "opacity": 0.25, "isHollow": true },
              { "color": "#FF0000", "radius": 2, "offset": { "x": 1, "y": 2 }, "opacity": 1, "isHollow": false }
          ] } },
          { "id": "ol", "type": "outline", "content": { "lineWidth": 1, "position": "outside", "fill": { "solid": "#00000033" } } },
          { "id": "ptr", "type": "fill" }
        ]
        """,
    )

    @Test
    fun `layers stack like MaterialView, effects above CALayers, first corner wins`() {
        val plan = card.resolve(App8Appearance.Light)
        assertEquals(Corner.Curve.continuous, plan.corner?.curve)
        assertEquals(Corner.Radius.Fixed(16.0), plan.corner?.radius)
        assertEquals(
            listOf("FillLayer", "ShadowLayer", "OutlineLayer", "EffectLayer"),
            plan.layers.map { it::class.simpleName },
        )
        assertTrue(plan.hasEffects)
    }

    @Test
    fun `fill resolves for the appearance`() {
        val light = card.resolve(App8Appearance.Light).layers[0] as MaterialLayer.FillLayer
        val dark = card.resolve(App8Appearance.Dark).layers[0] as MaterialLayer.FillLayer
        assertEquals(DslPaint.Solid(Color.White), light.paint)
        assertEquals(0xFF1C1C1E.toInt(), (dark.paint as DslPaint.Solid).color.toArgb())
    }

    @Test
    fun `shadow layers keep CALayer semantics`() {
        val shadows = (card.resolve(App8Appearance.Light).layers[1] as MaterialLayer.ShadowLayer).shadows
        assertEquals(ShadowSpec(Color.Black, 8f, 0f, 4f, 0.25f, spread = 0f, hollow = true), shadows[0])
        assertEquals(false, shadows[1].hollow)
        assertEquals(1f, shadows[1].offsetX)
    }

    @Test
    fun `solid wins over gradient and gradient stops follow iOS colorsList`() {
        val m = TestStyles.material(
            """
            [
              { "id": "a", "type": "fill", "content": { "solid": "#F00", "gradient": { "start": { "x": 0, "y": 0, "color": "#000" }, "end": { "x": 1, "y": 1, "color": "#FFF" } } } },
              { "id": "b", "type": "fill", "content": { "gradient": {
                  "start": { "x": 0, "y": 0, "color": "#FF0000" },
                  "end": { "x": 1, "y": 0.5 },
                  "middlePoints": [ { "position": 0.3, "color": "#00FF00" } ] } } }
            ]
            """,
        )
        val (a, b) = m.resolve(App8Appearance.Light).layers.map { (it as MaterialLayer.FillLayer).paint }
        assertEquals(DslPaint.Solid(Color.Red), a)
        val g = (b as DslPaint.Gradient).spec as GradientSpec.Linear
        assertEquals(Offset(0f, 0f), g.start)
        assertEquals(Offset(1f, 0.5f), g.end)
        assertEquals(listOf(0f, 0.3f, 1f), g.stops.map { it.position })
        assertEquals(listOf(Color.Red, Color.Green, Color.Transparent), g.stops.map { it.color })
    }

    @Test
    fun `gradient stop positions are made monotonic for Skia`() {
        val stops = listOf(0f, 0.6f, 0.4f, 1.2f).map { GradientStop(it, Color.Black) }
        assertEquals(listOf(0f, 0.6f, 0.6f, 1f), monotonicStops(stops))
    }

    @Test
    fun `nested material keeps its own corner and draws above`() {
        val m = TestStyles.material(
            """
            [
              { "id": "n", "type": "material", "content": [
                  { "id": "g", "type": "visualEffect", "content": { "glass": "normal" } },
                  { "id": "c", "type": "corner", "content": { "radius": "capsule" } }
              ] },
              { "id": "f", "type": "fill", "content": { "solid": "#000" } }
            ]
            """,
        )
        val plan = m.resolve(App8Appearance.Light)
        assertEquals(null, plan.corner)
        assertTrue(plan.layers[0] is MaterialLayer.FillLayer)
        val nested = (plan.layers[1] as MaterialLayer.Nested).plan
        assertEquals(Corner.Radius.Capsule, nested.corner?.radius)
        assertTrue((nested.layers.single() as MaterialLayer.EffectLayer).spec.glass)
    }

    @Test
    fun `outline ring grows by position like Outline Layer`() {
        val b = Rect(0f, 0f, 100f, 50f)
        val inside = outlineGeometry(b, 2f, Outline.Position.inside, 10f)
        assertEquals(b, inside.outer)
        assertEquals(Rect(2f, 2f, 98f, 48f), inside.inner)
        assertEquals(10f, inside.outerRadius)
        assertEquals(8f, inside.innerRadius)
        val center = outlineGeometry(b, 2f, Outline.Position.center, 10f)
        assertEquals(Rect(-1f, -1f, 101f, 51f), center.outer)
        assertEquals(11f, center.outerRadius)
        assertEquals(9f, center.innerRadius)
        val outside = outlineGeometry(b, 2f, Outline.Position.outside, 0f)
        assertEquals(Rect(-2f, -2f, 102f, 52f), outside.outer)
        assertEquals(b, outside.inner)
        assertEquals(2f, outside.outerRadius)
        assertEquals(0f, outside.innerRadius)
    }

    @Test
    fun `CA shadow radius is a sigma, converted for BlurMaskFilter`() {
        // Skia: sigma = 0.57735 r + 0.5.
        val r = blurMaskRadiusForSigma(12f)
        assertEquals(12f, 0.57735f * r + 0.5f, 1e-4f)
        assertEquals(0f, blurMaskRadiusForSigma(0f))
    }

    @Test
    fun `blur styles adapt to appearance unless they are Dark variants`() {
        val light = BackgroundBlur.systemMaterial.effectSpec(App8Appearance.Light)
        val dark = BackgroundBlur.systemMaterial.effectSpec(App8Appearance.Dark)
        assertTrue(light.tint.red > 0.9f && dark.tint.red < 0.2f)
        val forcedDark = BackgroundBlur.systemMaterialDark.effectSpec(App8Appearance.Light)
        assertTrue(forcedDark.tint.red < 0.2f)
        // Thicker materials are more opaque.
        val alphas = listOf(
            BackgroundBlur.systemUltraThinMaterial, BackgroundBlur.systemThinMaterial,
            BackgroundBlur.systemMaterial, BackgroundBlur.systemThickMaterial,
        ).map { it.effectSpec(App8Appearance.Light).tint.alpha }
        assertEquals(alphas.sorted(), alphas)
        assertTrue(Glass.clear.effectSpec(App8Appearance.Light).tint.alpha < Glass.normal.effectSpec(App8Appearance.Light).tint.alpha)
    }
}
