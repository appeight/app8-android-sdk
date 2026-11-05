package dev.app8.engine.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Port of CollectionExamplesTests.swift. Skipped: DatasourceResolver tests (variables runtime).
// VariableDefinition tests check the raw JSON kept until the vars package lands.
class CollectionExamplesTest {

    private fun props(json: String) = DslDecoder().decode(json, ::decodeCollectionProperties)

    private fun section(json: String): CollectionProperties.SectionDefinition =
        props("""{ "sectionDefinitions": [$json] }""").sectionDefinitions!!.single()

    @Test
    fun sectionDefinitionMinimalDecode() {
        val def = section("""{"key":"settings","data":"{{settingsItems}}"}""")
        assertEquals("settings", def.key)
        assertEquals("{{settingsItems}}", def.data)
        assertNull(def.templateName)
        assertNull(def.templateKey)
    }

    @Test
    fun sectionDefinitionWithTemplate() {
        val def = section("""{"key":"header","data":"{{profileData}}","templateName":"profileHeader"}""")
        assertEquals("header", def.key)
        assertEquals("profileHeader", def.templateName)
        assertNull(def.templateKey)
    }

    @Test
    fun sectionDefinitionWithTemplateKey() {
        val def = section("""{"key":"feed","data":"{{items}}","templateKey":"item.type"}""")
        assertEquals("item.type", def.templateKey)
        assertNull(def.templateName)
    }

    @Test
    fun collectionPropertiesWithSectionsArray() {
        val p = props(
            """
            {
                "layout": {"type": "vertical"},
                "sectionDefinitions": [
                    {"key": "header",   "data": "{{profileData}}",   "templateName": "profileHeader"},
                    {"key": "settings", "data": "{{settingsItems}}", "templateName": "settingsRow"},
                    {"key": "support",  "data": "{{supportItems}}",  "templateName": "settingsRow"}
                ]
            }
            """,
        )
        assertEquals(listOf("header", "settings", "support"), p.sectionDefinitions?.map { it.key })
        assertEquals("profileHeader", p.sectionDefinitions!![0].templateName)
        assertEquals("settingsRow", p.sectionDefinitions!![1].templateName)
        assertNull(p.data)
        assertNull(p.groupBy)
    }

    @Test
    fun collectionPropertiesWithGroupByAndTemplateKey() {
        val p = props(
            """{ "data": "{{listings}}", "groupBy": "{{item.sectionTitle}}", "templateKey": "item.isFeatured", "layout": {"type": "vertical"} }""",
        )
        assertEquals("{{listings}}", p.data)
        assertEquals("{{item.sectionTitle}}", p.groupBy)
        assertEquals("item.isFeatured", p.templateKey)
        assertNull(p.sectionDefinitions)
    }

    @Test
    fun collectionPropertiesWithSingleDataBinding() {
        val p = props(
            """{ "data": "{{listings}}", "templateKey": "item.isFeatured", "layout": {"type": "vertical", "itemSpacing": 16, "estimatedItemHeight": 260} }""",
        )
        assertEquals("{{listings}}", p.data)
        assertEquals("item.isFeatured", p.templateKey)
        assertEquals(16.0, p.layout?.itemSpacing)
        assertEquals(260.0, p.layout?.estimatedItemHeight)
        assertNull(p.sectionDefinitions)
        assertNull(p.groupBy)
    }

    @Test
    fun swipeActionsTrailingDecodes() {
        val p = props(
            """
            {
                "layout": {"type": "vertical"}, "data": "{{items}}",
                "swipeActions": { "trailing": [ {
                    "title": "Delete", "systemImage": "trash", "backgroundColor": "#FF3B30", "style": "destructive",
                    "action": { "type": "toggleArrayValue", "variableName": "items", "value": "{{item.id}}" }
                } ] }
            }
            """,
        )
        assertNotNull(p.swipeActions)
        assertEquals(1, p.swipeActions?.trailing?.size)
        assertNull(p.swipeActions?.leading)
        val swipe = p.swipeActions!!.trailing!![0]
        assertEquals("Delete", swipe.title)
        assertEquals("trash", swipe.systemImage)
        assertEquals("#FF3B30", swipe.backgroundColor)
        assertEquals(CollectionProperties.SwipeAction.Style.destructive, swipe.style)
        assertEquals(Action.Type.toggleArrayValue, swipe.action?.type)
        assertEquals("items", swipe.action?.variableName)
    }

    @Test
    fun swipeActionsLeadingAndTrailing() {
        val p = props(
            """
            {
                "layout": {"type": "vertical"}, "data": "{{items}}",
                "swipeActions": {
                    "leading": [ { "title": "Archive", "systemImage": "archivebox", "backgroundColor": "#FF9500",
                        "action": { "type": "updateVariable", "variableName": "archivedId", "value": "{{item.id}}" } } ],
                    "trailing": [ { "title": "Delete", "systemImage": "trash", "style": "destructive",
                        "action": { "type": "toggleArrayValue", "variableName": "items", "value": "{{item.id}}" } } ],
                    "allowFullSwipe": false
                }
            }
            """,
        )
        assertEquals(1, p.swipeActions?.leading?.size)
        assertEquals(1, p.swipeActions?.trailing?.size)
        assertEquals(false, p.swipeActions?.allowFullSwipe)
        assertEquals("Archive", p.swipeActions!!.leading!![0].title)
        assertNull(p.swipeActions!!.leading!![0].style)
        assertEquals(CollectionProperties.SwipeAction.Style.destructive, p.swipeActions!!.trailing!![0].style)
    }

    @Test
    fun swipeActionsAbsentWhenNotDefined() {
        assertNull(props("""{ "layout": {"type": "vertical"}, "data": "{{items}}" }""").swipeActions)
    }

    @Test
    fun swipeActionsMultipleTrailingActions() {
        val p = props(
            """
            {
                "layout": {"type": "vertical"}, "data": "{{items}}",
                "swipeActions": { "trailing": [
                    { "title": "Delete", "style": "destructive", "action": { "type": "toggleArrayValue", "variableName": "items", "value": "{{item.id}}" } },
                    { "title": "Flag", "backgroundColor": "#FFCC00", "action": { "type": "updateVariable", "variableName": "flaggedId", "value": "{{item.id}}" } }
                ] }
            }
            """,
        )
        assertEquals(listOf("Delete", "Flag"), p.swipeActions?.trailing?.map { it.title })
    }

    // Variable definitions are kept as raw JSON (TODO: vars.VariableDefinition).

    private fun variables(json: String) = DslDecoder().decode(json, ::decodeVariables)

    @Test
    fun variableDefinitionWithInitialValueArray() {
        val v = variables(
            """{ "settingsItems": { "type": "array", "initialValue": [
                {"icon": "bell.fill", "title": "Notifications"}, {"icon": "lock.fill", "title": "Privacy"},
                {"icon": "eye.fill", "title": "Appearance"}, {"icon": "iphone", "title": "Device"}
            ] } }""",
        )["settingsItems"] as JsonObject
        assertEquals(JsonPrimitive("array"), v["type"])
        val items = v["initialValue"] as JsonArray
        assertEquals(4, items.size)
        assertEquals(JsonPrimitive("bell.fill"), (items[0] as JsonObject)["icon"])
        assertEquals(JsonPrimitive("Device"), (items[3] as JsonObject)["title"])
    }

    @Test
    fun variableDefinitionWithSourceHasNoInitialValue() {
        val v = variables("""{ "profileData": {"type":"array","source":"datasources/profile"} }""")["profileData"] as JsonObject
        assertNull(v["initialValue"])
        assertEquals(JsonPrimitive("datasources/profile"), v["source"])
    }
}
