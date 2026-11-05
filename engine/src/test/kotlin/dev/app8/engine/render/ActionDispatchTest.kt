package dev.app8.engine.render

import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.actions.normalizeAuthorAnalyticsName
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.events.App8AnalyticsEvent
import dev.app8.engine.events.App8Event
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionDispatchTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"count":{"type":"number","initialValue":0},"name":{"type":"string","initialValue":"Ada"}},
      "children":[
        {"type":"button","id":"buy","content":{"properties":{"text":"Buy"},
          "analytics":{"tap":{"name":"buyClicked","properties":{"who":"{{name}}","screen_id":"spoof"}}},
          "actions":{"tap":[
            {"type":"incrementVariable","variableName":"count","by":2},
            {"type":"incrementVariable","variableName":"cartCount"},
            {"type":"navigation","nextScreen":"detail","params":{"n":"{{count}}","fixed":7}},
            {"type":"emit","name":"bought","payload":{"who":"{{name}}","qty":"{{count}}"}},
            {"type":"openURL","url":"https://x.dev/{{name}}","urlPresentation":"sheet"},
            {"type":"haptic","hapticStyle":"success"}
          ]}}},
        {"type":"view","id":"card","content":{"states":{"on":{"childStates":{"dot":"lit"}}},
          "children":[{"type":"view","id":"dot","content":{"states":{"lit":{}}}}],
          "actions":{"tap":{"type":"navigation","nextScreen":"sheet","presentation":"sheet","detents":["medium","large"]}}}},
        {"type":"view","id":"back","content":{"actions":{"tap":[{"type":"navigation","isBack":true},{"type":"selectTab","tabId":"cart"},{"type":"dismiss"}]}}}
      ]}}"""

    private val rt = runtime(mapOf("s" to screen))
    private val session = session(rt)
    private val nav = RecordingNavigator()
    private val host = RecordingHost()
    private val dispatcher = ActionDispatcher(rt, nav, host)
    private val analytics = mutableListOf<App8AnalyticsEvent>()
    private val events = mutableListOf<App8Event>()

    init {
        rt.analyticsBus.observe { analytics += it }
        rt.eventBus.subscribe { events += it }
    }

    private fun node(id: String) = session.root.find(id)!!

    @Test
    fun `a tap fires analytics first, then every action in order`() {
        dispatcher.dispatchTrigger(node("buy"), ActionTrigger.tap)

        assertEquals(listOf("app8.component.tapped", "app8.buyClicked", "app8.navigation.pushed", "app8.url.opened"), analytics.map { it.name })
        val tapped = analytics[0]
        assertEquals("s", tapped.properties["screen_id"])
        assertEquals("buy", tapped.properties["component_id"])
        assertEquals("button", tapped.properties["component_type"])
        assertEquals(dev.app8.engine.core.EngineVersion.CURRENT, tapped.properties["engine_version"])
        // Author binding: resolved properties; the canonical key wins over the author's.
        assertEquals("Ada", analytics[1].properties["who"])
        assertEquals("s", analytics[1].properties["screen_id"])
        assertEquals(mapOf("from_screen_alias" to "s", "to_screen_alias" to "detail"), analytics[2].properties.filterKeys { it.endsWith("alias") })

        assertEquals(2L, session.scope.store.getValue("count"))
        assertEquals(3L, rt.appStore.getValue("cartCount"), "writes reach the scope that defines the variable")
        assertEquals(listOf("push:detail"), nav.calls)
        assertEquals(mapOf("n" to 2L, "fixed" to 7L), nav.params.single())
        assertEquals("bought", events.single().name)
        assertEquals(mapOf("who" to "Ada", "qty" to 2L), events.single().payload, "emit payloads are resolved but never get canonical keys")
        assertEquals(listOf("url:https://x.dev/Ada:sheet", "haptic:success"), host.calls)
    }

    @Test
    fun `presentation, back, tabs and dismiss go to the navigator`() {
        dispatcher.dispatchTrigger(node("card"), ActionTrigger.tap)
        dispatcher.dispatchTrigger(node("back"), ActionTrigger.tap)
        assertEquals(listOf("present:sheet:sheet:2", "pop:false", "tab:null:cart", "dismiss"), nav.calls)
        assertEquals(true, analytics.last { it.name == "app8.navigation.pushed" }.properties["is_back"])
    }

    @Test
    fun `setState applies child states by id`() {
        dispatcher.setState(node("card"), "on")
        assertEquals("on", node("card").stateName)
        assertEquals("lit", node("dot").stateName)
    }

    @Test
    fun `auto taps can be turned off while author bindings still fire`() {
        rt.analyticsConfig = rt.analyticsConfig.copy(autoComponentTaps = false, autoNavigationEvents = false, autoUrlEvents = false)
        dispatcher.dispatchTrigger(node("buy"), ActionTrigger.tap)
        assertEquals(listOf("app8.buyClicked"), analytics.map { it.name })
    }

    @Test
    fun `author analytics names are normalized idempotently`() {
        val warnings = mutableListOf<String>()
        val warn = { k: String, _: String -> warnings += k; Unit }
        assertEquals("app8.stripeConnectClicked", normalizeAuthorAnalyticsName("stripeConnectClicked", warn))
        assertEquals("app8.foo", normalizeAuthorAnalyticsName("app8.foo", warn))
        assertEquals("app8.foo", normalizeAuthorAnalyticsName(normalizeAuthorAnalyticsName("app8.foo")))
        assertEquals("app8.screen.appeared", normalizeAuthorAnalyticsName("screen.appeared", warn))
        assertEquals("app8.", normalizeAuthorAnalyticsName("app8.", warn))
        assertTrue(warnings.any { it.startsWith("author-name:reserved") })
    }

    @Test
    fun `redact can drop or rewrite analytics`() {
        rt.analyticsBus.redact = { e -> if (e.name == "app8.component.tapped") null else e.copy(properties = e.properties - "who") }
        dispatcher.dispatchTrigger(node("buy"), ActionTrigger.tap)
        assertTrue(analytics.none { it.name == "app8.component.tapped" })
        assertNull(analytics.first { it.name == "app8.buyClicked" }.properties["who"])
    }

    // iOS PortFindingsTests #2 / #3 (app8-ios-sdk#36).
    private val portScreen = """{"type":"screen","id":"p","content":{
      "variables":{"note":{"type":"string","initialValue":""},"last":{"type":"string","initialValue":""},
        "email":{"type":"string","initialValue":"a@b.c"},"fromParam":{"type":"string"}},
      "children":[
        {"type":"textView","id":"tv","content":{"properties":{"bindVariable":"note"},
          "analytics":{"onTextChange":{"name":"noteEdited"}},
          "actions":{"onTextChange":{"type":"updateVariable","variableName":"last","value":"{{${'$'}value}}"}}}},
        {"type":"view","id":"plain","content":{"actions":{"tap":{"type":"resetVariables","scope":"component"}}}},
        {"type":"view","id":"owner","content":{"variables":{"draft":{"type":"string","initialValue":"x"}},
          "actions":{"tap":{"type":"resetVariables","scope":"component"}}}},
        {"type":"view","id":"screenReset","content":{"actions":{"tap":{"type":"resetVariables"}}}}
      ]}}"""

    @Test
    fun `textView onTextChange runs with the new text as value and fires analytics`() {
        val rt2 = runtime(mapOf("p" to portScreen))
        val s2 = session(rt2, "p")
        val seen = mutableListOf<String>()
        rt2.analyticsBus.observe { seen += it.name }
        val d = ActionDispatcher(rt2, RecordingNavigator(), RecordingHost())
        d.dispatchTextChange(s2.root.find("tv")!!, "hello")
        assertEquals("hello", s2.scope.store.getValue("last"))
        assertTrue("app8.noteEdited" in seen, "$seen")
    }

    @Test
    fun `resetVariables restores initial values, component scope only resets an own store`() {
        val rt2 = runtime(mapOf("p" to portScreen))
        val s2 = session(rt2, "p", params = mapOf("fromParam" to "injected"))
        val d = ActionDispatcher(rt2, RecordingNavigator(), RecordingHost())
        val store = s2.scope.store
        store.setValue("email", "changed")
        store.setValue("fromParam", "edited")
        rt2.appStore.setValue("cartCount", 9)

        d.dispatchTrigger(s2.root.find("plain")!!, ActionTrigger.tap)
        assertEquals("changed", store.getValue("email"), "a component without variables has an empty own store")

        val owner = s2.root.find("owner")!!
        owner.scope.store.setValue("draft", "y")
        d.dispatchTrigger(owner, ActionTrigger.tap)
        assertEquals("x", owner.scope.store.getValue("draft"))
        assertEquals("changed", store.getValue("email"))

        d.dispatchTrigger(s2.root.find("screenReset")!!, ActionTrigger.tap)
        assertEquals("a@b.c", store.getValue("email"))
        assertEquals("injected", store.getValue("fromParam"), "an injected param is the initial value")
        assertEquals(9L, rt2.appStore.getValue("cartCount"), "screen scope leaves app variables alone")
    }
}
