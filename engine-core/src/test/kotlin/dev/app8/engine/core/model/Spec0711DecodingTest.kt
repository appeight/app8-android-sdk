package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.7.11 keys: `barStyle.shape`, `contentInsetAdjustment: bottom`, `preview.cornerRadius`. All lenient. */
class Spec0711DecodingTest {

    private fun barStyle(json: String): TabBarStyle {
        val entity = assertIs<ComponentEntity<*>>(
            DslDecoder().decodeComponent(
                """{"type":"tabBarScreen","id":"tabs","content":{
                  "tabs":[{"id":"home","label":"HOME","icon":"house","screen":"homeScreen"}],
                  "barStyle":$json}}""",
            ),
        )
        return assertNotNull(assertIs<TabBarScreenContent>(entity.content).barStyle)
    }

    @Test
    fun edgeStripeDecodesWithItsExactHeight() {
        val s = barStyle("""{"variant":"text","shape":"edge","height":32,"background":"#000000","selection":"opacity","unselectedOpacity":0.3}""")
        assertEquals(TabBarStyle.Shape.edge, s.shape)
        assertTrue(s.isEdge)
        assertEquals(32.0, s.height)
        assertEquals("#000000", s.background)
        assertFalse(s.isSystem)
    }

    @Test
    fun edgeHeightIsAtLeast28AndFloatingAtLeast44() {
        assertEquals(28.0, barStyle("""{"variant":"text","shape":"edge","height":12}""").height)
        assertEquals(44.0, barStyle("""{"variant":"text","height":32}""").height)
        assertEquals(44.0, barStyle("""{"variant":"text","shape":"floating","height":12}""").height)
    }

    @Test
    fun shapeDefaultsAndUnknownValuesAreFloating() {
        assertEquals(TabBarStyle.Shape.floating, barStyle("""{"variant":"text"}""").shape)
        assertEquals(TabBarStyle.Shape.floating, barStyle("""{"variant":"text","shape":"pill"}""").shape)
        assertEquals(TabBarStyle.Shape.floating, barStyle("""{"variant":"text","shape":7}""").shape)
        // A bad height next to an edge shape keeps the rest of the style.
        val s = barStyle("""{"variant":"text","shape":"edge","height":"tall"}""")
        assertEquals(TabBarStyle.Shape.edge, s.shape)
        assertNull(s.height)
    }

    @Test
    fun flatFloatingBarIsEngineDrawnWithExactHeightAndCorners() {
        val s = barStyle("""{"variant":"text","shape":"floating","background":"#000000","cornerRadius":0,"height":32,"badgePlacement":"inline"}""")
        assertEquals(32.0, s.height)
        assertEquals(0.0, s.cornerRadius)
        assertEquals(TabBarStyle.BadgePlacement.inline, s.badgePlacement)
        assertTrue(s.isEngineDrawn)
        assertTrue(s.isInsetFloating)
        assertFalse(s.isSystem)
    }

    @Test
    fun systemVariantWithANonGlassBackgroundIsEngineDrawn() {
        val solid = barStyle("""{"background":"solid","height":20}""")
        assertFalse(solid.isSystem)
        assertTrue(solid.isInsetFloating)
        assertEquals(28.0, solid.height)
        // Glass (explicit or default) stays the platform bar, at least 44.
        val glass = barStyle("""{"background":"glass","height":20}""")
        assertTrue(glass.isSystem)
        assertFalse(glass.isEngineDrawn)
        assertEquals(44.0, glass.height)
        assertTrue(barStyle("{}").isSystem)
        // Edge is engine-drawn whatever the background, and never inset.
        val edge = barStyle("""{"shape":"edge","background":"#111111"}""")
        assertTrue(edge.isEngineDrawn)
        assertFalse(edge.isInsetFloating)
        assertFalse(edge.isSystem)
    }

    @Test
    fun cornerRadiusAndBadgePlacementAreLenient() {
        val d = barStyle("""{"variant":"text","background":"#000"}""")
        assertNull(d.cornerRadius)
        assertEquals(TabBarStyle.BadgePlacement.corner, d.badgePlacement)
        val bad = barStyle("""{"variant":"text","background":"#000","cornerRadius":-2,"badgePlacement":"below","height":40}""")
        assertNull(bad.cornerRadius)
        assertEquals(TabBarStyle.BadgePlacement.corner, bad.badgePlacement)
        assertEquals(40.0, bad.height)
        val wrongTypes = barStyle("""{"variant":"text","background":"#000","cornerRadius":"big","badgePlacement":3}""")
        assertNull(wrongTypes.cornerRadius)
        assertEquals(TabBarStyle.BadgePlacement.corner, wrongTypes.badgePlacement)
        assertEquals("#000", wrongTypes.background)
    }

    @Test
    fun floatingBarBottomFollowsTheIos26Placement() {
        assertEquals(8.0, TabBarStyle.floatingBarBottom(0.0))
        assertEquals(21.0, TabBarStyle.floatingBarBottom(34.0)) // 13 into a 34 inset
        assertEquals(21.0, TabBarStyle.floatingBarBottom(24.0)) // Pixel gesture bar
        assertEquals(56.0, TabBarStyle.floatingBarBottom(48.0)) // three-button navigation
        assertEquals(8.0, TabBarStyle.floatingBarBottom(Double.NaN))
    }

    private fun scroll(json: String) = DslDecoder().decode(json, ::decodeScrollViewProperties)

    @Test
    fun contentInsetAdjustmentBottom() {
        val p = scroll("""{"contentInsetAdjustment":"bottom","stretchesTopChild":true}""")
        assertEquals(ScrollViewProperties.ContentInsetAdjustment.bottom, p.contentInsetAdjustment)
        assertEquals(true, p.stretchesTopChild)
        assertFalse(ScrollViewProperties.ContentInsetAdjustment.bottom.adjustsTop)
        assertTrue(ScrollViewProperties.ContentInsetAdjustment.bottom.adjustsBottom)
        assertTrue(ScrollViewProperties.ContentInsetAdjustment.automatic.adjustsTop)
        assertTrue(ScrollViewProperties.ContentInsetAdjustment.automatic.adjustsBottom)
        assertFalse(ScrollViewProperties.ContentInsetAdjustment.never.adjustsTop)
        assertFalse(ScrollViewProperties.ContentInsetAdjustment.never.adjustsBottom)
    }

    @Test
    fun unknownContentInsetAdjustmentIsAutomaticNotAFailure() {
        assertEquals(ScrollViewProperties.ContentInsetAdjustment.automatic, scroll("""{"contentInsetAdjustment":"top"}""").contentInsetAdjustment)
        val p = scroll("""{"contentInsetAdjustment":3,"bounces":false}""")
        assertEquals(ScrollViewProperties.ContentInsetAdjustment.automatic, p.contentInsetAdjustment)
        assertEquals(false, p.bounces)
        assertNull(scroll("""{}""").contentInsetAdjustment)
    }

    private fun preview(extra: String): ContextMenu.Preview {
        val c = DslDecoder().decodeComponent(
            """{"type":"view","id":"card","content":{"contextMenu":{
              "preview":{"component":{"type":"label","content":{"properties":{"text":"Hi"}}}$extra},
              "items":[]}}}""",
        )
        return assertNotNull(c.contentAs<ViewContent>()?.contextMenu?.preview)
    }

    @Test
    fun previewCornerRadius() {
        assertNull(preview("").cornerRadius)
        assertEquals(0.0, preview(""","cornerRadius":0""").cornerRadius)
        assertEquals(20.0, preview(""","cornerRadius":20""").cornerRadius)
    }

    @Test
    fun badPreviewCornerRadiusKeepsThePreview() {
        assertNull(preview(""","cornerRadius":"round"""").cornerRadius)
        assertNull(preview(""","cornerRadius":-4""").cornerRadius)
        assertEquals(300.0, preview(""","width":300,"cornerRadius":{}""").width)
    }
}
