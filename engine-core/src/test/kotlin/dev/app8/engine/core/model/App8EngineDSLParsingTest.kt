package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.ViewStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Port of App8EngineDSLParsingTests.swift.
class App8EngineDSLParsingTest {

    @Test
    fun parsesAllValidFixtures() {
        val json = javaClass.getResource("/fixtures/01-app.json")!!.readText()
        val app = DslDecoder().decodeApp(json)
        assertFalse(app.title.isNullOrEmpty(), "Expected non-empty App title for 01-app.json")
    }

    @Test
    fun parsesComponentWithTriggers() {
        val component = DslDecoder().decodeComponent(
            """
            {
                "type": "button", "id": "test-button",
                "content": {
                    "properties": { "text": "Press Me" },
                    "style": {},
                    "triggers": { "touchDown": "pressed", "touchUp": "normal" },
                    "defaultStateName": "normal",
                    "states": {
                        "normal": { "properties": { "text": "Normal" }, "style": { "alpha": 1.0 } },
                        "pressed": { "properties": { "text": "Pressed" }, "style": { "alpha": 0.5 } }
                    }
                }
            }
            """,
        )
        assertEquals("test-button", component.id)
        assertTrue(component.type.isOneOf(ComponentType.Key.button))
        val content = assertIs<ComponentEntity<*>>(component).content as ButtonContent
        assertEquals("normal", content.defaultStateName)
        assertEquals(2, content.states?.size)
        assertEquals("Pressed", content.states!!["pressed"]?.properties?.text)
        assertEquals(0.5, content.states!!["pressed"]?.style?.alpha)
        assertEquals(2, content.triggers?.size)
        assertEquals("pressed", content.triggers?.get(Trigger.touchDown))
        assertEquals("normal", content.triggers?.get(Trigger.touchUp))
    }

    @Test
    fun parsesComponentWithStatesAndAnimation() {
        val component = DslDecoder().decodeComponent(
            """
            {
                "type": "view", "id": "test-view",
                "content": {
                    "properties": {},
                    "style": { "alpha": 1.0 },
                    "defaultStateName": "normal",
                    "states": {
                        "normal": { "properties": {}, "style": { "alpha": 1.0 }, "animation": { "duration": 0.3, "curve": "easeOut" } },
                        "highlighted": {
                            "properties": {}, "style": { "alpha": 0.5 },
                            "animation": { "duration": 0.1, "curve": "easeIn" },
                            "childStates": { "child-1": "active", "child-2": "active" }
                        }
                    }
                }
            }
            """,
        )
        @Suppress("UNCHECKED_CAST")
        val content = assertIs<ComponentEntity<*>>(component).content as ComponentContent<ViewProperties, ViewStyle>
        assertEquals("normal", content.defaultStateName)
        assertEquals(2, content.states?.size)

        val normal = content.states!!["normal"]!!.animation!!.inlineOrNull!!
        assertEquals(0.3, normal.duration)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.easeOut), normal.timing)

        val highlighted = content.states!!["highlighted"]!!
        val hi = highlighted.animation!!.inlineOrNull!!
        assertEquals(0.1, hi.duration)
        assertEquals(Animation.Timing.Curve(Animation.NamedCurve.easeIn), hi.timing)
        assertEquals("active", highlighted.childStates?.get("child-1"))
        assertEquals("active", highlighted.childStates?.get("child-2"))
    }
}
