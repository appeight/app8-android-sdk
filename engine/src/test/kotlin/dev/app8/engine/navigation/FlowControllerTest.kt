package dev.app8.engine.navigation

import dev.app8.engine.core.model.DslDecoder
import org.junit.Test
import kotlin.test.assertEquals

class FlowControllerTest {
    private val app = DslDecoder().decodeApp(
        """{"title":"T","navigation":{"startFlow":"launch","flows":[
          {"id":"launch","startScreen":"splash"},{"id":"main","startScreen":"tabs"}]}}""",
    )
    private val warnings = mutableListOf<String>()

    private fun top(flow: FlowController) = flow.layers.single().stack.entries.single().screenId

    @Test
    fun completeFlowSwitchesToTheFlowsStartScreen() {
        val flow = FlowController(app, "splash", startFlowId = "launch", warn = warnings::add)
        flow.completeFlow("main")
        assertEquals("tabs", top(flow))
        assertEquals("main", flow.currentFlowId)
    }

    @Test
    fun unknownFlowWarnsAndKeepsTheScreen() {
        // iOS throws NavigationError.flowNotFound; a screen id is not a flow.
        val flow = FlowController(app, "splash", startFlowId = "launch", warn = warnings::add)
        flow.completeFlow("tabs")
        assertEquals("splash", top(flow))
        assertEquals(listOf("Flow not found: tabs"), warnings)
    }

    @Test
    fun completingTheCurrentFlowAgainIsIgnored() {
        val flow = FlowController(app, "splash", startFlowId = "launch", warn = warnings::add)
        flow.completeFlow("main")
        val before = flow.layers.single().stack.entries.single().id
        flow.completeFlow("main")
        assertEquals(before, flow.layers.single().stack.entries.single().id)
    }
}

class StatusBarContrastTest {
    @Test
    fun lightContentGetsDarkIcons() {
        assertEquals(true, lightIconsUnreadable(0.95))
        assertEquals(false, lightIconsUnreadable(0.08))
    }
}
