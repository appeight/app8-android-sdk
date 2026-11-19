package dev.app8.engine.navigation

import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.model.Action
import dev.app8.engine.render.RecordingHost
import dev.app8.engine.render.RecordingNavigator
import dev.app8.engine.render.hexColor
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import dev.app8.engine.render.string
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Bar items (app8-dsl navigation.md, Bar Action): `icon`, `label` and `color` are expressions
 * in the screen's scope, read in composition (tracked) so the top app bar item follows them;
 * `action` is a single action or an array (iOS `BarActionExpressionTests`).
 */
class BarActionExpressionTest {
    private val heart = "{{includes(liked, product.handle) ? 'heart.fill' : 'heart'}}"
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"liked":{"type":"array","initialValue":[]},"product":{"type":"object","initialValue":{"handle":"p1"}}},
      "navigationBar":{"title":"Product",
        "leftAction":{"label":"{{liked.length}} saved","action":{"type":"dismiss"}},
        "rightAction":{"icon":"heart","action":{"type":"dismiss"}},
        "rightActions":[
          {"icon":"$heart","color":"{{includes(liked, product.handle) ? '#FF2D55' : '#000000'}}","action":[
            {"type":"toggleArrayValue","variableName":"liked","value":"{{product.handle}}"},
            {"type":"haptic","hapticStyle":"light"}]},
          {"icon":"square.and.arrow.up","action":{"type":"dismiss"}}]},
      "children":[]}}"""

    private val rt = runtime(mapOf("s" to screen))
    private val session = session(rt)
    private val host = RecordingHost()
    private val dispatcher = ActionDispatcher(rt, RecordingNavigator(), host)
    private val bar = session.content!!.navigationBar!!

    @Test
    fun `an action array decodes in order and a single action stays a list of one`() {
        val like = bar.rightActions!![0]
        assertEquals(listOf(Action.Type.toggleArrayValue, Action.Type.haptic), like.actions.map { it.type })
        assertEquals(Action.Type.toggleArrayValue, like.action.type)
        assertEquals(listOf(Action.Type.dismiss), bar.rightAction!!.actions.map { it.type })
        assertEquals(listOf(Action.Type.dismiss), bar.leftAction!!.actions.map { it.type })
    }

    @Test
    fun `icon, label and color follow the screen's variables`() {
        val root = session.root
        val like = bar.rightActions!![0]
        assertEquals("heart", root.string(like.icon))
        assertEquals("0 saved", root.string(bar.leftAction!!.label))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), root.hexColor(like.color))

        dispatcher.run(root, like.actions)
        assertEquals("heart.fill", root.string(like.icon))
        assertEquals("1 saved", root.string(bar.leftAction!!.label))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF2D55), root.hexColor(like.color))
        assertEquals(listOf("haptic:light"), host.calls)

        dispatcher.run(root, like.actions)
        assertEquals("heart", root.string(like.icon))
        assertEquals("square.and.arrow.up", root.string(bar.rightActions!![1].icon))
    }
}
