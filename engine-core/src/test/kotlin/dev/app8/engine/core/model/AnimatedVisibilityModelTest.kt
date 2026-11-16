package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Decoding of the wrapped `isHidden` (animated visibility) and `view.properties.transformRotation`. */
class AnimatedVisibilityModelTest {
    private val decoder = DslDecoder(DecodeOptions(animationResolver = { id -> if (id == "open") Animation.Inline(id = "open", duration = 0.3) else null }))

    private fun entity(json: String) = assertIs<ComponentEntity<*>>(decoder.decodeComponent(json))

    @Test
    fun `wrapped isHidden on a view keeps the value and the animation`() {
        val e = entity(
            """{"type":"view","id":"body","content":{"properties":{"isHidden":{
                "value":"{{ !includes(openRows, 'shipping') }}",
                "animation":{"duration":0.3,"cubicBezier":[0.33,1,0.68,1]}}}}}""",
        )
        assertEquals("{{ !includes(openRows, 'shipping') }}", (e.content.properties as ViewProperties).isHidden)
        assertEquals("{{ !includes(openRows, 'shipping') }}", e.isHidden)
        val a = assertIs<Animation.Inline>(e.isHiddenAnimation)
        assertEquals(0.3, a.duration)
        assertEquals(Animation.Timing.CubicBezier(0.33, 1.0, 0.68, 1.0), a.timing)
    }

    @Test
    fun `wrapped isHidden works on every component type and takes a pointer`() {
        val button = entity("""{"type":"button","id":"b","content":{"properties":{"text":"Go","isHidden":{"value":true,"animation":{"id":"open"}}}}}""")
        assertEquals("true", button.isHidden)
        assertEquals(0.3, assertIs<Animation.Inline>(button.isHiddenAnimation).duration)
        val label = entity("""{"type":"label","id":"l","content":{"properties":{"text":"A","isHidden":{"value":"{{x}}","animation":{"duration":0.2}}}}}""")
        assertEquals("{{x}}", (label.content.properties as LabelProperties).isHidden)
        assertIs<Animation.Inline>(label.isHiddenAnimation)
    }

    @Test
    fun `bare isHidden stays instant`() {
        val e = entity("""{"type":"view","id":"v","content":{"properties":{"isHidden":"{{x}}"}}}""")
        assertEquals("{{x}}", (e.content.properties as ViewProperties).isHidden)
        assertNull(e.isHiddenAnimation)
        val flag = entity("""{"type":"image","id":"i","content":{"properties":{"type":"none","isHidden":false}}}""")
        assertEquals("false", flag.isHidden)
        assertNull(flag.isHiddenAnimation)
    }

    @Test
    fun `a state may carry a wrapped isHidden`() {
        val e = entity(
            """{"type":"view","id":"v","content":{"states":{"closed":{"properties":{"isHidden":{"value":true,"animation":{"duration":0.3}}}}}}}""",
        )
        @Suppress("UNCHECKED_CAST")
        val state = (e.content as ViewContent).states!!.getValue("closed")
        assertEquals("true", state.isHidden)
        assertEquals("true", state.properties!!.isHidden)
        assertIs<Animation.Inline>(state.isHiddenAnimation)
    }

    @Test
    fun `transformRotation decodes bare and wrapped`() {
        val bare = entity("""{"type":"view","id":"v","content":{"properties":{"transformRotation":45}}}""")
        assertEquals(AnimatedValue("45"), (bare.content.properties as ViewProperties).transformRotation)
        val wrapped = entity(
            """{"type":"view","id":"v","content":{"properties":{"transformRotation":{
                "value":"{{ open ? 180 : 0 }}","animation":{"duration":0.3,"cubicBezier":[0.33,1,0.68,1]}}}}}""",
        )
        val r = (wrapped.content.properties as ViewProperties).transformRotation!!
        assertEquals("{{ open ? 180 : 0 }}", r.value)
        assertEquals(0.3, assertIs<Animation.Inline>(r.animation).duration)
    }
}
