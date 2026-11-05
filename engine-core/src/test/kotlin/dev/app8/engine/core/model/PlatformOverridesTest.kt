package dev.app8.engine.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-node `ios` / `android` overrides and `platforms` filters (app8-dsl docs/platforms.md). */
class PlatformOverridesTest {

    private val android = DslDecoder(Platform.ANDROID)
    private val ios = DslDecoder(Platform.IOS)

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun docExampleIconUsesAndroidSymbolName() {
        val json = """
            { "id": "fav", "type": "icon", "content": {
                "properties": { "type": "symbol", "name": "heart.fill" },
                "android": { "properties": { "name": "favorite" } } } }
        """
        val onAndroid = android.decodeComponent(json).contentAs<IconContent>()!!
        assertEquals(IconProperties.Model.Symbol("favorite"), onAndroid.properties.model)
        // Merge is per key: `type` from the base node survives.
        assertEquals(IconProperties.Type.symbol, onAndroid.properties.type)

        val onIos = ios.decodeComponent(json).contentAs<IconContent>()!!
        assertEquals(IconProperties.Model.Symbol("heart.fill"), onIos.properties.model)
    }

    @Test
    fun iosOverrideAppliesOnlyOnIos() {
        val json = """
            { "id": "t", "type": "label", "content": {
                "properties": { "text": "base" },
                "ios": { "properties": { "text": "apple" } },
                "android": { "properties": { "text": "robot" } } } }
        """
        assertEquals(LocalizedString.Literal("robot"), android.decodeComponent(json).contentAs<LabelContent>()!!.properties.text)
        assertEquals(LocalizedString.Literal("apple"), ios.decodeComponent(json).contentAs<LabelContent>()!!.properties.text)
    }

    @Test
    fun nodeLevelOverrideMergesIntoTheNode() {
        val json = """
            { "id": "v", "type": "view",
              "android": { "content": { "layout": { "height": 56 } } },
              "content": { "layout": { "width": 10, "height": 44 } } }
        """
        val layout = android.decodeComponent(json).contentAs<ViewContent>()!!.layout!!
        assertEquals(Layout.Dimension.Fixed(10f), layout.width)
        assertEquals(Layout.Dimension.Fixed(56f), layout.height)
    }

    @Test
    fun objectsMergeRecursivelyArraysAndScalarsReplace() {
        val base = obj("""{ "a": { "b": 1, "c": [1, 2, 3], "d": { "e": "x", "f": "y" } }, "g": true }""")
        val override = obj("""{ "a": { "c": [9], "d": { "f": "z" } }, "g": { "now": "object" } }""")
        val merged = PlatformOverrides.merge(base, override)
        assertEquals(
            obj("""{ "a": { "b": 1, "c": [9], "d": { "e": "x", "f": "z" } }, "g": { "now": "object" } }"""),
            merged,
        )
    }

    @Test
    fun childrenArrayInOverrideReplacesInsteadOfMergingById() {
        val json = """
            { "id": "root", "type": "view", "content": {
                "children": [ { "id": "a", "type": "view", "content": {} }, { "id": "b", "type": "view", "content": {} } ],
                "android": { "children": [ { "id": "c", "type": "view", "content": {} } ] } } }
        """
        assertEquals(listOf("c"), android.decodeComponent(json).content!!.children.map { it.id })
        assertEquals(listOf("a", "b"), ios.decodeComponent(json).content!!.children.map { it.id })
    }

    @Test
    fun platformKeysAreDroppedAfterMerging() {
        val node = obj("""{ "type": "view", "ios": { "x": 1 }, "android": { "y": 2 }, "content": { "ios": {}, "android": { "z": 3 } } }""")
        val applied = PlatformOverrides.apply(node, Platform.ANDROID)!!
        assertFalse("ios" in applied || "android" in applied)
        val content = applied["content"] as JsonObject
        assertFalse("ios" in content || "android" in content)
        assertEquals(JsonPrimitive(2), applied["y"])
        assertEquals(JsonPrimitive(3), content["z"])
        assertNull(applied["x"])
    }

    @Test
    fun childrenNotListingAndroidAreDropped() {
        val json = """
            { "id": "root", "type": "view", "content": { "children": [
                { "id": "both", "type": "view", "content": {} },
                { "id": "iosOnly", "type": "view", "platforms": ["ios"], "content": {} },
                { "id": "androidOnly", "type": "view", "platforms": ["android"], "content": {} },
                { "id": "everywhere", "type": "view", "platforms": ["ios", "android"], "content": {} } ] } }
        """
        val collector = DecodeErrorCollector()
        val root = DslDecoder(DecodeOptions(errorCollector = collector)).decodeComponent(json)
        assertEquals(listOf("both", "androidOnly", "everywhere"), root.content!!.children.map { it.id })
        assertTrue(collector.entries.isEmpty(), "platform filtering is not a decode error")
        assertEquals(listOf("both", "iosOnly", "everywhere"), ios.decodeComponent(json).content!!.children.map { it.id })
    }

    @Test
    fun excludedRootThrowsPlatformExcluded() {
        assertFailsWith<PlatformExcludedException> {
            android.decodeComponent("""{ "id": "s", "type": "screen", "platforms": ["ios"], "content": {} }""")
        }
    }

    @Test
    fun excludedSingleSlotsBecomeNull() {
        val json = """
            { "id": "list", "type": "collection", "content": {
                "properties": { "data": "{{items}}" },
                "template": { "type": "label", "platforms": ["ios"], "content": { "properties": { "text": "x" } } },
                "emptyState": { "id": "e", "type": "label", "platforms": ["ios"], "content": {} },
                "sectionHeaders": {
                  "a": { "id": "ha", "type": "label", "content": {} },
                  "b": { "id": "hb", "type": "label", "platforms": ["ios"], "content": {} } } } }
        """
        val collection = android.decodeComponent(json).contentAs<CollectionContent>()!!
        assertNull(collection.template)
        assertNull(collection.emptyState)
        assertEquals(setOf("a"), collection.sectionHeaders!!.keys)
        val onIos = ios.decodeComponent(json).contentAs<CollectionContent>()!!
        assertEquals("template", onIos.template?.id)
    }

    @Test
    fun overrideCanFixANodeThatWouldOtherwiseFail() {
        // An invalid base value replaced by a valid android value decodes on Android only.
        val json = """
            { "id": "s", "type": "shape", "content": {
                "properties": { "kind": "hexagon" },
                "android": { "properties": { "kind": "circle" } } } }
        """
        assertEquals(ShapeProperties.Kind.circle, android.decodeComponent(json).contentAs<ShapeContent>()!!.properties.kind)
        assertFailsWith<DslDecodingException> { ios.decodeComponent(json) }
    }

    @Test
    fun templatesApplyOverridesAtLoad() {
        val template = android.decodeTemplate(
            """{ "id": "Card", "type": "view", "content": { "properties": { "cornerRadius": 8 }, "android": { "properties": { "cornerRadius": 12 } } } }""",
        )
        val content = template.rawContent as JsonObject
        assertEquals(JsonPrimitive(12), (content["properties"] as JsonObject)["cornerRadius"])
        assertFalse("android" in content)
        assertFailsWith<PlatformExcludedException> {
            android.decodeTemplate("""{ "id": "IosCard", "type": "view", "platforms": ["ios"], "content": {} }""")
        }
    }

    @Test
    fun appPlatformsAndDslVersion() {
        val app = android.decodeApp(
            """
            { "title": "Shop", "dsl": "1.1",
              "platforms": { "ios": { "minVersion": "18.0" }, "android": { "minSdk": 33 } } }
            """,
        )
        assertEquals("1.1", app.dsl)
        assertEquals(33, app.platforms?.android?.minSdk)
        assertEquals("18.0", app.platforms?.ios?.minVersion)
    }

    @Test
    fun malformedPlatformsBlockNeverRejectsTheApp() {
        val app = android.decodeApp("""{ "title": "Shop", "dsl": 1.1, "platforms": { "android": { "minSdk": "33" }, "ios": 5 } }""")
        assertEquals("Shop", app.title)
        assertEquals("1.1", app.dsl)
        assertNull(app.platforms?.android?.minSdk)
        assertNull(app.platforms?.ios)
        assertNull(android.decodeApp("""{ "platforms": [1, 2] }""").platforms)
    }

    @Test
    fun platformsWithNonArrayValueIsIgnored() {
        val node = obj("""{ "type": "view", "platforms": "ios" }""")
        assertTrue(PlatformOverrides.isIncluded(node, Platform.ANDROID))
        assertFalse(PlatformOverrides.isIncluded(obj("""{ "platforms": [] }"""), Platform.ANDROID))
        assertIs<JsonArray>(obj("""{ "platforms": [] }""")["platforms"])
    }
}
