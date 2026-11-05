package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of iOS AnimationTests.swift (decoding only).
class AnimationTest {

    private fun anim(json: String, resolver: ((String) -> Animation.Inline?)? = null): Animation =
        DslDecoder(DecodeOptions(animationResolver = resolver)).decode(json, ::decodeAnimation)

    private fun <T> decode(json: String, f: (Cursor) -> T): T = DslDecoder().decode(json, f)

    @Test
    fun flatNamedCurve() {
        val inline = assertNotNull(anim("""{ "duration": 0.15, "curve": "easeOut" }""").inlineOrNull)
        assertEquals(0.15, inline.duration)
        assertEquals(0.0, inline.delay)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.easeOut), inline.timing)
        assertTrue(inline.options.isEmpty())
    }

    @Test
    fun flatLegacySpringWithoutParams() {
        val inline = assertNotNull(anim("""{ "duration": 0.4, "curve": "spring" }""").inlineOrNull)
        assertEquals(Animation.Timing.SpringTiming(Animation.Spring.DEFAULT), inline.timing)
    }

    @Test
    fun structuredSpring() {
        val inline = assertNotNull(anim("""{ "duration": 0.4, "spring": { "damping": 0.8, "velocity": 0.9 } }""").inlineOrNull)
        val spring = assertIs<Animation.Timing.SpringTiming>(inline.timing).spring
        assertEquals(0.8, spring.damping)
        assertEquals(0.9, spring.velocity)
        assertNull(spring.mass)
        assertNull(spring.stiffness)
    }

    @Test
    fun cubicBezier() {
        val inline = assertNotNull(anim("""{ "duration": 0.2, "cubicBezier": [0.25, 0.1, 0.25, 1.0] }""").inlineOrNull)
        assertEquals(Animation.Timing.CubicBezier(0.25, 0.1, 0.25, 1.0), inline.timing)
    }

    @Test
    fun options() {
        val inline = assertNotNull(anim("""{ "duration": 0.2, "options": ["beginFromCurrent", "allowUserInteraction"] }""").inlineOrNull)
        assertTrue(Animation.Option.beginFromCurrent in inline.options)
        assertTrue(Animation.Option.allowUserInteraction in inline.options)
    }

    @Test
    fun defaultsWhenFieldsAbsent() {
        val inline = assertNotNull(anim("""{ "duration": 0.3 }""").inlineOrNull)
        assertEquals(0.3, inline.duration)
        assertEquals(0.0, inline.delay)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.easeInOut), inline.timing)
    }

    @Test
    fun prefersSpringOverCubicBezierOverCurve() {
        val inline = assertNotNull(
            anim(
                """{ "duration": 0.3, "curve": "easeOut", "cubicBezier": [0.25, 0.1, 0.25, 1.0],
                    "spring": { "damping": 0.5, "velocity": 0.0 } }""",
            ).inlineOrNull,
        )
        assertIs<Animation.Timing.SpringTiming>(inline.timing)
    }

    @Test
    fun wrappedInline() {
        val inline = assertNotNull(
            anim("""{ "id": "fastPress", "type": "animation", "content": { "duration": 0.15, "curve": "easeOut" } }""").inlineOrNull,
        )
        assertEquals("fastPress", inline.id)
        assertEquals(0.15, inline.duration)
    }

    @Test
    fun preservesPointerWithoutResolver() {
        val a = anim("""{ "id": "fastPress" }""")
        assertTrue(a.isPointer)
        assertEquals("fastPress", a.pointerId)
        assertNull(a.inlineOrNull)
    }

    @Test
    fun resolvesPointerWithResolver() {
        val target = Animation.Inline(id = "fastPress", duration = 0.15, timing = Animation.Timing.Curve(Animation.NamedCurve.easeOut))
        val inline = assertNotNull(anim("""{ "id": "fastPress" }""") { if (it == "fastPress") target else null }.inlineOrNull)
        assertEquals(0.15, inline.duration)
        assertEquals("fastPress", inline.id)
    }

    @Test
    fun leavesPointerWhenResolverReturnsNull() {
        assertTrue(anim("""{ "id": "missing" }""") { null }.isPointer)
    }

    @Test
    fun animatedValueBareString() {
        val v = decode("\"hello\"", ::decodeAnimatedString)
        assertEquals("hello", v.value)
        assertNull(v.animation)
    }

    @Test
    fun animatedValueWrappedWithAnimation() {
        val v = decode("""{ "value": "{{ x }}", "animation": { "duration": 0.2, "curve": "easeOut" } }""", ::decodeAnimatedString)
        assertEquals("{{ x }}", v.value)
        assertEquals(0.2, assertNotNull(v.animation?.inlineOrNull).duration)
    }

    @Test
    fun animatedValueWrappedWithoutAnimation() {
        val v = decode("""{ "value": 42 }""") { c -> decodeAnimatedValue(c) { it.int() } }
        assertEquals(42, v.value)
        assertNull(v.animation)
    }

    @Test
    fun animatedValuePointerResolvesViaOptions() {
        val target = Animation.Inline(id = "fastPress", duration = 0.1, timing = Animation.Timing.Curve(Animation.NamedCurve.easeOut))
        val decoder = DslDecoder(DecodeOptions(animationResolver = { if (it == "fastPress") target else null }))
        val v = decoder.decode("""{ "value": "{{ x }}", "animation": { "id": "fastPress" } }""", ::decodeAnimatedString)
        assertEquals("{{ x }}", v.value)
        val inline = assertNotNull(v.animation?.inlineOrNull)
        assertEquals("fastPress", inline.id)
        assertEquals(0.1, inline.duration)
    }

    @Test
    fun viewPropertiesBareTransform() {
        val p = decode(
            """{ "transformTranslateY": "{{ scrollY * -0.3 }}", "alpha": "{{ scrollY > 100 ? 0 : 1 }}",
                "backgroundColor": "{{ item.color }}" }""",
            ::decodeViewProperties,
        )
        assertEquals("{{ scrollY * -0.3 }}", p.transformTranslateY?.value)
        assertNull(p.transformTranslateY?.animation)
        assertEquals("{{ scrollY > 100 ? 0 : 1 }}", p.alpha?.value)
        assertEquals("{{ item.color }}", p.backgroundColor?.value)
    }

    @Test
    fun viewPropertiesWrappedTransformWithAnimation() {
        val p = decode(
            """{
              "transformTranslateX": { "value": "{{ x }}", "animation": { "duration": 0.2, "curve": "easeOut" } },
              "alpha": { "value": "{{ visible ? 1 : 0 }}", "animation": { "duration": 0.5, "spring": { "damping": 0.7, "velocity": 0 } } }
            }""",
            ::decodeViewProperties,
        )
        val tx = assertNotNull(p.transformTranslateX)
        assertEquals("{{ x }}", tx.value)
        assertEquals(0.2, assertNotNull(tx.animation?.inlineOrNull).duration)
        val alpha = assertNotNull(p.alpha)
        assertEquals("{{ visible ? 1 : 0 }}", alpha.value)
        val ai = assertNotNull(alpha.animation?.inlineOrNull)
        assertEquals(0.5, ai.duration)
        assertIs<Animation.Timing.SpringTiming>(ai.timing)
    }

    @Test
    fun labelPropertiesBareBackgroundColor() {
        val p = decode("""{ "text": "Hi", "backgroundColor": "{{ item.bg }}" }""", ::decodeLabelProperties)
        assertEquals("{{ item.bg }}", p.backgroundColor?.value)
        assertNull(p.backgroundColor?.animation)
    }

    @Test
    fun labelPropertiesWrappedBackgroundColor() {
        val p = decode(
            """{ "text": "Hi", "backgroundColor": { "value": "{{ item.bg }}", "animation": { "duration": 0.4 } } }""",
            ::decodeLabelProperties,
        )
        val bg = assertNotNull(p.backgroundColor)
        assertEquals("{{ item.bg }}", bg.value)
        assertEquals(0.4, assertNotNull(bg.animation?.inlineOrNull).duration)
    }

    @Test
    fun iconPropertiesBareTintAndBackground() {
        val p = decode(
            """{ "type": "symbol", "name": "checkmark", "tintColor": "{{ item.iconColor }}", "backgroundColor": "{{ item.bg }}" }""",
            ::decodeIconProperties,
        )
        assertEquals("{{ item.iconColor }}", p.tintColor?.value)
        assertNull(p.tintColor?.animation)
        assertEquals("{{ item.bg }}", p.backgroundColor?.value)
    }

    @Test
    fun iconPropertiesWrappedTint() {
        val p = decode(
            """{ "type": "symbol", "name": "checkmark",
                "tintColor": { "value": "{{ item.iconColor }}", "animation": { "duration": 0.3, "curve": "easeInOut" } } }""",
            ::decodeIconProperties,
        )
        val tint = assertNotNull(p.tintColor)
        assertEquals("{{ item.iconColor }}", tint.value)
        assertEquals(0.3, assertNotNull(tint.animation?.inlineOrNull).duration)
    }

    @Test
    fun shapePropertiesBareProgress() {
        val p = decode(
            """{ "kind": "arc", "progress": "{{ progress }}", "animationDuration": 0.6, "animationCurve": "easeInOut" }""",
            ::decodeShapeProperties,
        )
        assertEquals("{{ progress }}", p.progress?.value)
        assertNull(p.progress?.animation)
        assertEquals(0.6, p.animationDuration)
        assertEquals("easeInOut", p.animationCurve)
    }

    @Test
    fun shapePropertiesWrappedProgress() {
        val p = decode(
            """{ "kind": "arc", "progress": { "value": "{{ progress }}", "animation": { "duration": 0.8, "curve": "linear" } } }""",
            ::decodeShapeProperties,
        )
        val progress = assertNotNull(p.progress)
        assertEquals("{{ progress }}", progress.value)
        val inline = assertNotNull(progress.animation?.inlineOrNull)
        assertEquals(0.8, inline.duration)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.linear), inline.timing)
    }

    @Test
    fun legacyFallbackConstants() {
        val transform = Animation.legacyTransformVariableUpdate
        assertEquals(0.3, transform.duration)
        val spring = assertIs<Animation.Timing.SpringTiming>(transform.timing).spring
        assertEquals(0.75, spring.damping)
        assertEquals(0.3, spring.velocity)
        val alpha = Animation.legacyAlphaVariableUpdate
        assertEquals(0.25, alpha.duration)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.easeOut), alpha.timing)
    }
}
