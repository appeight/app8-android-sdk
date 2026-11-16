package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPermissionActionTest {

    private fun action(json: String): Action = DslDecoder().decode(json, ::decodeAction)

    @Test
    fun decodesOnGrantedListAndSingleOnDenied() {
        val a = action(
            """{ "type": "requestNotificationPermission",
                 "onGranted": [ { "type": "toggleArrayValue", "variableName": "remindedIds", "value": "{{item.id}}" }, { "type": "haptic", "hapticStyle": "success" } ],
                 "onDenied": { "type": "haptic", "hapticStyle": "error" } }""",
        )
        assertEquals(Action.Type.requestNotificationPermission, a.type)
        assertEquals(listOf(Action.Type.toggleArrayValue, Action.Type.haptic), a.onGranted?.map { it.type })
        assertEquals(listOf(Action.Type.haptic), a.onDenied?.map { it.type })
    }

    @Test
    fun callbacksAreOptional() {
        val a = action("""{ "type": "requestNotificationPermission" }""")
        assertNull(a.onGranted)
        assertNull(a.onDenied)
    }
}
