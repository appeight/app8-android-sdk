package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.Corner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of iOS ScreenTransitionTests.swift — decoding only.
// Skipped: preset expansion (`ScreenTransition.resolve`: slide/zoom/cover/none/system/shared/popup/sheet
// keyframes, preset presentation merge) and frame computation — those belong to the
// navigation layer, not the model port. Their decode halves are asserted on the raw inline here.
class ScreenTransitionTest {

    private fun transition(
        json: String,
        resolver: ((String) -> ScreenTransition.Inline?)? = null,
        animationResolver: ((String) -> Animation.Inline?)? = null,
    ): ScreenTransition =
        DslDecoder(DecodeOptions(transitionResolver = resolver, animationResolver = animationResolver)).decode(json, ::decodeScreenTransition)

    private fun componentTransition(json: String, animationResolver: ((String) -> Animation.Inline?)? = null): ComponentTransition =
        DslDecoder(DecodeOptions(animationResolver = animationResolver)).decode(json, ::decodeComponentTransition)

    private fun inline(json: String): ScreenTransition.Inline = assertNotNull(transition(json).inlineOrNull)

    @Test
    fun bareStringPreset() = assertEquals(ScreenTransition.Preset.slide, inline("\"slide\"").preset)

    @Test
    fun unknownBareStringFallsBackToSystem() = assertEquals(ScreenTransition.Preset.system, inline("\"totally-unknown\"").preset)

    @Test
    fun flatInline() {
        val i = inline("""{ "mode": "modal", "preset": "cover", "edge": "bottom" }""")
        assertEquals(ScreenTransition.Mode.modal, i.mode)
        assertEquals(ScreenTransition.Preset.cover, i.preset)
        assertEquals(ScreenTransition.Edge.bottom, i.edge)
    }

    @Test
    fun unknownEnumValuesDegrade() {
        val i = inline("""{ "mode": "sideways", "preset": "warp", "edge": "diagonal", "raise": "top" }""")
        assertEquals(ScreenTransition.Mode.push, i.mode)
        assertEquals(ScreenTransition.Preset.system, i.preset)
        assertEquals(ScreenTransition.Edge.trailing, i.edge)
        assertEquals(ScreenTransition.Raise.incoming, i.raise)
    }

    @Test
    fun wrappedInline() {
        val i = inline("""{ "id": "hero", "type": "transition", "content": { "preset": "fade" } }""")
        assertEquals("hero", i.id)
        assertEquals(ScreenTransition.Preset.fade, i.preset)
    }

    @Test
    fun preservesPointerWithoutResolver() {
        val t = transition("""{ "id": "hero" }""")
        assertTrue(t.isPointer)
        assertEquals("hero", t.pointerId)
        assertNull(t.inlineOrNull)
    }

    @Test
    fun resolvesPointerWithResolver() {
        val target = ScreenTransition.Inline(id = "hero", preset = ScreenTransition.Preset.slide)
        val t = transition("""{ "id": "hero" }""", resolver = { if (it == "hero") target else null })
        assertEquals(ScreenTransition.Preset.slide, assertNotNull(t.inlineOrNull).preset)
    }

    @Test
    fun lengthDecodesPoints() {
        val i = inline("""{ "to": { "begin": { "translate": { "x": 40, "y": 0 } } } }""")
        assertEquals(40.0, i.to!!.begin.translate.x.resolved(1000.0))
    }

    @Test
    fun lengthDecodesPercentFraction() {
        val i = inline("""{ "to": { "begin": { "translate": { "x": "100%", "y": "-50%" } } } }""")
        assertEquals(320.0, i.to!!.begin.translate.x.resolved(320.0))
        assertEquals(-300.0, i.to!!.begin.translate.y.resolved(600.0))
    }

    @Test
    fun invalidLengthFailsTheTransition() {
        kotlin.test.assertFailsWith<DslDecodingException> { transition("""{ "to": { "begin": { "translate": { "x": "wide" } } } }""") }
    }

    @Test
    fun transitionStateDefaultsToIdentity() {
        val state = inline("""{ "to": { "begin": {} } }""").to!!.begin
        assertNull(state.opacity)
        assertEquals(0.0, state.translate.x.resolved(100.0))
        assertEquals(1.0, state.scale.x)
        assertEquals(1.0, state.scale.y)
        assertEquals(0.0, state.rotate)
        assertEquals(0.5, state.anchor.x)
        // Missing `end` is identity too.
        assertEquals(ScreenTransition.TransitionState.IDENTITY, inline("""{ "to": { "begin": {} } }""").to!!.end)
    }

    @Test
    fun scaleDecodesFromSingleNumberOrObject() {
        val uniform = inline("""{ "to": { "begin": { "scale": 0.5 } } }""").to!!.begin.scale
        assertEquals(ScreenTransition.Scale(0.5, 0.5), uniform)
        val aniso = inline("""{ "to": { "begin": { "scale": { "x": 0.5, "y": 1.5 } } } }""").to!!.begin.scale
        assertEquals(ScreenTransition.Scale(0.5, 1.5), aniso)
    }

    @Test
    fun customKeyframesDecode() {
        val i = inline(
            """{ "mode": "push",
                 "to":   { "begin": { "opacity": 0, "scale": 0.6, "rotate": -12 }, "end": {} },
                 "from": { "begin": {}, "end": { "opacity": 0.4 } } }""",
        )
        assertEquals(0.0, i.to!!.begin.opacity)
        assertEquals(0.6, i.to!!.begin.scale.x)
        assertEquals(-12.0, i.to!!.begin.rotate)
        assertEquals(0.4, i.from!!.end.opacity)
        assertEquals(ScreenTransition.Reverse.Auto, i.reverse)
    }

    @Test
    fun raiseDefaultsToNullAndDecodesOutgoing() {
        assertNull(inline("""{ "preset": "slide" }""").raise)
        val out = inline(
            """{ "mode": "push", "raise": "outgoing",
                 "from": { "begin": {}, "end": { "translate": { "x": 0, "y": "100%" } } },
                 "to": { "begin": {}, "end": {} } }""",
        )
        assertEquals(ScreenTransition.Raise.outgoing, out.raise)
    }

    @Test
    fun explicitReverseDecodes() {
        val i = inline(
            """{ "preset": "slide",
                 "reverse": { "from": { "begin": {}, "end": { "opacity": 0 } }, "to": { "begin": {}, "end": {} } } }""",
        )
        val reverse = assertIs<ScreenTransition.Reverse.Explicit>(i.reverse)
        assertEquals(0.0, reverse.from!!.end.opacity)
        // Any string means auto.
        assertEquals(ScreenTransition.Reverse.Auto, inline("""{ "reverse": "auto" }""").reverse)
    }

    @Test
    fun animationPointerResolvesViaResolver() {
        val smooth = Animation.Inline(id = "smooth", duration = 0.7)
        val i = assertNotNull(
            transition("""{ "preset": "fade", "animation": { "id": "smooth" } }""", animationResolver = { if (it == "smooth") smooth else null })
                .inlineOrNull,
        )
        assertEquals(0.7, assertNotNull(i.animation?.inlineOrNull).duration)
    }

    @Test
    fun normalizeAnimationResolvesPointerLater() {
        val i = inline("""{ "preset": "fade", "animation": { "id": "smooth" } }""")
        assertTrue(i.animation!!.isPointer)
        val n = i.normalizeAnimation { if (it == "smooth") Animation.Inline(id = "smooth", duration = 0.7) else null }
        assertEquals(0.7, assertNotNull(n.animation?.inlineOrNull).duration)
    }

    @Test
    fun appManifestDecodesTransitionsRegistryAndDefault() {
        val app = DslDecoder().decodeApp(
            """{
              "title": "Gallery",
              "navigation": { "startFlow": "main", "flows": [ { "id": "main", "startScreen": "menu" } ] },
              "animations": [ { "id": "smooth", "type": "animation", "content": { "duration": 0.45, "curve": "easeInOut" } } ],
              "transitions": [
                { "id": "slideTrans", "type": "transition", "content": { "preset": "slide", "edge": "trailing", "animation": { "id": "smooth" } } },
                { "id": "coverModal", "type": "transition", "content": { "preset": "cover", "mode": "modal", "dimming": { "color": "#000000", "opacity": 0.5 } } }
              ],
              "defaultTransition": { "id": "slideTrans" }
            }""",
        )
        assertEquals(2, app.transitions?.size)
        assertEquals("slideTrans", app.defaultTransition?.pointerId)
        assertEquals("smooth", app.animations!!.single().inlineOrNull?.id)
        val slide = assertNotNull(app.transitions!![0].inlineOrNull)
        assertEquals("slideTrans", slide.id)
        assertEquals("smooth", slide.animation?.pointerId)
    }

    @Test
    fun screenContentDecodesTransitionDefault() {
        val component = DslDecoder().decodeComponent(
            """{ "type": "screen", "id": "detailDefault",
                 "content": { "properties": {}, "transition": { "preset": "fade" }, "children": [] } }""",
        )
        val content = assertNotNull(component.contentAs<ViewContent>())
        assertEquals(ScreenTransition.Preset.fade, content.transition?.screenOrNull?.inlineOrNull?.preset)
    }

    @Test
    fun dimmingAndInteractiveDecode() {
        val i = inline(
            """{ "preset": "cover", "mode": "modal",
                 "dimming": { "color": "#000000", "opacity": 0.5 },
                 "interactive": { "enabled": true, "edge": "bottom", "threshold": 0.25, "velocity": 650 } }""",
        )
        val dimming = assertNotNull(i.dimming)
        assertEquals(0.5, dimming.opacity)
        assertEquals("000000", dimming.color?.value)
        val interactive = assertNotNull(i.interactive)
        assertTrue(interactive.enabled)
        assertEquals(0.25, interactive.threshold)
        assertEquals(650.0, interactive.velocity)
        assertEquals(ScreenTransition.Edge.bottom, interactive.edge)
    }

    @Test
    fun interactiveDefaults() {
        val interactive = assertNotNull(inline("""{ "interactive": {} }""").interactive)
        assertEquals(ScreenTransition.InteractiveConfig(true, null, 0.3, 800.0), interactive)
    }

    @Test
    fun componentTransitionElementWhenKeyPresent() {
        val ct = componentTransition("""{ "key": "hero", "morph": "frame", "fallback": "slideTop", "stagger": 0.05 }""")
        val e = assertNotNull(ct.elementOrNull)
        assertNull(ct.screenOrNull)
        assertEquals("hero", e.key)
        assertEquals(ScreenTransition.Morph.frame, e.morph)
        assertEquals(ScreenTransition.Fallback.slideTop, e.fallback)
        assertEquals(0.05, e.stagger)
    }

    @Test
    fun componentTransitionElementDefaults() {
        val e = assertNotNull(componentTransition("""{ "key": "card" }""").elementOrNull)
        assertEquals(ScreenTransition.Morph.frameFade, e.morph)
        assertEquals(ScreenTransition.Fallback.fade, e.fallback)
        assertNull(e.stagger)
        assertNull(e.animation)
    }

    @Test
    fun componentTransitionScreenForBareStringPresetAndPointer() {
        val bare = componentTransition("\"fade\"")
        assertNull(bare.elementOrNull)
        assertEquals(ScreenTransition.Preset.fade, bare.screenOrNull?.inlineOrNull?.preset)
        val obj = componentTransition("""{ "preset": "slide", "edge": "leading" }""")
        assertNull(obj.elementOrNull)
        assertEquals(ScreenTransition.Preset.slide, obj.screenOrNull?.inlineOrNull?.preset)
        val pointer = componentTransition("""{ "id": "slideTrans" }""")
        assertNull(pointer.elementOrNull)
        assertEquals("slideTrans", pointer.screenOrNull?.pointerId)
        // A null `key` is not an element context.
        assertNull(componentTransition("""{ "key": null, "preset": "fade" }""").elementOrNull)
    }

    @Test
    fun sharedPresetDecodes() {
        val i = inline("""{ "preset": "shared" }""")
        assertEquals(ScreenTransition.Preset.shared, i.preset)
        assertEquals(ScreenTransition.Mode.push, i.preset!!.defaultMode)
    }

    @Test
    fun presetDefaultModes() {
        assertEquals(ScreenTransition.Mode.modal, ScreenTransition.Preset.zoom.defaultMode)
        assertEquals(ScreenTransition.Mode.modal, ScreenTransition.Preset.popup.defaultMode)
        assertEquals(ScreenTransition.Mode.modal, ScreenTransition.Preset.sheet.defaultMode)
        assertEquals(ScreenTransition.Mode.push, ScreenTransition.Preset.slide.defaultMode)
    }

    @Test
    fun elementAnimationPointerResolvesViaResolver() {
        val dramatic = Animation.Inline(id = "dramatic", duration = 0.6, timing = Animation.Timing.CubicBezier(0.16, 1.0, 0.3, 1.0))
        val e = assertNotNull(
            componentTransition("""{ "key": "hero", "animation": { "id": "dramatic" } }""") { if (it == "dramatic") dramatic else null }.elementOrNull,
        )
        assertEquals(0.6, assertNotNull(e.animation?.inline { null }).duration)
    }

    @Test
    fun componentExposesElementTransition() {
        val component = DslDecoder().decodeComponent(
            """{ "id": "hero-card", "type": "view",
                 "content": { "properties": {}, "transition": { "key": "hero", "morph": "frameFade" }, "children": [] } }""",
        )
        val e = assertNotNull(component.contentAs<ViewContent>()?.elementTransition)
        assertEquals("hero", e.key)
        assertEquals(ScreenTransition.Morph.frameFade, e.morph)
        assertEquals("hero-card", component.id)
    }

    @Test
    fun presentationOverrideDecodes() {
        val p = assertNotNull(inline("""{ "preset": "sheet", "presentation": { "height": "70%" } }""").presentation)
        assertEquals(ScreenTransition.ModalDimension.Fraction(0.7), p.height)
        assertNull(p.align)
        assertNull(p.width)
    }

    @Test
    fun explicitPresentationBlock() {
        val p = assertNotNull(
            inline(
                """{ "preset": "scale", "mode": "modal",
                     "presentation": { "width": 320, "height": { "ratio": 1.2 }, "align": "bottom",
                       "margin": 16, "corner": { "radius": 20 }, "ignoresSafeArea": false, "avoidsKeyboard": false } }""",
            ).presentation,
        )
        assertEquals(ScreenTransition.ModalDimension.Points(320.0), p.width)
        assertEquals(ScreenTransition.ModalDimension.Ratio(1.2), p.height)
        assertEquals(ScreenTransition.ModalAlign.bottom, p.align)
        assertEquals(16.0, p.margin?.top)
        assertEquals(16.0, p.margin?.trailing)
        assertEquals(false, p.avoidsKeyboard)
        assertEquals(false, p.ignoresSafeArea)
        // Full `{ radius }` corner form decodes as a Corner (default circular curve).
        assertEquals(Corner(Corner.Radius.Fixed(20.0), Corner.Curve.circular), p.corner)
    }

    @Test
    fun presentationBareCornerRadiusIsContinuous() {
        val p = assertNotNull(inline("""{ "presentation": { "corner": 28 } }""").presentation)
        assertEquals(Corner(Corner.Radius.Fixed(28.0), Corner.Curve.continuous), p.corner)
        val pct = assertNotNull(inline("""{ "presentation": { "corner": "50%" } }""").presentation)
        assertEquals(Corner(Corner.Radius.Fraction(0.5), Corner.Curve.continuous), pct.corner)
    }

    @Test
    fun modalDimensionDecodesEveryForm() {
        fun dim(json: String) = DslDecoder().decode(json, ::decodeModalDimension)
        assertEquals(ScreenTransition.ModalDimension.Points(240.0), dim("240"))
        assertEquals(ScreenTransition.ModalDimension.Fraction(0.75), dim("\"75%\""))
        assertEquals(ScreenTransition.ModalDimension.Fill, dim("\"fill\""))
        assertEquals(ScreenTransition.ModalDimension.Ratio(0.5), dim("""{ "ratio": 0.5 }"""))
        kotlin.test.assertFailsWith<DslDecodingException> { dim("{}") }
    }

    @Test
    fun dimmingDismissOnTapDefaultsTrueAndIsOverridable() {
        assertEquals(true, inline("""{ "preset": "scale", "mode": "modal", "dimming": { "opacity": 0.6 } }""").dimming?.dismissOnTap)
        assertEquals(false, inline("""{ "preset": "scale", "mode": "modal", "dimming": { "opacity": 0.6, "dismissOnTap": false } }""").dimming?.dismissOnTap)
        assertEquals(0.4, inline("""{ "dimming": {} }""").dimming?.opacity)
    }

    @Test
    fun plainPushTransitionHasNoModalPresentation() = assertNull(inline("\"slide\"").presentation)

    @Test
    fun modalPresentationMerging() {
        val base = ScreenTransition.ModalPresentation(width = ScreenTransition.ModalDimension.Fill, align = ScreenTransition.ModalAlign.bottom)
        val merged = base.merging(ScreenTransition.ModalPresentation(height = ScreenTransition.ModalDimension.Fraction(0.7)))
        assertEquals(ScreenTransition.ModalDimension.Fill, merged.width)
        assertEquals(ScreenTransition.ModalAlign.bottom, merged.align)
        assertEquals(ScreenTransition.ModalDimension.Fraction(0.7), merged.height)
    }
}
