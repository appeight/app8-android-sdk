package dev.app8.engine.core.vars.actions

import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** DSL 0.6.0 `insertArrayItem`, `upsertArrayItem`, `removeArrayItem` and the null-key rule of `incrementArrayItem`. */
class ArrayActionsTest {
    private lateinit var store: VariableStore
    private val handler = VariableActionHandler()

    @BeforeTest
    fun setUp() {
        store = VariableStore()
        store.defineVariable("list", VariableDefinition(VariableType.ARRAY, listOf("a", "b", "c")))
        store.defineVariable(
            "cart",
            VariableDefinition(
                VariableType.ARRAY,
                listOf(mapOf("key" to "x", "quantity" to 1L, "note" to "keep?"), mapOf("key" to "y", "quantity" to 2L), mapOf("key" to "x", "quantity" to 5L)),
            ),
        )
        store.defineVariable("empty", VariableDefinition(VariableType.ARRAY))
        store.defineVariable("pos", VariableDefinition(VariableType.NUMBER, 1L))
        store.defineVariable("missing", VariableDefinition(VariableType.STRING))
    }

    private fun run(json: String) = handler.execute(VariableAction.fromJson(json), store, VariableContext(store))

    @Suppress("UNCHECKED_CAST")
    private fun keys(name: String) = (store.getValue(name) as List<Map<String, Any?>>).map { it["key"] }

    @Test
    fun `insert defaults to the start, clamps, and counts negative indexes from the end`() {
        run("""{"type":"insertArrayItem","variableName":"list","value":"s"}""")
        assertEquals(listOf("s", "a", "b", "c"), store.getValue("list"))
        run("""{"type":"insertArrayItem","variableName":"list","value":"e","index":-1}""")
        assertEquals(listOf("s", "a", "b", "c", "e"), store.getValue("list"))
        run("""{"type":"insertArrayItem","variableName":"list","value":"p","index":-2}""")
        assertEquals(listOf("s", "a", "b", "c", "p", "e"), store.getValue("list"))
        run("""{"type":"insertArrayItem","variableName":"list","value":"big","index":99}""")
        assertEquals("big", (store.getValue("list") as List<*>).last())
        run("""{"type":"insertArrayItem","variableName":"list","value":"neg","index":-99}""")
        assertEquals("neg", (store.getValue("list") as List<*>).first())
        run("""{"type":"insertArrayItem","variableName":"list","value":"at1","index":"{{pos}}"}""")
        assertEquals("at1", (store.getValue("list") as List<*>)[1])
        run("""{"type":"insertArrayItem","variableName":"empty","value":{"id":"{{pos}}"}}""")
        assertEquals(listOf(mapOf("id" to 1L)), store.getValue("empty"), "a null variable counts as an empty array")
    }

    @Test
    fun `upsert replaces the first match exactly or inserts at position`() {
        run("""{"type":"upsertArrayItem","variableName":"cart","matchBy":"key","value":{"key":"x","quantity":9}}""")
        @Suppress("UNCHECKED_CAST")
        val cart = store.getValue("cart") as List<Map<String, Any?>>
        assertEquals(mapOf("key" to "x", "quantity" to 9L), cart[0], "replaced exactly, other fields dropped")
        assertEquals(5L, cart[2]["quantity"], "only the first match")
        run("""{"type":"upsertArrayItem","variableName":"cart","matchBy":"key","value":{"key":"z"},"position":"start"}""")
        assertEquals(listOf("z", "x", "y", "x"), keys("cart"))
        run("""{"type":"upsertArrayItem","variableName":"cart","matchBy":"key","value":{"key":"w"}}""")
        assertEquals(listOf("z", "x", "y", "x", "w"), keys("cart"))
        run("""{"type":"upsertArrayItem","variableName":"cart","matchBy":"key","value":{"other":1}}""")
        assertEquals(5, (store.getValue("cart") as List<*>).size, "a missing match key is a no-op")
    }

    @Test
    fun `remove by key removes every match, by index removes one`() {
        run("""{"type":"removeArrayItem","variableName":"cart","matchBy":"key","value":"x"}""")
        assertEquals(listOf("y"), keys("cart"))
        setUp()
        run("""{"type":"removeArrayItem","variableName":"cart","matchBy":"key","value":{"key":"y","quantity":2}}""")
        assertEquals(listOf("x", "x"), keys("cart"), "an object value uses its own key")
        run("""{"type":"removeArrayItem","variableName":"list","index":-1}""")
        assertEquals(listOf("a", "b"), store.getValue("list"))
        run("""{"type":"removeArrayItem","variableName":"list","index":7}""")
        assertEquals(listOf("a", "b"), store.getValue("list"), "out of range: no-op")
        run("""{"type":"removeArrayItem","variableName":"list","index":0,"matchBy":"key","value":"b"}""")
        assertEquals(listOf("b"), store.getValue("list"), "index wins over matchBy")
        run("""{"type":"removeArrayItem","variableName":"cart","matchBy":"key","value":"{{null}}"}""")
        assertEquals(listOf("x", "x"), keys("cart"), "a null key is a no-op")
    }

    @Test
    fun `incrementArrayItem with a null key does nothing`() {
        run("""{"type":"incrementArrayItem","variableName":"cart","matchBy":"key","value":{"key":"{{null}}","quantity":1}}""")
        run("""{"type":"incrementArrayItem","variableName":"cart","matchBy":"key","value":"{{null}}"}""")
        assertEquals(listOf("x", "y", "x"), keys("cart"))
    }
}
