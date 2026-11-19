package dev.app8.engine.render

import dev.app8.engine.core.model.ComponentState
import dev.app8.engine.core.model.ViewProperties
import dev.app8.engine.core.model.style.ViewStyle
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Press feedback: ripple unless the DSL defines its own (non-opacity) touch state. */
class PressFeedbackTest {
    private val screen = """{"id":"s","type":"screen","content":{"children":[
      {"id":"plain","type":"view","content":{"actions":{"tap":[{"type":"haptic"}]}}},
      {"id":"dim","type":"view","content":{"actions":{"tap":[{"type":"haptic"}]},
        "triggers":{"touchDown":"pressed","touchUp":"normal"},
        "states":{"pressed":{"style":{"alpha":0.6}},"normal":{"style":{"alpha":1}}}}},
      {"id":"brand","type":"view","content":{"actions":{"tap":[{"type":"haptic"}]},
        "triggers":{"touchDown":"pressed","touchUp":"normal"},
        "states":{"pressed":{"properties":{"backgroundColor":"#FF0000"}},"normal":{}}}}
    ]}}"""

    private fun node(id: String): RenderNode {
        val s = session(runtime(mapOf("s" to screen)))
        return s.root.find(id)!!
    }

    @Test
    fun aPlainTappableViewRipples() = assertTrue(PressFeedback.wanted(node("plain").content))

    @Test
    fun anOpacityOnlyTouchStateKeepsTheRipple() = assertTrue(PressFeedback.wanted(node("dim").content))

    @Test
    fun aBrandTouchStateIsTheOnlyFeedback() = assertFalse(PressFeedback.wanted(node("brand").content))

    @Test
    fun opacityOnlyRules() {
        assertTrue(PressFeedback.isOpacityOnly(ComponentState<Any, Any>(style = ViewStyle(alpha = 0.5))))
        assertTrue(PressFeedback.isOpacityOnly(ComponentState<Any, Any>(properties = ViewProperties(alpha = null))))
        assertFalse(PressFeedback.isOpacityOnly(ComponentState<Any, Any>(properties = ViewProperties(cornerRadius = 4.0))))
        assertFalse(PressFeedback.isOpacityOnly(ComponentState<Any, Any>(childStates = mapOf("a" to "b"))))
        assertFalse(PressFeedback.isOpacityOnly(ComponentState<Any, Any>()), "an empty state is no feedback at all")
    }
}
