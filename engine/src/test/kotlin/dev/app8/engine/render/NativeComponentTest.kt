package dev.app8.engine.render

import dev.app8.engine.App8Config
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.model.NativeContent
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.nativecomponent.App8NativeComponent
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.render.components.NativeKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DSL 0.6 `native`: registry lookup, fallback, props resolution and module events. */
class NativeComponentTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"tint":{"type":"string","initialValue":"#1E88E5"},"lastSignatureId":{"type":"string","initialValue":""}},
      "children":[
        {"type":"native","id":"pad","content":{
          "properties":{"module":"signaturePad","props":{"tint":"{{tint}}","label":"Tint {{tint}}","colors":["black","{{tint}}"],
            "nested":{"count":"{{taskCount}}"},"fixed":3,"none":null}},
          "analytics":{"completed":{"name":"signatureCompleted","properties":{"signature":"{{event.signature.id}}"}}},
          "actions":{
            "completed":[
              {"type":"updateVariable","variableName":"lastSignatureId","value":"{{event.signature.id}}"},
              {"type":"navigation","nextScreen":"receipt","params":{"signatureId":"{{event.signature.id}}","level":"{{event.signature.levels[0].level}}"}}
            ],
            "cancelled":{"type":"dismiss"}
          },
          "fallback":{"type":"view","id":"padFallback","content":{"children":[
            {"type":"label","id":"msg","content":{"properties":{"text":"Signing is not available"}}}]}}
        }}
      ]}}"""

    private fun runtime(components: Map<String, App8NativeComponent>) =
        runtime(MemoryDataSource(APP_JSON, mapOf("s" to screen)), App8Config(nativeComponents = components))

    private val registered = App8NativeComponent { _, _ -> }

    @Test
    fun `native renders through NativeKind`() {
        val rt = runtime(emptyMap())
        val node = session(rt).root.find("pad")!!
        assertSame(NativeKind, ComponentRegistry.kind(node))
    }

    @Test
    fun `an unregistered module keeps its fallback subtree as the only child`() {
        val node = session(runtime(emptyMap())).root.find("pad")!!
        assertEquals(listOf("padFallback"), node.children.map { it.id })
        assertEquals(listOf("msg"), node.children.single().children.map { it.id })
    }

    @Test
    fun `a registered module drops the fallback from the tree`() {
        val node = session(runtime(mapOf("signaturePad" to registered))).root.find("pad")!!
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun `props resolve expressions, nested values included, and follow variable changes`() {
        val rt = runtime(mapOf("signaturePad" to registered))
        val s = session(rt)
        val node = s.root.find("pad")!!
        val content = node.content as NativeContent
        val props = NativeKind.resolveProps(node, content, node.scope.untracked)
        assertEquals("#1E88E5", props["tint"])
        assertEquals("Tint #1E88E5", props["label"])
        assertEquals(listOf("black", "#1E88E5"), props["colors"])
        assertEquals(mapOf("count" to 2L), props["nested"])
        assertEquals(3L, props["fixed"])
        assertTrue(props.containsKey("none") && props["none"] == null)

        s.scope.store.setValue("tint", "#000000")
        assertEquals("#000000", NativeKind.resolveProps(node, content, node.scope.untracked)["tint"])
    }

    @Test
    fun `emit forwards to the bus, then runs the event's actions with event in scope`() {
        val rt = runtime(mapOf("signaturePad" to registered))
        val s = session(rt)
        val nav = RecordingNavigator()
        val dispatcher = ActionDispatcher(rt, nav, RecordingHost())
        val events = mutableListOf<App8Event>()
        val analytics = mutableListOf<App8AnalyticsEvent>()
        rt.eventBus.subscribe { events += it }
        rt.analyticsBus.observe { analytics += it }
        val node = s.root.find("pad")!!

        // Host values (Int, Float) arrive normalized to DSL values (Long, Double).
        dispatcher.dispatchNativeEvent(node, "completed", mapOf("signature" to mapOf("id" to "abc", "levels" to listOf(mapOf("level" to 3))), "score" to 0.5f))

        val e = events.single()
        assertEquals("native.signaturePad.completed", e.name)
        assertEquals("s", e.screenId)
        assertEquals("pad", e.componentId)
        assertEquals("native", e.componentType)
        assertEquals(mapOf("id" to "abc", "levels" to listOf(mapOf("level" to 3L))), e.payload["signature"])
        assertEquals(0.5, e.payload["score"])
        // The author binding keyed by the event name fires with `event` in scope (no auto tap event).
        assertEquals(listOf("app8.signatureCompleted", "app8.navigation.pushed"), analytics.map { it.name })
        assertEquals("abc", analytics[0].properties["signature"])
        assertEquals("pad", analytics[0].properties["component_id"])
        assertEquals("native", analytics[0].properties["component_type"])
        assertEquals("abc", s.scope.store.getValue("lastSignatureId"))
        assertEquals(listOf("push:receipt"), nav.calls)
        assertEquals(mapOf("signatureId" to "abc", "level" to 3L), nav.params.single())
    }

    @Test
    fun `an event without actions still reaches the bus`() {
        val rt = runtime(mapOf("signaturePad" to registered))
        val s = session(rt)
        val nav = RecordingNavigator()
        val dispatcher = ActionDispatcher(rt, nav, RecordingHost())
        val events = mutableListOf<String>()
        rt.eventBus.subscribe { events += it.name }
        val node = s.root.find("pad")!!

        dispatcher.dispatchNativeEvent(node, "failed", mapOf("reason" to "camera_denied"))
        dispatcher.dispatchNativeEvent(node, "cancelled", emptyMap())

        assertEquals(listOf("native.signaturePad.failed", "native.signaturePad.cancelled"), events)
        assertEquals(listOf("dismiss"), nav.calls)
    }

    // DSL 0.6.1 visibility (app8-dsl `components/native.md#visibility`).

    private val visibilityScreen = """{"type":"screen","id":"v","content":{
      "variables":{"appearCount":{"type":"number","initialValue":0},"lastState":{"type":"string","initialValue":""}},
      "children":[
        {"type":"native","id":"player","content":{
          "properties":{"module":"videoPlayer"},
          "actions":{
            "appear":[{"type":"incrementVariable","variableName":"appearCount","by":1},{"type":"updateVariable","variableName":"lastState","value":"shown"}],
            "disappear":{"type":"updateVariable","variableName":"lastState","value":"hidden"}
          },
          "analytics":{"appear":"playerShown"}
        }}
      ]}}"""

    @Test
    fun `appear and disappear run their actions and analytics with an empty event, not on the bus`() {
        val rt = runtime(MemoryDataSource(APP_JSON, mapOf("v" to visibilityScreen)), App8Config(nativeComponents = mapOf("videoPlayer" to registered)))
        val s = session(rt, "v")
        val dispatcher = ActionDispatcher(rt, RecordingNavigator(), RecordingHost())
        val events = mutableListOf<String>()
        val analytics = mutableListOf<String>()
        rt.eventBus.subscribe { events += it.name }
        rt.analyticsBus.observe { analytics += it.name }
        val node = s.root.find("player")!!

        dispatcher.dispatchNativeVisibility(node, true)
        assertEquals(1L, (s.scope.store.getValue("appearCount") as Number).toLong())
        assertEquals("shown", s.scope.store.getValue("lastState"))
        dispatcher.dispatchNativeVisibility(node, false)
        assertEquals("hidden", s.scope.store.getValue("lastState"))

        assertTrue(events.isEmpty(), "visibility is not posted on the event bus: $events")
        assertEquals(listOf("app8.playerShown"), analytics)
    }

    @Test
    fun `a module cannot emit the reserved appear and disappear names`() {
        val rt = runtime(MemoryDataSource(APP_JSON, mapOf("v" to visibilityScreen)), App8Config(nativeComponents = mapOf("videoPlayer" to registered)))
        val s = session(rt, "v")
        val dispatcher = ActionDispatcher(rt, RecordingNavigator(), RecordingHost())
        val events = mutableListOf<String>()
        rt.eventBus.subscribe { events += it.name }
        val node = s.root.find("player")!!

        dispatcher.dispatchNativeEvent(node, "appear", emptyMap())
        dispatcher.dispatchNativeEvent(node, "disappear", emptyMap())

        assertTrue(events.isEmpty())
        assertEquals(0L, (s.scope.store.getValue("appearCount") as Number).toLong())
        assertTrue(rt.diagnostics.snapshot().any { it.startsWith("native.event.reserved") })
    }

    @Test
    fun `a context starts not visible unless built otherwise`() {
        val context = dev.app8.engine.nativecomponent.App8NativeContext(emptyMap(), { _, _ -> }, "app", "s", "m")
        assertEquals(false, context.isVisible)
        assertEquals(true, context.copy(isVisible = true).isVisible)
    }
}
