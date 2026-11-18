package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.PropertyResolver
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Array and object literals in expressions (app8-dsl docs/variables.md, "Array and Object Literals"). */
class ExpressionLiteralTest {
    private val store = VariableStore().apply {
        defineVariable("newCount", VariableDefinition(VariableType.NUMBER, 3L))
        defineVariable("items", VariableDefinition(VariableType.ARRAY, listOf(1L, 2L)))
        defineVariable("name", VariableDefinition(VariableType.STRING, "Ann"))
    }
    private val resolver = PropertyResolver()
    private fun r(s: String): Any? = resolver.resolve(s, VariableContext(store))

    @Test
    fun objectAndArrayLiteralsAreValues() {
        assertEquals(listOf(mapOf("title" to "A"), mapOf("title" to "B")), r("""{{[ {"title": "A"}, {"title": "B"} ]}}"""))
        assertEquals(
            listOf(mapOf("title" to "New", "count" to 3L), mapOf("title" to "All", "count" to 2L)),
            r("{{[ {'title': 'New', 'count': newCount}, {title: 'All', count: length(items)} ]}}"),
        )
        assertEquals(emptyMap<String, Any?>(), r("{{ {} }}"))
        assertEquals(mapOf("a" to mapOf("b" to listOf(1L, mapOf("c" to "Ann")))), r("{{ {'a': {'b': [1, {'c': name}]}} }}"))
        assertEquals(mapOf("a" to 1L), r("{{{'a': 1}}}"), "no spaces around the braces")
        assertEquals(1L, r("{{ {'a': {'b': 1}}.a.b }}"))
        assertEquals("}}", r("{{ {'k': '}}'}.k }}"), "a }} inside quotes does not end the expression")
    }

    @Test
    fun interpolationAfterANestedLiteral() {
        assertEquals("x 2 y Ann", resolver.resolveToString("x {{ {'a': {'b': 2}}.a.b }} y {{name}}", VariableContext(store)))
    }

    @Test
    fun objectLiteralValuesAreDependencies() {
        val deps = DependencyExtractor().extractDependencies(ExpressionParser().parse("{{ {'n': newCount, 'm': {k: name}} }}"))
        assertEquals(setOf("newCount", "name"), deps.toSet())
    }

    @Test
    fun syntaxErrors() {
        assertFailsWith<Exception> { ExpressionParser().parse("{{ {'a': 1,} }}") }
        assertFailsWith<Exception> { ExpressionParser().parse("{{ {1: 2} }}") }
    }
}
