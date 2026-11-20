package dev.app8.engine.render

import dev.app8.engine.App8Error
import dev.app8.engine.core.model.ComponentContent
import dev.app8.engine.runtime.DegradingDecode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A bad value on a screen's own fields drops that value, not the screen. */
class DegradingDecodeTest {
    @Test
    fun `a wrong type on the screen's own fields is dropped and the screen renders`() {
        val screen = """{"type":"screen","id":"s","content":{
          "navigationBar":{"title":5,"hidden":"yes"},
          "onEvent":[{"event":"appear","action":{"type":"dismiss"}},{"event":7}],
          "hidesTabBar":"no",
          "children":[{"type":"label","id":"t","content":{"properties":{"text":"Hi"}}}]}}"""
        val rt = runtime(mapOf("s" to screen))
        val s = session(rt)
        assertEquals("Hi", s.root.find("t")!!.string("Hi"))
        val content = s.content as ComponentContent<*, *>
        assertNull(content.hidesTabBar)
        assertNull(content.navigationBar?.title)
        assertEquals(1, content.onEvent?.size)
        assertTrue(rt.diagnostics.snapshot().count { it.startsWith("decode.dropped") } >= 3, rt.diagnostics.snapshot().toString())
    }

    @Test
    fun `a screen without a usable root still fails cleanly`() {
        val rt = runtime(mapOf("s" to """{"id":"s","content":{}}"""))
        assertFailsWith<App8Error.ScreenDecodeFailed> { session(rt) }
        val rt2 = runtime(mapOf("s" to """[1,2]"""))
        assertFailsWith<App8Error.ScreenDecodeFailed> { session(rt2) }
    }

    @Test
    fun `paths address object keys and array elements`() {
        val json = Json.parseToJsonElement("""{"a":{"b":[{"c":1,"d":2},{"c":3}]}}""")
        assertEquals("""{"a":{"b":[{"d":2},{"c":3}]}}""", DegradingDecode.remove(json, "a.b[0].c").toString())
        assertEquals("""{"a":{"b":[{"c":1,"d":2}]}}""", DegradingDecode.remove(json, "a.b[1]").toString())
        assertNull(DegradingDecode.remove(json, ""))
        assertNull(DegradingDecode.remove(json, "a.x"))
        assertNull(DegradingDecode.remove(json, "a.b[9]"))
        assertNull(DegradingDecode.remove(json, "a.b[x]"))
    }
}
