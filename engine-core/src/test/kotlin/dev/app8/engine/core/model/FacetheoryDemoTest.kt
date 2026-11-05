package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.StyleEntity
import dev.app8.engine.core.model.style.StylePointer
import dev.app8.engine.core.model.style.TextModel
import dev.app8.engine.core.model.style.ThemedColor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The Facetheory demo app (shoppu-web `packages/app-dsl/demo/facetheory`) decodes without errors. */
class FacetheoryDemoTest {

    private fun resource(path: String): String =
        javaClass.getResource("/facetheory/$path")?.readText() ?: fail("missing test resource facetheory/$path")

    private fun resourceDir(path: String): List<File> {
        val url = javaClass.getResource("/facetheory/$path") ?: fail("missing test resource dir facetheory/$path")
        return File(url.toURI()).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
    }

    @Test
    fun appJsonDecodes() {
        val app = DslDecoder().decodeApp(resource("app.json"))
        assertEquals("Facetheory", app.title)
        assertEquals(App.UserInterfaceStyle.light, app.defaultUserInterfaceStyle)
        assertEquals("main", app.navigation?.startFlow)
        assertEquals(listOf("main", "store"), app.navigation?.flows?.map { it.id })
        assertEquals("launch", app.initialScreenId)
        assertNotNull(app.variables?.get("cart"))
    }

    @Test
    fun stylesJsonDecodesEveryEntry() {
        val collector = DecodeErrorCollector()
        val decoder = DslDecoder(DecodeOptions(errorCollector = collector))
        val styles = decoder.decodeStyles(resource("styles.json"))
        assertEquals(emptyList(), collector.entries.map { "${it.jsonPath}: ${it.error.message}" })
        val raw = decoder.parse(resource("styles.json")) as kotlinx.serialization.json.JsonArray
        assertEquals(raw.size, styles.size)
        val green = styles.first { it.id == "brandGreen" }
        assertIs<StyleEntity<*>>(green)
        assertEquals(0x214E38, (green.content as ThemedColor).light!!.argb and 0xFFFFFF)
        val title = styles.first { it.id == "sectionTitle" } as StyleEntity<*>
        assertEquals(24.0, (title.content as TextModel).fontSize)
    }

    @Test
    fun componentTemplatesDecode() {
        val decoder = DslDecoder()
        for (file in resourceDir("components")) {
            val template = decoder.decodeTemplate(file.readText())
            assertEquals(file.nameWithoutExtension, template.id)
            // The template content is a full component tree on its own.
            val asComponent = decoder.decodeComponent(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(template.id))
                    put("type", kotlinx.serialization.json.JsonPrimitive(template.type.raw))
                    put("content", template.rawContent!!)
                },
            )
            assertNoDroppedNodes(file.name, decoder.parse(file.readText()), asComponent)
        }
    }

    @Test
    fun everyScreenDecodesWithoutDroppingNodes() {
        val decoder = DslDecoder()
        val templates = TemplateResolver(resourceDir("components").map { decoder.decodeTemplate(it.readText()) })
        val screens = resourceDir("screens")
        assertEquals(6, screens.size)
        for (file in screens) {
            val collector = DecodeErrorCollector()
            val d = DslDecoder(DecodeOptions(errorCollector = collector))
            val component = d.decodeScreen(file.readText(), templates)
            assertEquals(emptyList(), collector.entries.map { "${file.name} ${it.jsonPath}: ${it.error.message}" })
            assertTrue(component.type.isOneOf(ComponentType.Key.screen, ComponentType.Key.tabBarScreen), file.name)
            assertIs<ComponentEntity<*>>(component, file.name)
            val expanded = TemplatePreprocessor(templates).process(d.parse(file.readText()))
            assertNoDroppedNodes(file.name, expanded, component)
        }
    }

    @Test
    fun tabsScreenHasThreeTabs() {
        val tabs = DslDecoder().decodeComponent(resource("screens/tabs.json")).contentAs<TabBarScreenContent>()!!
        assertEquals(listOf("shop", "cart", "account"), tabs.tabs.map { it.id })
        assertEquals("{{sum(map(cart, item.quantity))}}", tabs.tabs[1].badge)
        assertEquals("shop", tabs.initialTab)
    }

    @Test
    fun accountScreenKeepsStylePointersAndExpressions() {
        val screen = DslDecoder().decodeComponent(resource("screens/account.json")).contentAs<ViewContent>()!!
        assertEquals(true, screen.navigationBar?.hidden)
        val title = screen.children.first { it.id == "accountTitle" }.contentAs<LabelContent>()!!
        assertEquals(LocalizedString.Literal("Account"), title.properties.text)
        val pointer = title.style!!.text.base
        assertIs<StylePointer>(pointer)
        assertEquals("sectionTitle", pointer.id)
        assertEquals(3, title.layout?.constraints?.size)
        assertEquals(Layout.Target.SafeArea(null), title.layout!!.constraints!![0].target)
    }

    /** Counts component nodes (objects with string `type` + `id` under `children`) and compares with the decoded tree. */
    private fun assertNoDroppedNodes(name: String, json: kotlinx.serialization.json.JsonElement, decoded: Component) {
        fun countJson(e: kotlinx.serialization.json.JsonElement): Int {
            val o = e as? kotlinx.serialization.json.JsonObject ?: return 0
            val children = ((o["content"] as? kotlinx.serialization.json.JsonObject)?.get("children") as? kotlinx.serialization.json.JsonArray)
                ?: return 1
            return 1 + children.sumOf { countJson(it) }
        }

        fun countDecoded(c: Component): Int = 1 + (c.content?.children?.sumOf { countDecoded(it) } ?: 0)
        assertEquals(countJson(json), countDecoded(decoded), "$name: some component nodes were dropped")
    }
}
