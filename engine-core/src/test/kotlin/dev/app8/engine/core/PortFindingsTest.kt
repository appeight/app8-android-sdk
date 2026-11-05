package dev.app8.engine.core

import dev.app8.engine.core.expr.DependencyExtractor
import dev.app8.engine.core.expr.ExpressionEvaluator
import dev.app8.engine.core.expr.ExpressionParser
import dev.app8.engine.core.model.DslDecoder
import dev.app8.engine.core.model.style.Outline
import dev.app8.engine.core.model.style.decodeOutline
import dev.app8.engine.core.model.style.decodeShadow
import dev.app8.engine.core.model.style.decodeShadowLayer
import dev.app8.engine.core.vars.ScopedVariableStore
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableException
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableStoreProtocol
import dev.app8.engine.core.vars.VariableType
import dev.app8.engine.core.vars.actions.VariableAction
import dev.app8.engine.core.vars.actions.VariableActionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of iOS `PortFindingsTests` (app8-ios-sdk#36): engine fixes found while porting to
 * Android, kept in parity on both platforms. Renderer-side items (datePicker storage,
 * stackView clipping, fillsBar search) are covered in the `engine` module tests.
 */
class PortFindingsTest {

    // region #3 resetVariables restores initial values per scope

    private fun resetAction(scope: String?): VariableAction =
        VariableAction.fromJson(if (scope == null) """{"type":"resetVariables"}""" else """{"type":"resetVariables","scope":"$scope"}""")

    /** app (VariableStore) <- screen (Scoped) <- component (Scoped). */
    private fun makeChain(): Triple<VariableStore, ScopedVariableStore, ScopedVariableStore> {
        val app = VariableStore()
        app.defineVariable("cart", VariableDefinition(VariableType.NUMBER, 1))
        val screen = ScopedVariableStore(app)
        screen.defineVariable("email", VariableDefinition(VariableType.STRING, "a@b.c"))
        screen.defineVariable("greeting", VariableDefinition(VariableType.STRING, "{{'hi ' + email}}"))
        screen.defineVariable("emailLength", VariableDefinition(VariableType.NUMBER, computed = "{{email.length}}"))
        val component = ScopedVariableStore(screen)
        component.defineVariable("draft", VariableDefinition(VariableType.STRING, "x"))
        app.setValue("cart", 5)
        screen.setValue("email", "changed@example.com")
        screen.setValue("greeting", "yo")
        component.setValue("draft", "y")
        return Triple(app, screen, component)
    }

    private fun runReset(scope: String?, on: VariableStoreProtocol) =
        VariableActionHandler().execute(resetAction(scope), on, VariableContext(on))

    @Test
    fun resetVariablesScreenScopeRestoresInitialValues() {
        val (app, screen, component) = makeChain()
        assertEquals(19L, screen.getValue("emailLength"))
        runReset("screen", component)
        assertEquals("x", component.getValue("draft"))
        assertEquals("a@b.c", screen.getValue("email"))
        assertEquals("hi a@b.c", screen.getValue("greeting"), "expression initialValue restores its evaluated value")
        assertEquals(5L, screen.getValue("emailLength"), "computed dependents recompute")
        assertEquals(5L, app.getValue("cart"), "screen scope leaves app variables alone")
    }

    @Test
    fun resetVariablesDefaultScopeIsScreen() {
        val (app, screen, _) = makeChain()
        runReset(null, screen)
        assertEquals("a@b.c", screen.getValue("email"))
        assertEquals(5L, app.getValue("cart"))
    }

    @Test
    fun resetVariablesComponentScope() {
        val (_, screen, component) = makeChain()
        runReset("component", component)
        assertEquals("x", component.getValue("draft"))
        assertEquals("changed@example.com", screen.getValue("email"))
    }

    @Test
    fun resetVariablesAppAndAllScopes() {
        var (app, screen, component) = makeChain()
        runReset("app", component)
        assertEquals(1L, app.getValue("cart"))
        assertEquals("changed@example.com", screen.getValue("email"))

        makeChain().let { (a, s, c) -> app = a; screen = s; component = c }
        runReset("all", component)
        assertEquals(1L, app.getValue("cart"))
        assertEquals("a@b.c", screen.getValue("email"))
        assertEquals("x", component.getValue("draft"))
        assertTrue(component.hasVariable("draft"), "reset never deletes variables")
    }

    @Test
    fun resetVariablesSingleStoreScreenScopeResetsThatStore() {
        val app = VariableStore()
        app.defineVariable("n", VariableDefinition(VariableType.NUMBER, 2))
        app.setValue("n", 9)
        runReset(null, app)
        assertEquals(2L, app.getValue("n"))
    }

    @Test
    fun resetVariablesNotifiesChanges() {
        val (_, screen, _) = makeChain()
        val changed = mutableListOf<String>()
        screen.anyVariableChanged.subscribe { changed += it }
        runReset("screen", screen)
        assertTrue("email" in changed && "greeting" in changed && "emailLength" in changed, "$changed")
    }

    // endregion

    // region #4 setMultipleValues failure must not leave the store "updating"

    private fun assertStoreRecoversAfterFailedBatch(store: VariableStoreProtocol) {
        store.defineVariable("a", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("double", VariableDefinition(VariableType.NUMBER, computed = "{{a * 2}}"))
        assertFailsWith<VariableException.UndefinedVariable> { store.setMultipleValues(linkedMapOf("a" to 3, "missing" to 1)) }
        // Failed batch is atomic: nothing applied.
        assertEquals(1L, store.getValue("a"))
        // Later single writes still recompute dependents.
        store.setValue("a", 10)
        assertEquals(20L, store.getValue("double"))
    }

    @Test
    fun setMultipleValuesFailureDoesNotWedgeVariableStore() = assertStoreRecoversAfterFailedBatch(VariableStore())

    @Test
    fun setMultipleValuesFailureDoesNotWedgeScopedStore() = assertStoreRecoversAfterFailedBatch(ScopedVariableStore(VariableStore()))

    @Test
    fun setMultipleValuesScopedValidatesBeforeWritingParent() {
        val app = VariableStore()
        app.defineVariable("p", VariableDefinition(VariableType.NUMBER, 1))
        val scoped = ScopedVariableStore(app)
        scoped.defineVariable("l", VariableDefinition(VariableType.NUMBER, 1))
        assertFailsWith<VariableException.UndefinedVariable> { scoped.setMultipleValues(linkedMapOf("p" to 2, "l" to 2, "zzz" to 3)) }
        assertEquals(1L, app.getValue("p"))
        assertEquals(1L, scoped.getValue("l"))
    }

    @Test
    fun setMultipleValuesTypeMismatchStillRecomputesAndRecovers() {
        val store = VariableStore()
        store.defineVariable("a", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("b", VariableDefinition(VariableType.NUMBER, 1))
        store.defineVariable("double", VariableDefinition(VariableType.NUMBER, computed = "{{a * 2}}"))
        assertFailsWith<VariableException.TypeMismatch> { store.setMultipleValues(linkedMapOf("a" to 3, "b" to "not a number")) }
        // `a` was written before the failure: its dependents were recomputed anyway.
        assertEquals(6L, store.getValue("double"))
        store.setValue("a", 4)
        assertEquals(8L, store.getValue("double"))
    }

    // endregion

    // region #5 / #6 expressions

    private fun eval(expression: String, vars: Map<String, Pair<VariableType, Any>> = emptyMap()): Any? {
        val store = VariableStore()
        for ((name, def) in vars) store.defineVariable(name, VariableDefinition(def.first, def.second))
        val node = ExpressionParser().parse(expression)
        return ExpressionEvaluator().evaluate(node, VariableContext(store))
    }

    @Test
    fun arrayLiteralKeepsNonLiteralElements() {
        assertEquals(listOf<Any?>(1L, 2L, 3L), eval("{{[1, x, 3]}}", mapOf("x" to (VariableType.NUMBER to 2))))
        val nested = eval("{{[x + 1, [x, 'a'], name]}}", mapOf("x" to (VariableType.NUMBER to 2), "name" to (VariableType.STRING to "n"))) as List<*>
        assertEquals(3, nested.size)
        assertEquals(3L, nested[0])
        assertEquals(listOf<Any?>(2L, "a"), nested[1])
        assertEquals("n", nested[2])
    }

    @Test
    fun arrayLiteralNilElementBecomesNull() {
        val store = VariableStore()
        store.defineVariable("list", VariableDefinition(VariableType.ARRAY, listOf(1)))
        // `first` with no match is nil; it must keep its position as null.
        store.defineVariable("missing", VariableDefinition(VariableType.STRING, computed = "{{first(list, item > 5)}}"))
        val value = ExpressionEvaluator().evaluate(ExpressionParser().parse("{{[1, missing, 3]}}"), VariableContext(store)) as List<*>
        assertEquals(3, value.size)
        assertEquals(Values.Null, value[1])
    }

    @Test
    fun arrayLiteralDependenciesAreTracked() {
        val node = ExpressionParser().parse("{{[1, x, y.z]}}")
        assertEquals(setOf("x", "y"), DependencyExtractor().extractDependencies(node))
    }

    @Test
    fun computedArrayLiteralRecomputes() {
        val store = VariableStore()
        store.defineVariable("x", VariableDefinition(VariableType.NUMBER, 2))
        store.defineVariable("list", VariableDefinition(VariableType.ARRAY, computed = "{{[1, x]}}"))
        store.setValue("x", 7)
        assertEquals(listOf<Any?>(1L, 7L), store.getValue("list"))
    }

    @Test
    fun toStringOfCollectionsIsJSON() {
        assertEquals("""[1,"a",true]""", eval("{{toString(list)}}", mapOf("list" to (VariableType.ARRAY to listOf(1, "a", true)))))
        assertEquals("""{"a":"x","b":1}""", eval("{{toString(obj)}}", mapOf("obj" to (VariableType.OBJECT to linkedMapOf("b" to 1, "a" to "x")))))
        assertEquals("[1,2]", eval("{{toString([1, 2])}}"))
        assertEquals("5", eval("{{toString(5)}}"))
        assertEquals("s", eval("{{toString('s')}}"))
        assertEquals("""[null,2.5,"a/b \"q\""]""", eval("""{{toString([null, 2.5, 'a/b "q"'])}}"""))
        assertEquals("""x[1,2]""", eval("{{'x' + [1, 2]}}"))
    }

    // endregion

    // region Spec drift: shadow opacity / isHollow, outline position optional

    private val decoder = DslDecoder()

    @Test
    fun shadowLayerOptionalFieldsUseDocumentedDefaults() {
        val layer = decoder.decode("""{ "color": "#00000040", "radius": 8, "offset": { "x": 0, "y": 4 } }""", ::decodeShadowLayer)
        assertEquals(1.0, layer.opacity)
        assertFalse(layer.isHollow)
    }

    @Test
    fun shadowLayerExplicitFieldsStillWin() {
        val layer = decoder.decode(
            """{ "color": "#000000", "radius": 8, "offset": { "x": 0, "y": 4 }, "opacity": 0.2, "isHollow": true }""",
            ::decodeShadowLayer,
        )
        assertEquals(0.2, layer.opacity, 0.0001)
        assertTrue(layer.isHollow)
    }

    @Test
    fun shadowWithoutOptionalFieldsIsNotDropped() {
        val shadow = decoder.decode("""{ "layers": [{ "color": "#000000", "radius": 4, "offset": { "x": 0, "y": 1 } }] }""", ::decodeShadow)
        assertEquals(1, shadow.layers.size)
    }

    @Test
    fun outlinePositionDefaultsToInside() {
        val outline = decoder.decode("""{ "lineWidth": 2, "fill": { "solid": "#000000" } }""", ::decodeOutline)
        assertEquals(Outline.Position.inside, outline.position)
        assertEquals(2f, outline.lineWidth)
    }

    // endregion
}
