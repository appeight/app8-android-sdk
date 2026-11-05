package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Port of iOS ActionModelTests.swift (decoding only). */
class ActionModelTest {

    private fun action(json: String): Action = DslDecoder().decode(json, ::decodeAction)

    @Test
    fun showAlertDecodesWithAllProperties() {
        val a = action(
            """
            { "type": "showAlert", "alertTitle": "Delete?", "alertMessage": "This cannot be undone.",
              "alertActions": [ { "title": "Cancel", "style": "cancel" }, { "title": "Delete", "style": "destructive" } ] }
            """,
        )
        assertEquals(Action.Type.showAlert, a.type)
        assertEquals("Delete?", a.alertTitle)
        assertEquals("This cannot be undone.", a.alertMessage)
        assertEquals(2, a.alertActions?.size)
        assertEquals("Cancel", a.alertActions!![0].title)
        assertEquals(Action.AlertActionStyle.cancel, a.alertActions!![0].style)
        assertEquals("Delete", a.alertActions!![1].title)
        assertEquals(Action.AlertActionStyle.destructive, a.alertActions!![1].style)
    }

    @Test
    fun showAlertDecodesMinimal() {
        val a = action("""{ "type": "showAlert", "alertTitle": "Info" }""")
        assertEquals(Action.Type.showAlert, a.type)
        assertEquals("Info", a.alertTitle)
        assertNull(a.alertMessage)
        assertNull(a.alertActions)
    }

    @Test
    fun showAlertActionWithFollowUp() {
        val a = action(
            """
            { "type": "showAlert", "alertTitle": "Confirm", "alertActions": [ { "title": "OK", "style": "default",
              "action": { "type": "updateVariable", "variableName": "confirmed", "value": true } } ] }
            """,
        )
        assertEquals(Action.Type.updateVariable, a.alertActions!![0].action?.type)
        assertEquals("confirmed", a.alertActions!![0].action?.variableName)
        assertEquals(JsonPrimitive(true), a.alertActions!![0].action?.value)
    }

    @Test
    fun hapticDecodesAllStyles() {
        for (style in listOf("light", "medium", "heavy", "success", "warning", "error", "selection")) {
            val a = action("""{ "type": "haptic", "hapticStyle": "$style" }""")
            assertEquals(Action.Type.haptic, a.type)
            assertEquals(style, a.hapticStyle?.name)
        }
    }

    @Test
    fun hapticDecodesWithoutStyle() {
        val a = action("""{ "type": "haptic" }""")
        assertEquals(Action.Type.haptic, a.type)
        assertNull(a.hapticStyle)
    }

    @Test
    fun openURLDecodesWebPhoneAndExpressionURLs() {
        val web = action("""{ "type": "openURL", "url": "https://apple.com" }""")
        assertEquals(Action.Type.openURL, web.type)
        assertEquals("https://apple.com", web.url)
        assertEquals("tel:+15551234567", action("""{ "type": "openURL", "url": "tel:+15551234567" }""").url)
        assertEquals("{{item.link}}", action("""{ "type": "openURL", "url": "{{item.link}}" }""").url)
    }

    @Test
    fun openURLPresentations() {
        assertNull(action("""{ "type": "openURL", "url": "https://apple.com" }""").urlPresentation)
        for (p in Action.URLPresentation.entries) {
            val a = action("""{ "type": "openURL", "url": "https://apple.com", "urlPresentation": "${p.name}" }""")
            assertEquals(p, a.urlPresentation)
        }
    }

    @Test
    fun actionTriggersResolveByRawName() {
        val triggers = mapOf("longPress" to 1, "onTextChange" to 2, "onScrollThreshold" to 3, "bogusTrigger" to 4)
            .keyedByEnum<ActionTrigger, Int>()!!
        assertEquals(1, triggers[ActionTrigger.longPress])
        assertEquals(2, triggers[ActionTrigger.onTextChange])
        assertEquals(3, triggers[ActionTrigger.onScrollThreshold])
        assertEquals(3, triggers.size, "unknown trigger is dropped")
    }

    @Test
    fun datePickerComponentDecodesInActionContext() {
        val c = DslDecoder().decodeComponent(
            """
            { "type": "datePicker", "id": "test-dp", "content": {
                "properties": { "bindVariable": "date", "datePickerMode": "date", "displayStyle": "compact" },
                "actions": { "tap": { "type": "haptic", "hapticStyle": "selection" } },
                "layout": { "height": 40 } } }
            """,
        )
        val content = assertNotNull(c.contentAs<DatePickerContent>())
        assertEquals("date", content.properties.bindVariable)
        assertEquals(Action.Type.haptic, content.actions?.get(ActionTrigger.tap)?.first()?.type)
    }

    private fun detents(json: String): List<Action.SheetDetent> =
        assertNotNull(action("""{ "type": "navigation", "nextScreen": "x", "presentation": "pageSheet", "detents": $json }""").detents)

    @Test
    fun sheetDetents() {
        assertEquals(listOf(Action.SheetDetent.Medium, Action.SheetDetent.Large), detents("""["medium", "large"]"""))
        assertEquals(listOf(Action.SheetDetent.Fixed(320.0)), detents("[320]"))
        assertEquals(listOf(Action.SheetDetent.Fraction(0.6)), detents("""["60%"]"""))
        assertEquals(
            listOf(Action.SheetDetent.Medium, Action.SheetDetent.Fixed(480.0), Action.SheetDetent.Fraction(0.9)),
            detents("""["medium", 480, "90%"]"""),
        )
    }

    @Test
    fun pageSheetPresentationAndGrabber() {
        val on = action("""{ "type": "navigation", "nextScreen": "x", "presentation": "pageSheet" }""")
        assertEquals(Action.PresentationStyle.pageSheet, on.presentation)
        assertNull(on.grabber)
        val off = action("""{ "type": "navigation", "nextScreen": "x", "presentation": "pageSheet", "grabber": false }""")
        assertEquals(false, off.grabber)
    }
}
