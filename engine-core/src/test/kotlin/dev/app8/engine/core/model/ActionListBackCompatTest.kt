package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of iOS ActionListBackCompatTests.swift: `{ trigger: Action }` and `{ trigger: [Action] }` both decode. */
class ActionListBackCompatTest {

    private fun decode(json: String): Map<String, List<Action>> =
        DslDecoder().decode(json) { c -> c.req("actions") { m -> m.map(::decodeActionList) } }

    @Test
    fun singleActionObjectDecodesAsOneElementList() {
        val tap = decode("""{ "actions": { "tap": { "type": "navigation", "nextScreen": "next" } } }""")["tap"]!!
        assertEquals(1, tap.size)
        assertEquals(Action.Type.navigation, tap[0].type)
        assertEquals("next", tap[0].nextScreen)
    }

    @Test
    fun actionArrayDecodesInJsonOrder() {
        val tap = decode(
            """{ "actions": { "tap": [ { "type": "emit", "name": "checkout.started" }, { "type": "navigation", "nextScreen": "confirm" } ] } }""",
        )["tap"]!!
        assertEquals(2, tap.size)
        assertEquals(Action.Type.emit, tap[0].type)
        assertEquals("checkout.started", tap[0].name)
        assertEquals(Action.Type.navigation, tap[1].type)
        assertEquals("confirm", tap[1].nextScreen)
    }

    @Test
    fun emptyActionArrayDecodes() {
        assertTrue(decode("""{ "actions": { "tap": [] } }""")["tap"]!!.isEmpty())
    }

    @Test
    fun multipleTriggersCoexist() {
        val d = decode(
            """{ "actions": { "tap": { "type": "haptic", "hapticStyle": "light" }, "longPress": [{ "type": "navigation", "nextScreen": "menu" }] } }""",
        )
        assertEquals(Action.Type.haptic, d["tap"]!!.first().type)
        assertEquals(Action.Type.navigation, d["longPress"]!!.first().type)
    }
}
