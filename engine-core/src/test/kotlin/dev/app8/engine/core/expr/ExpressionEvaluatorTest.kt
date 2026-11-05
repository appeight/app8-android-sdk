package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableDefinition
import dev.app8.engine.core.vars.VariableException
import dev.app8.engine.core.vars.VariableStore
import dev.app8.engine.core.vars.VariableType
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Port of iOS `ExpressionEvaluatorTests`. */
class ExpressionEvaluatorTest {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator()
    private lateinit var store: VariableStore
    private lateinit var context: VariableContext

    @BeforeTest
    fun setUp() {
        store = VariableStore()
        store.defineVariable("name", VariableDefinition(VariableType.STRING, "John"))
        store.defineVariable("age", VariableDefinition(VariableType.NUMBER, 25))
        store.defineVariable("isActive", VariableDefinition(VariableType.BOOLEAN, true))
        store.defineVariable("items", VariableDefinition(VariableType.ARRAY, listOf("apple", "banana", "cherry")))
        store.defineVariable(
            "user",
            VariableDefinition(
                VariableType.OBJECT,
                mapOf("firstName" to "Jane", "lastName" to "Doe", "age" to 30, "email" to "jane@example.com"),
            ),
        )
        store.defineVariable("nullVar", VariableDefinition(VariableType.STRING, null))
        store.defineVariable("emptyString", VariableDefinition(VariableType.STRING, ""))
        store.defineVariable("zero", VariableDefinition(VariableType.NUMBER, 0))
        context = VariableContext(store)
    }

    private fun evaluate(expression: String): Any? = evaluator.evaluate(parser.parse(expression), context)

    @Test
    fun evaluateLiterals() {
        assertEquals(true, evaluate("true"))
        assertEquals(false, evaluate("false"))
        assertEquals(42L, evaluate("42"))
        assertEquals(3.14, evaluate("3.14"))
        assertEquals("hello", evaluate("'hello'"))
        assertSame(Values.Null, evaluate("null"))
    }

    @Test
    fun evaluateVariables() {
        assertEquals("John", evaluate("name"))
        assertEquals(25L, evaluate("age"))
        assertEquals(true, evaluate("isActive"))
        assertEquals(listOf("apple", "banana", "cherry"), evaluate("items"))
    }

    @Test
    fun evaluateUndefinedVariable() {
        assertFailsWith<VariableException> { evaluate("undefinedVar") }
    }

    @Test
    fun evaluateArithmetic() {
        assertEquals(5L, evaluate("2 + 3"))
        assertEquals(6L, evaluate("10 - 4"))
        assertEquals(12L, evaluate("3 * 4"))
        assertEquals(5.0, evaluate("15 / 3"))
        assertEquals(30L, evaluate("age + 5"))
        assertEquals(50L, evaluate("age * 2"))
    }

    @Test
    fun evaluateStringConcatenation() {
        assertEquals("Hello World", evaluate("'Hello' + ' ' + 'World'"))
        assertEquals("John is 25 years old", evaluate("name + ' is ' + age + ' years old'"))
    }

    @Test
    fun parseBareExpression() {
        assertEquals("John", evaluate("{{name}}"))
        assertEquals(25L, evaluate("{{age}}"))
    }

    @Test
    fun parseInterpolatedStringWithPrefix() {
        assertEquals("Hello, John!", evaluate("Hello, {{name}}!"))
    }

    @Test
    fun parseInterpolatedStringWithSuffix() {
        assertEquals("John says hi", evaluate("{{name}} says hi"))
    }

    @Test
    fun parseCompoundInterpolation() {
        assertEquals("John is 25", evaluate("{{name}} is {{age}}"))
    }

    @Test
    fun parseInterpolatedWithMemberAccess() {
        assertEquals("Jane Doe", evaluate("{{user.firstName}} {{user.lastName}}"))
    }

    @Test
    fun evaluateComparison() {
        assertEquals(true, evaluate("5 > 3"))
        assertEquals(true, evaluate("2 >= 2"))
        assertEquals(false, evaluate("10 < 5"))
        assertEquals(true, evaluate("3 <= 3"))
        assertEquals(true, evaluate("age > 20"))
        assertEquals(true, evaluate("age <= 25"))
    }

    @Test
    fun evaluateEquality() {
        assertEquals(true, evaluate("5 === 5"))
        assertEquals(true, evaluate("'hello' === 'hello'"))
        assertEquals(true, evaluate("true === true"))
        assertEquals(false, evaluate("5 === '5'"))
        assertEquals(true, evaluate("5 !== '5'"))
        assertEquals(false, evaluate("null !== null"))
        assertEquals(true, evaluate("5 == '5'"))
        assertEquals(true, evaluate("null == null"))
        assertEquals(true, evaluate("name === 'John'"))
        assertEquals(true, evaluate("age !== 30"))
    }

    @Test
    fun evaluateLogical() {
        assertEquals(true, evaluate("true && true"))
        assertEquals(false, evaluate("true && false"))
        assertEquals(true, evaluate("false || true"))
        assertEquals(false, evaluate("false || false"))
        assertEquals(false, evaluate("false && undefinedVar"))
        assertEquals(true, evaluate("true || undefinedVar"))
        assertEquals(true, evaluate("isActive && age > 20"))
        assertEquals(true, evaluate("name === 'Jane' || age === 25"))
    }

    @Test
    fun evaluateUnary() {
        assertEquals(false, evaluate("!true"))
        assertEquals(true, evaluate("!false"))
        assertEquals(true, evaluate("!!true"))
        assertEquals(-5L, evaluate("-5"))
        assertEquals(-25L, evaluate("-age"))
        assertEquals(false, evaluate("!isActive"))
    }

    @Test
    fun evaluateMemberAccess() {
        assertEquals("Jane", evaluate("user.firstName"))
        assertEquals(30L, evaluate("user.age"))
        assertEquals(3L, evaluate("items.length"))
        assertEquals(4L, evaluate("name.length"))
        assertNull(evaluate("user.undefined"))
    }

    @Test
    fun evaluateArrayAccess() {
        assertEquals("apple", evaluate("items[0]"))
        assertEquals("banana", evaluate("items[1]"))
        assertEquals("cherry", evaluate("items[2]"))
        assertNull(evaluate("items[10]"))
        assertNull(evaluate("items[-1]"))
    }

    @Test
    fun evaluateFunctionCalls() {
        assertEquals(3L, evaluate("length(items)"))
        assertEquals(5L, evaluate("length('hello')"))
        assertEquals(true, evaluate("includes(items, 'banana')"))
        assertEquals(false, evaluate("includes(items, 'orange')"))
        assertEquals(true, evaluate("includes(name, 'oh')"))
        assertEquals(true, evaluate("includes('hello', '')"))
        assertEquals(true, evaluate("includes(emptyString, '')"))
        assertEquals(true, evaluate("includes(name, '')"))
        assertEquals(true, evaluate("match(user.email, '^[^\\\\s@]+@[^\\\\s@]+\\\\.[^\\\\s@]+$')"))
        assertEquals(false, evaluate("match('invalid-email', '^[^\\\\s@]+@[^\\\\s@]+\\\\.[^\\\\s@]+$')"))
        assertEquals(123L, evaluate("parseInt('123')"))
        assertEquals(3.14, evaluate("parseFloat('3.14')"))
        assertEquals("42", evaluate("toString(42)"))
        assertEquals("true", evaluate("toString(true)"))
        assertEquals(true, evaluate("isArray(items)"))
        assertEquals(false, evaluate("isArray(name)"))
    }

    @Test
    fun evaluateTernary() {
        assertEquals("yes", evaluate("true ? 'yes' : 'no'"))
        assertEquals("no", evaluate("false ? 'yes' : 'no'"))
        assertEquals("adult", evaluate("age > 18 ? 'adult' : 'minor'"))
        assertEquals(25L, evaluate("name === 'John' ? age : 0"))
    }

    @Test
    fun evaluateComplexExpressions() {
        assertEquals(60L, evaluate("(age + 5) * 2"))
        assertEquals(true, evaluate("isActive && (age > 20 || name === 'Admin')"))
        assertEquals("adult", evaluate("age > 60 ? 'senior' : age > 18 ? 'adult' : 'minor'"))
        assertEquals("Jane Doe", evaluate("user.firstName + ' ' + user.lastName"))
        assertEquals(false, evaluate("!!emptyString"))
        assertEquals(true, evaluate("!!name"))
        assertEquals(false, evaluate("!!zero"))
        assertEquals(false, evaluate("!!nullVar"))
    }

    @Test
    fun dependencyExtraction() {
        val extractor = DependencyExtractor()
        assertEquals(setOf("name"), extractor.extractDependencies(parser.parse("name")))
        assertEquals(setOf("age"), extractor.extractDependencies(parser.parse("age > 18")))
        assertEquals(
            setOf("isActive", "age", "name"),
            extractor.extractDependencies(parser.parse("isActive && (age > 20 || name === 'Admin')")),
        )
        assertEquals(setOf("user"), extractor.extractDependencies(parser.parse("user.firstName + ' ' + user.lastName")))
        assertEquals(setOf("items", "index"), extractor.extractDependencies(parser.parse("items[index]")))
    }

    @Test
    fun evaluateModulo() {
        assertEquals(1L, evaluate("10 % 3"))
        assertEquals(0L, evaluate("15 % 5"))
        assertEquals(4L, evaluate("age % 7"))
    }

    @Test
    fun evaluateNullComparisons() {
        assertEquals(true, evaluate("null === null"))
        assertEquals(true, evaluate("null == null"))
        assertEquals(false, evaluate("nullVar === null")) // nullVar defaults to "", not NSNull
    }

    @Test
    fun evaluateEmptyArrayAndString() {
        assertEquals(true, evaluate("!emptyString"))
        assertEquals(0L, evaluate("length(emptyString)"))
    }

    @Test
    fun stringUppercase() {
        assertEquals("HELLO", evaluate("uppercase('hello')"))
        assertEquals("JOHN", evaluate("uppercase(name)"))
    }

    @Test
    fun stringLowercase() {
        assertEquals("hello", evaluate("lowercase('HELLO')"))
        assertEquals("mixed", evaluate("lowercase('MixED')"))
    }

    @Test
    fun stringTrim() {
        assertEquals("hello", evaluate("trim('  hello  ')"))
        assertEquals("", evaluate("trim('   ')"))
        assertEquals("no-whitespace", evaluate("trim('no-whitespace')"))
    }

    @Test
    fun stringReplace() {
        assertEquals("hello there", evaluate("replace('hello world', 'world', 'there')"))
        assertEquals("bbb", evaluate("replace('aaa', 'a', 'b')"))
        assertEquals("foo", evaluate("replace('foo', '', 'bar')"))
    }

    @Test
    fun stringSplit() {
        assertEquals(listOf("a", "b", "c"), evaluate("split('a,b,c', ',')"))
        assertEquals(listOf("hello", "world"), evaluate("split('hello world', ' ')"))
        assertEquals(listOf("a", "b", "c"), evaluate("split('abc', '')"))
    }

    @Test
    fun stringSubstring() {
        assertEquals("ell", evaluate("substring('hello', 1, 3)"))
        assertEquals("llo", evaluate("substring('hello', 2)"))
        assertEquals("hi", evaluate("substring('hi', 0, 10)"))
        assertEquals("", evaluate("substring('hi', 10)"))
    }

    @Test
    fun stringStartsWith() {
        assertEquals(true, evaluate("startsWith('hello world', 'hello')"))
        assertEquals(false, evaluate("startsWith('hello', 'world')"))
        assertEquals(true, evaluate("startsWith('foo', '')"))
    }

    @Test
    fun stringEndsWith() {
        assertEquals(true, evaluate("endsWith('hello.png', '.png')"))
        assertEquals(false, evaluate("endsWith('hello.jpg', '.png')"))
    }

    @Test
    fun arraySort() {
        assertEquals(listOf("apple", "banana", "cherry"), evaluate("sort(items)"))
        assertEquals(listOf("cherry", "banana", "apple"), evaluate("sort(items, false)"))
    }

    @Test
    fun arraySortNumbers() {
        store.defineVariable("nums", VariableDefinition(VariableType.ARRAY, listOf(3, 1, 4, 1, 5, 9, 2, 6)))
        assertEquals(listOf(1L, 1L, 2L, 3L, 4L, 5L, 6L, 9L), evaluate("sort(nums)"))
    }

    @Test
    fun arrayReverse() {
        assertEquals(listOf("cherry", "banana", "apple"), evaluate("reverse(items)"))
    }

    @Test
    fun arrayJoin() {
        assertEquals("apple, banana, cherry", evaluate("join(items, ', ')"))
        assertEquals("applebananacherry", evaluate("join(items, '')"))
    }

    @Test
    fun arrayConcat() {
        store.defineVariable("more", VariableDefinition(VariableType.ARRAY, listOf("date", "elderberry")))
        val result = evaluate("concat(items, more)") as List<*>
        assertEquals(5, result.size)
        assertEquals("apple", result[0])
        assertEquals("elderberry", result[4])
    }

    @Test
    fun arraySlice() {
        assertEquals(listOf("banana", "cherry"), evaluate("slice(items, 1)"))
        assertEquals(listOf("apple", "banana"), evaluate("slice(items, 0, 2)"))
        assertEquals(listOf("apple", "banana", "cherry"), evaluate("slice(items, 0, 10)"))
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun int_and_double_stay_distinct() {
        assertEquals(false, evaluate("1 === 1.0"))
        assertEquals(false, evaluate("2 / 2 === 1"))
        assertEquals("2.0", evaluate("toString(4 / 2)"))
        assertEquals(3L, evaluate("1.5 + 1.5"))
    }

    @Test
    fun booleans_coerce_to_numbers_like_nsnumber_bridging() {
        assertEquals(2L, evaluate("true + true"))
        assertEquals(true, evaluate("1 == true"))
        assertEquals(-1L, evaluate("-true"))
    }

    @Test
    fun nil_and_explicit_null_are_distinct() {
        assertEquals(false, evaluate("user.missing === null"))
        assertEquals(true, evaluate("user.missing == null"))
        assertEquals(true, evaluate("'' == null"))
    }

    @Test
    fun collections_in_string_context_render_like_swift_optional_description() {
        assertEquals("x[\"apple\",\"banana\",\"cherry\"]", evaluate("'x' + items"))
        assertEquals("apple|1|2.5|", evaluate("join(['apple', 1, 2.5, null], '|')"))
    }

    @Test
    fun grapheme_cluster_semantics_for_length_and_substring() {
        store.defineVariable("emoji", VariableDefinition(VariableType.STRING, "👍🏽ok"))
        assertEquals(3L, evaluate("length(emoji)"))
        assertEquals("👍🏽", evaluate("substring(emoji, 0, 1)"))
        assertEquals(listOf("👍🏽", "o", "k"), evaluate("split(emoji, '')"))
    }

    @Test
    fun loop_variable_item_shadows_store_variable() {
        store.defineVariable("item", VariableDefinition(VariableType.STRING, "outer"))
        assertEquals(listOf("apple!", "banana!", "cherry!"), evaluate("map(items, item + '!')"))
        assertEquals("outer", evaluate("item"))
    }

    @Test
    fun event_is_a_soft_undefined() {
        assertNull(evaluate("event"))
    }

    @Test
    fun disallowed_function_fails() {
        val e = assertFailsWith<ExpressionException.ParseError> { evaluate("eval('1')") }
        assertEquals("Function 'eval' is not allowed", e.message)
    }

    @Test
    fun i18n_uses_lookup_and_falls_back_to_key() {
        assertEquals("k", evaluate("i18n('k')"))
        evaluator.translationLookup = { if (it == "k") "translated" else null }
        assertEquals("translated", evaluate("i18n('k')"))
        assertEquals("other", evaluate("i18n('other')"))
    }

    @Test
    fun has_nested_quantifier_guards_redos() {
        assertFalse(ExpressionEvaluator.hasNestedQuantifier("(\\(a+\\))"))
        assertEquals(false, evaluate("match('aaaa', '(a*)*')"))
    }

    // endregion
}

/** Port of iOS `HigherOrderArrayFunctionTests`. */
class HigherOrderArrayFunctionTest {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator()
    private val store = VariableStore().apply {
        val products = listOf(
            mapOf("id" to 1, "name" to "Shirt", "price" to 35, "active" to true, "categoryId" to 10, "sellerId" to 100),
            mapOf("id" to 2, "name" to "Jeans", "price" to 80, "active" to true, "categoryId" to 20, "sellerId" to 200),
            mapOf("id" to 3, "name" to "Jacket", "price" to 120, "active" to false, "categoryId" to 10, "sellerId" to 100),
            mapOf("id" to 4, "name" to "Dress", "price" to 60, "active" to true, "categoryId" to 20, "sellerId" to 300),
        )
        defineVariable("products", VariableDefinition(VariableType.ARRAY, products))
        defineVariable("emptyArray", VariableDefinition(VariableType.ARRAY, emptyList<Any>()))
        defineVariable("selectedCategory", VariableDefinition(VariableType.OBJECT, mapOf("id" to 10)))
        defineVariable("currentSeller", VariableDefinition(VariableType.OBJECT, mapOf("id" to 100)))
        defineVariable("minPrice", VariableDefinition(VariableType.NUMBER, 50))
        defineVariable("maxPrice", VariableDefinition(VariableType.NUMBER, 100))
    }
    private val context = VariableContext(store)

    private fun evaluate(expression: String): Any? = evaluator.evaluate(parser.parse(expression), context)

    @Suppress("UNCHECKED_CAST")
    private fun objects(expression: String): List<Map<String, Any?>> = evaluate(expression) as List<Map<String, Any?>>

    @Test
    fun filterNoPredicateReturnsAll() {
        assertEquals(4, (evaluate("filter(products)") as List<*>).size)
    }

    @Test
    fun filterByBooleanField() {
        val result = objects("filter(products, item.active == true)")
        assertEquals(3, result.size)
        assert(result.all { it["active"] == true })
    }

    @Test
    fun filterByNumericComparison() {
        val result = objects("filter(products, item.price > 50)")
        assertEquals(3, result.size)
        assert(result.all { (it["price"] as Long) > 50 })
    }

    @Test
    fun filterByRelatedObjectField() {
        val result = objects("filter(products, item.categoryId == selectedCategory.id)")
        assertEquals(2, result.size)
        assert(result.all { it["categoryId"] == 10L })
    }

    @Test
    fun filterCompoundPredicateWithOuterVars() {
        val result = objects("filter(products, item.price > minPrice && item.price < maxPrice)")
        assertEquals(2, result.size)
        assert(result.all { (it["price"] as Long) in 51..99 })
    }

    @Test
    fun filterByAnotherObjectId() {
        val result = objects("filter(products, item.sellerId == currentSeller.id)")
        assertEquals(2, result.size)
        assert(result.all { it["sellerId"] == 100L })
    }

    @Test
    fun filterEmptyArrayReturnsEmpty() {
        assertEquals(0, (evaluate("filter(emptyArray, item.active == true)") as List<*>).size)
    }

    @Test
    fun firstNoPredicateReturnsFirstElement() {
        assertEquals(1L, (evaluate("first(products)") as Map<*, *>)["id"])
    }

    @Test
    fun firstWithPredicate() {
        assertEquals(3L, (evaluate("first(products, item.active == false)") as Map<*, *>)["id"])
    }

    @Test
    fun firstOnEmptyArrayReturnsNil() {
        assertNull(evaluate("first(emptyArray)"))
    }

    @Test
    fun firstWithNoMatchReturnsNil() {
        assertNull(evaluate("first(products, item.price > 999)"))
    }

    @Test
    fun findById() {
        assertEquals("Jeans", (evaluate("find(products, item.id == 2)") as Map<*, *>)["name"])
    }

    @Test
    fun mapExtractsField() {
        assertEquals(listOf("Shirt", "Jeans", "Jacket", "Dress"), evaluate("map(products, item.name)"))
    }

    @Test
    fun mapNoPredicateReturnsAll() {
        assertEquals(4, (evaluate("map(products)") as List<*>).size)
    }

    @Test
    fun filterDepsExcludesItemIncludesArray() {
        val deps = DependencyExtractor().extractDependencies(parser.parse("filter(products, item.price > minPrice)"))
        assertEquals(setOf("products", "minPrice"), deps)
        assertFalse("item" in deps)
    }

    @Test
    fun filterDepsWithRelatedObject() {
        val deps = DependencyExtractor().extractDependencies(parser.parse("filter(products, item.categoryId == selectedCategory.id)"))
        assertEquals(setOf("products", "selectedCategory"), deps)
        assertFalse("item" in deps)
    }

    // Additional: `last` is not treated as higher-order by the extractor (faithful to iOS).
    @Test
    fun lastPredicateDepsIncludeItem() {
        val deps = DependencyExtractor().extractDependencies(parser.parse("last(products, item.active)"))
        assertEquals(setOf("products", "item"), deps)
    }
}

/** Port of iOS `InitialValueExpressionTests`. */
class InitialValueExpressionTest {
    private val dogs = listOf(
        mapOf("id" to "dog-1", "name" to "Buddy", "breed" to "Labrador"),
        mapOf("id" to "dog-2", "name" to "Max", "breed" to "Poodle"),
    )

    @Test
    fun initialValueExpressionIsEvaluated() {
        val store = VariableStore()
        store.defineVariables(
            mapOf(
                "dogs" to VariableDefinition(VariableType.ARRAY, dogs),
                "selectedDogId" to VariableDefinition(VariableType.STRING, "{{first(dogs).id}}"),
            ),
        )
        assertEquals("dog-1", store.getValue("selectedDogId"))
    }

    @Test
    fun computedVarDependingOnExpressionInitialValue() {
        val store = VariableStore()
        store.defineVariables(
            mapOf(
                "dogs" to VariableDefinition(VariableType.ARRAY, dogs),
                "selectedDogId" to VariableDefinition(VariableType.STRING, "{{first(dogs).id}}"),
                "selectedDog" to VariableDefinition(VariableType.OBJECT, computed = "{{first(dogs, item.id == selectedDogId)}}"),
            ),
        )
        val dog = store.getValue("selectedDog") as Map<*, *>
        assertEquals("Buddy", dog["name"])
        assertEquals("Labrador", dog["breed"])
    }

    @Test
    fun nilComputedVarReturnsNilNotThrows() {
        val store = VariableStore()
        store.defineVariables(
            mapOf(
                "dogs" to VariableDefinition(VariableType.ARRAY, dogs.take(1)),
                "selectedDogId" to VariableDefinition(VariableType.STRING, "no-match"),
                "selectedDog" to VariableDefinition(VariableType.OBJECT, computed = "{{first(dogs, item.id == selectedDogId)}}"),
            ),
        )
        assertNull(store.getValue("selectedDog"))
        val result = ExpressionEvaluator().evaluate(ExpressionParser().parse("{{selectedDog.name}}"), VariableContext(store))
        assertNull(result)
    }

    // Additional: a failing initialValue expression keeps the literal string (error is logged).
    @Test
    fun failingInitialValueExpressionKeepsRawString() {
        val store = VariableStore()
        store.defineVariable("x", VariableDefinition(VariableType.STRING, "{{missing}}"))
        assertEquals("{{missing}}", store.getValue("x"))
    }
}
