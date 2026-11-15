package dev.app8.engine.core.model

import dev.app8.engine.core.model.style.Fill
import dev.app8.engine.core.model.style.decodeFill
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Decoding of the DSL 0.6.0 additions: control-flow, array and network actions, color expressions, isHidden. */
class Dsl060ModelTest {
    private fun action(json: String): Action = DslDecoder().decode(json, ::decodeAction)

    @Test
    fun `forEach and with nest single actions or lists`() {
        val loop = action(
            """{"type":"forEach","items":"{{selectedTasks}}","as":"line","index":"i",
               "actions":{"type":"incrementArrayItem","variableName":"tasks","matchBy":"key","value":{"key":"{{line.key}}"}}}""",
        )
        assertEquals(Action.Type.forEach, loop.type)
        assertEquals("line", loop.itemName)
        assertEquals(JsonPrimitive("i"), loop.index)
        assertEquals(Action.Type.incrementArrayItem, loop.actions!!.single().type)

        val with = action("""{"type":"with","values":{"count":1},"actions":[{"type":"dismiss"},{"type":"navigation","nextScreen":"next"}]}""")
        assertEquals(Action.Type.with, with.type)
        assertEquals(JsonPrimitive(1), with.values!!["count"])
        assertEquals(listOf(Action.Type.dismiss, Action.Type.navigation), with.actions!!.map { it.type })
    }

    @Test
    fun `request and reloadDatasource`() {
        val r = action(
            """{"type":"request","method":"DELETE","url":"https://api.example.com/v1/items/{{item.id}}",
               "headers":{"Accept":"application/json"},"body":{"note":"{{note}}"},"resultVariable":"lastResponse",
               "onSuccess":[{"type":"reloadDatasource","source":"datasources/items","onError":{"type":"haptic"}}],
               "onError":{"type":"updateVariable","variableName":"errorMessage","value":"{{response.body.message}}"}}""",
        )
        assertEquals("DELETE", r.method)
        assertEquals("lastResponse", r.resultVariable)
        assertEquals(JsonPrimitive("application/json"), r.headers!!["Accept"])
        val reload = r.onSuccess!!.single()
        assertEquals(Action.Type.reloadDatasource, reload.type)
        assertEquals("datasources/items", reload.source)
        assertEquals(Action.Type.haptic, reload.onError!!.single().type)
        assertEquals(Action.Type.updateVariable, r.onError!!.single().type)
    }

    @Test
    fun `array actions accept variable as a short form of variableName`() {
        val a = action("""{"type":"upsertArrayItem","variable":"recent","matchBy":"id","value":{"id":1},"position":"start"}""")
        assertEquals("recent", a.variableName)
        assertEquals("start", a.position)
        assertEquals(JsonPrimitive(-1), action("""{"type":"insertArrayItem","variableName":"l","value":1,"index":-1}""").index)
    }

    @Test
    fun `a color expression is kept, not parsed as hex`() {
        val fill: Fill = DslDecoder().decode("""{"solid":"{{on ? '#FFFFFF' : '#0D1311'}}"}""", ::decodeFill)
        val color = fill.solid.value!!
        assertEquals("{{on ? '#FFFFFF' : '#0D1311'}}", color.expression)
        assertNull(color.light)
        val plain: Fill = DslDecoder().decode("""{"solid":"#FF0000"}""", ::decodeFill)
        assertNull(plain.solid.value!!.expression)
    }

    @Test
    fun `isHidden is read from the properties of any component`() {
        val c = DslDecoder().decode(
            """{"type":"button","id":"b","content":{"properties":{"text":"Go","isHidden":"{{busy}}"}}}""",
            ::decodeComponent,
        ) as ComponentEntity<*>
        assertEquals("{{busy}}", c.isHidden)
        val b = DslDecoder().decode("""{"type":"image","id":"i","content":{"properties":{"type":"none","isHidden":true}}}""", ::decodeComponent) as ComponentEntity<*>
        assertEquals("true", b.isHidden)
    }

    @Test
    fun `native analytics are keyed by event name`() {
        val c = DslDecoder().decode(
            """{"type":"native","id":"n","content":{"properties":{"module":"pad"},
               "analytics":{"completed":{"name":"padCompleted","properties":{"n":"{{event.count}}"}},"cancelled":"padCancelled"}}}""",
            ::decodeComponent,
        ) as ComponentEntity<*>
        val native = c.content as NativeContent
        assertEquals("padCompleted", native.eventAnalytics["completed"]!!.name)
        assertEquals("padCancelled", native.eventAnalytics["cancelled"]!!.name)
    }
}
