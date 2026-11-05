package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of the expression-related cases of iOS `HardeningTests` (recursion limits, substring
 * overflow safety, match() ReDoS caps). The ImageLoader / UIFont / deepMerge / JSONValue-decode
 * cases belong to other layers and are not part of this port.
 */
class HardeningTest {
    private val parser = ExpressionParser()
    private val evaluator = ExpressionEvaluator()
    private val context = VariableContext(VariableStore())

    private fun eval(expression: String): Any? = evaluator.evaluate(parser.parse(expression), context)

    @Test
    fun deeplyNestedExpressionParseThrows() {
        val depth = EngineLimits.maxExpressionDepth + 50
        val expr = "(".repeat(depth) + "1" + ")".repeat(depth)
        assertFailsWith<ExpressionException> { parser.parse(expr) }
    }

    @Test
    fun shallowExpressionStillParses() {
        assertEquals(3L, eval("(((1 + 2)))"))
    }

    @Test
    fun deeplyNestedExpressionEvaluateThrows() {
        var node: ExpressionNode = ExpressionNode.Literal(LiteralValue.StringValue("x"))
        repeat(EngineLimits.maxExpressionDepth + 50) { node = ExpressionNode.UnaryOperation("!", node) }
        assertFailsWith<ExpressionException> { evaluator.evaluate(node, context) }
        // The depth counter unwinds after a failure.
        assertEquals(3L, eval("1 + 2"))
    }

    @Test
    fun substringWithNegativeLengthReturnsEmpty() {
        assertEquals("", eval("substring('hello', 10, -5)"))
    }

    @Test
    fun substringWithHugeArgumentsDoesNotTrap() {
        assertEquals("", eval("substring('hello', 99999999999, 99999999999)"))
        assertEquals("ell", eval("substring('hello', 1, 3)"))
    }

    @Test
    fun hasNestedQuantifierDetection() {
        assertTrue(ExpressionEvaluator.hasNestedQuantifier("(a+)+"))
        assertTrue(ExpressionEvaluator.hasNestedQuantifier("(.*)*"))
        assertTrue(ExpressionEvaluator.hasNestedQuantifier("(a+){2,}"))
        assertFalse(ExpressionEvaluator.hasNestedQuantifier("abc"))
        assertFalse(ExpressionEvaluator.hasNestedQuantifier("a+b+"))
        assertFalse(ExpressionEvaluator.hasNestedQuantifier("(ab)+"))
    }

    @Test
    fun matchRejectsCatastrophicPattern() {
        assertEquals(false, eval("match('aaaaaaaaaaaaaaaaaaaaaaaaa!', '(a+)+b')"))
    }

    @Test
    fun matchStillWorksForSafePattern() {
        assertEquals(true, eval("match('hello123', '[0-9]+')"))
        assertEquals(false, eval("match('hello', '[0-9]+')"))
    }

    // Additional: pattern / input size caps.
    @Test
    fun matchRejectsOversizedPatternOrInput() {
        val longPattern = "a".repeat(EngineLimits.maxRegexPatternLength + 1)
        assertEquals(false, eval("match('a', '$longPattern')"))
        val longInput = "a".repeat(EngineLimits.maxRegexInputLength + 1)
        assertEquals(false, eval("match('$longInput', 'a')"))
        assertEquals(true, eval("match('${"a".repeat(EngineLimits.maxRegexInputLength)}', 'a')"))
    }
}
