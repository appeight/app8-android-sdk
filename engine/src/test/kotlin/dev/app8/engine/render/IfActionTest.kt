package dev.app8.engine.render

import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.model.ActionCondition
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.vars.Values
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `if` control-flow action (app8-dsl docs/actions.md, Control Flow Actions). */
class IfActionTest {
    private val app = """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]}}"""

    private fun log(v: String) = """{"type":"appendToArray","variableName":"log","value":"$v"}"""

    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{
        "log":{"type":"array","initialValue":[]},
        "size":{"type":"string"},
        "on":{"type":"boolean","initialValue":true}
      },
      "children":[
        {"type":"view","id":"size","content":{"actions":{"tap":
          {"type":"if","condition":"{{size != null}}","then":[${log("then")}],"else":[${log("else")}]}}}},
        {"type":"view","id":"noElse","content":{"actions":{"tap":[
          {"type":"if","condition":"{{false}}","then":${log("then")}},
          {"type":"if","condition":true,"then":${log("then")}}
        ]}}},
        {"type":"view","id":"once","content":{"actions":{"tap":
          {"type":"if","condition":"{{on}}",
            "then":[{"type":"updateVariable","variableName":"on","value":false},${log("then")}],
            "else":[${log("else")}]}}}},
        {"type":"view","id":"unknown","content":{"actions":{"tap":[
          {"type":"teleport","to":"moon"},
          ${log("after")}
        ]}}},
        {"type":"view","id":"nested","content":{"actions":{"tap":
          {"type":"with","values":{"limit":2},"actions":
            {"type":"forEach","items":"{{[1, 2, 3]}}","as":"n","actions":
              {"type":"if","condition":"{{n <= limit}}",
                "then":{"type":"if","condition":"{{n == 1}}","then":${log("one")},"else":${log("small {{n}}")}},
                "else":${log("big {{n}}")}}}}}}}
      ]}}"""

    private val rt = runtime(MemoryDataSource(app, mapOf("s" to screen)))
    private val session = session(rt)
    private val dispatcher = ActionDispatcher(rt, RecordingNavigator(), RecordingHost())
    private fun tap(id: String) = dispatcher.dispatchTrigger(session.root.find(id)!!, ActionTrigger.tap)
    private fun value(name: String) = session.scope.store.getValue(name)

    @Test
    fun `if runs then when the condition holds, else otherwise`() {
        tap("size")
        session.scope.store.setValue("size", "M")
        tap("size")
        assertEquals(listOf("else", "then"), value("log"))
    }

    @Test
    fun `if without else does nothing when false, and one action works as a list`() {
        tap("noElse")
        assertEquals(listOf("then"), value("log"))
    }

    @Test
    fun `the condition is evaluated once`() {
        tap("once")
        assertEquals(listOf("then"), value("log"))
        assertEquals(false, value("on"))
    }

    @Test
    fun `if nests inside forEach, with and if`() {
        tap("nested")
        assertEquals(listOf("one", "small 2", "big 3"), value("log"))
    }

    @Test
    fun `condition truthiness`() {
        val cases = listOf<Pair<Any?, Boolean>>(
            true to true, false to false, 1L to true, 0L to false, -2.5 to true,
            "yes" to true, "" to false, "false" to false, "0" to true,
            listOf(1L) to true, emptyList<Any?>() to false, mapOf("a" to 1L) to true, emptyMap<String, Any?>() to false,
            null to false, Values.Null to false,
            "{{missing." to false,
        )
        for ((input, expected) in cases) assertEquals(expected, ActionCondition.holds(input), "$input")
    }

    @Test
    fun `an unknown action type is skipped and the list goes on`() {
        tap("unknown")
        assertEquals(listOf("after"), value("log"))
        assertTrue(rt.diagnostics.snapshot().any { "teleport" in it }, "one diagnostic names the type")
    }
}
