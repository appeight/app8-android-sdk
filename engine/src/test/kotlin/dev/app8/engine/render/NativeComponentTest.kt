package dev.app8.engine.render

import android.content.ContextWrapper
import dev.app8.engine.App8Config
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.model.NativeContent
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import dev.app8.engine.nativecomponent.App8NativeComponent
import dev.app8.engine.render.components.ComponentRegistry
import dev.app8.engine.render.components.NativeKind
import dev.app8.engine.runtime.App8Runtime
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
            "nested":{"count":"{{cartCount}}"},"fixed":3,"none":null}},
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

    private fun runtime(components: Map<String, App8NativeComponent>) = App8Runtime(
        ContextWrapper(null),
        MemoryDataSource(APP_JSON, mapOf("s" to screen)),
        App8Config(nativeComponents = components),
    )

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
        // DSL 0.6 has no author analytics on module events: only the navigation's auto event fires.
        assertEquals(listOf("app8.navigation.pushed"), analytics.map { it.name })
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
}
