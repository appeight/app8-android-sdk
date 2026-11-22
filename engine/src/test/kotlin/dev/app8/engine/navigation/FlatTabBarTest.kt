package dev.app8.engine.navigation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.app8.engine.core.model.TabBarStyle
import dev.app8.engine.style.effectSpec
import org.junit.Test
import kotlin.test.assertEquals

/** Engine-drawn tab bars (tab-bar-screen.md, "Flat bars", 0.7.11). */
class FlatTabBarTest {
    @Test
    fun floatingFlatBarSafeAreaReachesItsTopEdge() {
        val floating = TabBarStyle(variant = TabBarStyle.Variant.text, background = "#000000", height = 32.0)
        // Over a 24dp gesture bar: bottom edge 21dp above the screen bottom (iOS 26 placement).
        assertEquals(32.0 + 21.0, tabBarSafeBottom(floating, 32.0, 24.0))
        assertEquals(21.0, tabBarBottomSpace(floating, 24.0))
        // No bottom inset: 8dp above the screen bottom.
        assertEquals(32.0 + 8.0, tabBarSafeBottom(floating, 32.0, 0.0))
        // Three-button navigation (48dp): 8dp above the buttons.
        assertEquals(32.0 + 56.0, tabBarSafeBottom(floating, 32.0, 48.0))
        // `bottomOffset`: from the system inset; the safe area still reaches the bar's top edge.
        assertEquals(32.0 + 24.0 + 10.0, tabBarSafeBottom(floating.copy(bottomOffset = 10.0), 32.0, 24.0))
        // Edge stripes ignore it.
        assertEquals(32.0 + 24.0, tabBarSafeBottom(floating.copy(shape = TabBarStyle.Shape.edge, bottomOffset = 10.0), 32.0, 24.0))
        val edge = floating.copy(shape = TabBarStyle.Shape.edge)
        assertEquals(32.0 + 24.0, tabBarSafeBottom(edge, 32.0, 24.0))
        val glassText = TabBarStyle(variant = TabBarStyle.Variant.text)
        assertEquals(56.0 + 24.0, tabBarSafeBottom(glassText, 56.0, 24.0))
        assertEquals(64.0 + 24.0, tabBarSafeBottom(null, 64.0, 24.0))
    }

    @Test
    fun cornersDefaultToACapsuleAndZeroIsARectangle() {
        val style = TabBarStyle(background = "solid")
        assertEquals(RoundedCornerShape(16.dp), tabBarShape(style, 32.0))
        assertEquals(RectangleShape, tabBarShape(style.copy(cornerRadius = 0.0), 32.0))
        assertEquals(RoundedCornerShape(6.dp), tabBarShape(style.copy(cornerRadius = 6.0), 32.0))
    }
}

/** `selection: opacity` fades labels only; badges stay at full opacity (tab-bar-screen.md, badgePlacement). */
class TabLabelOpacityTest {
    @Test
    fun opacitySelectionFadesUnselectedLabels() {
        val s = TabBarStyle.Selection.opacity
        assertEquals(1f, tabLabelAlpha(s, 0.3f, progress = 1f, index = 1))
        assertEquals(0.3f, tabLabelAlpha(s, 0.3f, progress = 1f, index = 0))
        assertEquals(0.3f, tabLabelAlpha(s, 0.3f, progress = 1f, index = 2))
        // Halfway through a switch both labels are half faded.
        assertEquals(0.65f, tabLabelAlpha(s, 0.3f, progress = 0.5f, index = 0), 0.0001f)
    }

    @Test
    fun otherSelectionsKeepLabelsOpaque() {
        for (s in listOf(TabBarStyle.Selection.capsule, TabBarStyle.Selection.underline, TabBarStyle.Selection.box)) {
            assertEquals(1f, tabLabelAlpha(s, 0.3f, progress = 0f, index = 2))
        }
    }
}

/** A material-style bar background (blur + tint) samples the tab content as its backdrop. */
class TabBarBackdropTest {
    @Test
    fun onlyStyleIdsSampleTheBackdrop() {
        kotlin.test.assertTrue(tabBarSamplesBackdrop("tabBarDarkBlur"))
        kotlin.test.assertFalse(tabBarSamplesBackdrop(null))
        kotlin.test.assertFalse(tabBarSamplesBackdrop("glass"))
        kotlin.test.assertFalse(tabBarSamplesBackdrop("solid"))
        kotlin.test.assertFalse(tabBarSamplesBackdrop("#00000080"))
    }
}

/** The dark blur material of a flat bar: systemMaterialDark is a dark translucent tint in any appearance. */
class TabBarDarkMaterialTest {
    @Test
    fun systemMaterialDarkIsDarkAndTranslucent() {
        for (a in listOf(dev.app8.engine.style.App8Appearance.Light, dev.app8.engine.style.App8Appearance.Dark)) {
            val spec = dev.app8.engine.core.model.style.BackgroundBlur.systemMaterialDark.effectSpec(a)
            kotlin.test.assertTrue(spec.tint.alpha in 0.5f..0.95f, "tint ${spec.tint}")
            kotlin.test.assertTrue(spec.tint.red < 0.2f && spec.tint.green < 0.2f && spec.tint.blue < 0.2f, "tint ${spec.tint}")
            kotlin.test.assertTrue(spec.blurSigmaDp > 0f)
        }
    }
}

/** Bar background materials draw in array order: a fill after a blur tints it. */
class TabBarMaterialOrderTest {
    private fun material(json: String): dev.app8.engine.core.model.style.Material {
        val e = dev.app8.engine.core.model.DslDecoder().decodeStyles(json).single()
        return (e as dev.app8.engine.core.model.style.StyleEntity<*>).content as dev.app8.engine.core.model.style.Material
    }
    private fun ids(m: dev.app8.engine.core.model.style.Material?) = m?.layers?.map { it.id }

    @Test
    fun fillAfterABlurDrawsAboveIt() {
        val (base, overlay) = splitFillsAfterEffects(material("""[{"id":"bar","type":"material","content":[
          {"id":"blur","type":"visualEffect","content":{"blur":"systemMaterialDark"}},
          {"id":"tint","type":"fill","content":{"solid":"#00000080"}}]}]"""))
        assertEquals(listOf("blur"), ids(base))
        assertEquals(listOf("tint"), ids(overlay))
    }

    @Test
    fun fillBeforeTheBlurAndPlainMaterialsStayWhole() {
        val (base, overlay) = splitFillsAfterEffects(material("""[{"id":"bar","type":"material","content":[
          {"id":"under","type":"fill","content":{"solid":"#FF0000"}},
          {"id":"c","type":"corner","content":{"radius":8}},
          {"id":"blur","type":"visualEffect","content":{"blur":"dark"}},
          {"id":"tint","type":"fill","content":{"solid":"#00000080"}}]}]"""))
        assertEquals(listOf("under", "c", "blur"), ids(base))
        // The overlay keeps the corner so the tint is clipped like the blur.
        assertEquals(listOf("c", "tint"), ids(overlay))
        val plain = material("""[{"id":"p","type":"material","content":[{"id":"f","type":"fill","content":{"solid":"#000000"}}]}]""")
        assertEquals(plain to null, splitFillsAfterEffects(plain))
    }
}
