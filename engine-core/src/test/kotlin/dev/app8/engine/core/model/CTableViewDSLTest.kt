package dev.app8.engine.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of CTableViewDSLTests.swift (all tests are decoding-only).

private fun decodeTableView(json: String): ComponentEntity<*> {
    val entity = assertIs<ComponentEntity<*>>(DslDecoder().decodeComponent(json))
    assertIs<TableViewContent>(entity.content)
    return entity
}

private val ComponentEntity<*>.table: TableViewContent get() = content as TableViewContent

private fun tableViewJSON(properties: String = "{}", sections: String = "[]", extra: String = ""): String = """
    {
        "type": "tableView",
        "id": "test-table",
        "content": {
            "properties": $properties,
            "style": {},
            "layout": {},
            "sections": $sections
            ${if (extra.isEmpty()) "" else ", $extra"}
        }
    }
"""

private fun rows(rowsJson: String) = """[{ "id": "sec", "rows": $rowsJson }]"""

class CTableViewDSLTest {

    @Test
    fun tableViewCTypeDecodesAsKey() {
        assertTrue(decodeTableView(tableViewJSON()).type.isOneOf(ComponentType.Key.tableView))
    }

    @Test
    fun tableViewDecodesAsConcreteEntity() {
        val component = DslDecoder().decodeComponent(tableViewJSON())
        assertIs<ComponentEntity<*>>(component)
        assertNull(component.pointer)
    }

    @Test
    fun tableViewPointerDecodesWithoutContent() {
        val component = DslDecoder().decodeComponent("""{ "type": "tableView", "id": "ptr-table" }""")
        assertNotNull(component.pointer)
        assertEquals("ptr-table", component.id)
    }

    @Test
    fun defaultPropertiesAreNilWhenAbsent() {
        assertEquals(TableViewProperties(), decodeTableView(tableViewJSON()).table.properties)
    }

    @Test
    fun tableStyles() {
        for ((raw, expected) in listOf(
            "insetGrouped" to TableViewProperties.TableStyle.insetGrouped,
            "grouped" to TableViewProperties.TableStyle.grouped,
            "plain" to TableViewProperties.TableStyle.plain,
        )) {
            assertEquals(expected, decodeTableView(tableViewJSON(properties = """{ "tableStyle": "$raw" }""")).table.properties.tableStyle)
        }
    }

    @Test
    fun tableStyleUnknownFallsBackToInsetGrouped() {
        assertEquals(
            TableViewProperties.TableStyle.insetGrouped,
            decodeTableView(tableViewJSON(properties = """{ "tableStyle": "carousel" }""")).table.properties.tableStyle,
        )
    }

    @Test
    fun showsIndicatorDecodes() {
        assertEquals(false, decodeTableView(tableViewJSON(properties = """{ "showsIndicator": false }""")).table.properties.showsIndicator)
    }

    @Test
    fun separatorInsetDecodes() {
        assertEquals(64.0, decodeTableView(tableViewJSON(properties = """{ "separatorInset": 64 }""")).table.properties.separatorInset)
    }

    @Test
    fun allPropertiesDecodeTogether() {
        val p = decodeTableView(tableViewJSON(properties = """{ "tableStyle": "plain", "showsIndicator": true, "separatorInset": 16 }""")).table.properties
        assertEquals(TableViewProperties(TableViewProperties.TableStyle.plain, true, 16.0), p)
    }

    @Test
    fun emptySectionsArray() {
        assertTrue(decodeTableView(tableViewJSON(sections = "[]")).table.sections.isEmpty())
    }

    @Test
    fun singleSectionWithHeader() {
        val s = decodeTableView(tableViewJSON(sections = """[{ "id": "sec1", "header": "Account", "rows": [] }]""")).table.sections
        assertEquals(1, s.size)
        assertEquals("sec1", s[0].id)
        assertEquals("Account", s[0].header)
        assertNull(s[0].footer)
    }

    @Test
    fun sectionWithFooter() {
        val s = decodeTableView(tableViewJSON(sections = """[{ "id": "sec1", "footer": "Version 1.0", "rows": [] }]""")).table.sections
        assertNull(s[0].header)
        assertEquals("Version 1.0", s[0].footer)
    }

    @Test
    fun multipleSections() {
        val s = decodeTableView(
            tableViewJSON(
                sections = """
                [
                    { "id": "s1", "header": "First",  "rows": [] },
                    { "id": "s2", "header": "Second", "rows": [] },
                    { "id": "s3", "rows": [] }
                ]
                """,
            ),
        ).table.sections
        assertEquals(3, s.size)
        assertEquals("Second", s[1].header)
        assertNull(s[2].header)
    }

    @Test
    fun malformedSectionSkippedBySafeArray() {
        val s = decodeTableView(
            tableViewJSON(sections = """[{ "id": "good", "rows": [] }, { "BROKEN": true }, { "id": "also-good", "rows": [] }]"""),
        ).table.sections
        assertEquals(listOf("good", "also-good"), s.map { it.id })
    }

    @Test
    fun rowDecodesWithHeight() {
        val row = decodeTableView(tableViewJSON(sections = rows("""[{ "id": "row1", "height": 72, "children": [] }]"""))).table.sections[0].rows[0]
        assertEquals("row1", row.id)
        assertEquals(72.0, row.height)
    }

    @Test
    fun rowOptionalFieldsNilWhenAbsent() {
        val row = decodeTableView(tableViewJSON(sections = rows("""[{ "id": "r", "children": [] }]"""))).table.sections[0].rows[0]
        assertNull(row.height)
        assertNull(row.clearBackground)
        assertNull(row.actions)
    }

    @Test
    fun rowClearBackground() {
        val t = decodeTableView(tableViewJSON(sections = rows("""[{ "id": "footerRow", "height": 110, "clearBackground": true, "children": [] }]""")))
        assertEquals(true, t.table.sections[0].rows[0].clearBackground)
        val f = decodeTableView(tableViewJSON(sections = rows("""[{ "id": "r", "clearBackground": false, "children": [] }]""")))
        assertEquals(false, f.table.sections[0].rows[0].clearBackground)
    }

    @Test
    fun rowTapActionDecodes() {
        val row = decodeTableView(
            tableViewJSON(
                sections = rows(
                    """
                    [{
                        "id": "r", "children": [],
                        "actions": { "tap": { "type": "navigation", "nextScreen": "detail-screen", "presentation": "push" } }
                    }]
                    """,
                ),
            ),
        ).table.sections[0].rows[0]
        assertNotNull(row.actions?.get(ActionTrigger.tap))
    }

    @Test
    fun malformedRowSkippedBySafeArray() {
        val r = decodeTableView(
            tableViewJSON(sections = rows("""[{ "id": "good-row", "children": [] }, { "BROKEN": true }, { "id": "another-good", "children": [] }]""")),
        ).table.sections[0].rows
        assertEquals(listOf("good-row", "another-good"), r.map { it.id })
    }

    @Test
    fun rowChildLabelDecodes() {
        val children = decodeTableView(
            tableViewJSON(
                sections = rows(
                    """[{ "id": "row", "children": [
                        { "id": "myLabel", "type": "label", "content": { "properties": { "text": "Hello" }, "style": {}, "layout": {} } }
                    ] }]""",
                ),
            ),
        ).table.sections[0].rows[0].children
        assertEquals(1, children.size)
        assertEquals("myLabel", children[0].id)
        assertTrue(children[0].type.isOneOf(ComponentType.Key.label))
    }

    @Test
    fun rowChildIconDecodes() {
        val child = decodeTableView(
            tableViewJSON(
                sections = rows(
                    """[{ "id": "row", "children": [
                        { "id": "chevron", "type": "icon", "content": { "properties": { "type": "symbol", "name": "chevron.right" }, "style": {}, "layout": {} } }
                    ] }]""",
                ),
            ),
        ).table.sections[0].rows[0].children[0]
        assertTrue(child.type.isOneOf(ComponentType.Key.icon))
        assertEquals("chevron", child.id)
    }

    @Test
    fun rowMultipleChildrenDecode() {
        val children = decodeTableView(
            tableViewJSON(
                sections = rows(
                    """[{ "id": "row", "children": [
                        { "id": "iconBg", "type": "view", "content": { "style": {}, "layout": {} } },
                        { "id": "rowLabel", "type": "label", "content": { "properties": { "text": "Item" }, "style": {}, "layout": {} } },
                        { "id": "rowChevron", "type": "icon", "content": { "properties": { "type": "symbol", "name": "chevron.right" }, "style": {}, "layout": {} } }
                    ] }]""",
                ),
            ),
        ).table.sections[0].rows[0].children
        assertEquals(3, children.size)
    }

    @Test
    fun standardChildrenAreEmptyByDefault() {
        assertTrue(decodeTableView(tableViewJSON()).table.children.isEmpty())
    }

    @Test
    fun layoutDecodes() {
        // Duplicate "layout" key in the JSON: the later (constraints) one wins, like on iOS.
        val t = decodeTableView(
            tableViewJSON(
                extra = """
                "layout": { "constraints": [ { "type": "top", "target": "superview" }, { "type": "bottom", "target": "superview" } ] }
                """,
            ),
        )
        assertNotNull(t.table.layout)
    }

    @Test
    fun fullSettingsStyleTableDecodes() {
        val content = decodeTableView(
            tableViewJSON(
                properties = """{ "tableStyle": "insetGrouped", "separatorInset": 64, "showsIndicator": false }""",
                sections = """
                [
                    { "id": "profileSection", "rows": [
                        { "id": "profileRow", "height": 72, "children": [
                            { "id": "profileName", "type": "label", "content": { "properties": { "text": "Alex Johnson" }, "style": {}, "layout": {} } }
                        ] }
                    ] },
                    { "id": "accountSection", "header": "Account", "rows": [
                        { "id": "notificationsRow", "height": 54, "children": [] },
                        { "id": "privacyRow",       "height": 54, "children": [] }
                    ] },
                    { "id": "footerSection", "rows": [
                        { "id": "footerRow", "height": 110, "clearBackground": true, "children": [] }
                    ] }
                ]
                """,
            ),
        ).table

        assertEquals(TableViewProperties.TableStyle.insetGrouped, content.properties.tableStyle)
        assertEquals(64.0, content.properties.separatorInset)
        assertEquals(false, content.properties.showsIndicator)
        assertEquals(3, content.sections.size)

        val profile = content.sections[0]
        assertNull(profile.header)
        assertEquals(1, profile.rows.size)
        assertEquals(72.0, profile.rows[0].height)
        assertEquals(1, profile.rows[0].children.size)

        val account = content.sections[1]
        assertEquals("Account", account.header)
        assertEquals(2, account.rows.size)
        assertNull(account.rows[0].clearBackground)

        val footer = content.sections[2]
        assertEquals(true, footer.rows[0].clearBackground)
        assertEquals(110.0, footer.rows[0].height)
    }
}
