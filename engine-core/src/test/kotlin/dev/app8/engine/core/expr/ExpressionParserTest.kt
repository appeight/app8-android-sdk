package dev.app8.engine.core.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

/** Port of iOS `ExpressionParserTests`. */
class ExpressionParserTest {
    private val parser = ExpressionParser()

    @Test
    fun parseLiterals() {
        assertEquals(".literal(true)", describe("true"))
        assertEquals(".literal(false)", describe("false"))
        assertEquals(".literal(42)", describe("42"))
        assertEquals(".literal(3.14)", describe("3.14"))
        assertEquals(".literal(hello)", describe("'hello'"))
        assertEquals(".literal(world)", describe("\"world\""))
        assertEquals(".literal(null)", describe("null"))

        val node = parser.parse("[1, 2, 3]")
        val literal = node as? ExpressionNode.Literal ?: fail("Expected array literal")
        assertEquals(3, (literal.value.anyValue as List<*>).size)
    }

    @Test
    fun parseVariables() {
        assertEquals(".variable(myVariable)", describe("myVariable"))
        assertEquals(".variable(_private)", describe("_private"))
        assertEquals(".variable(var123)", describe("var123"))
    }

    @Test
    fun parseArithmetic() {
        assertEquals(".binaryOperation(+)", describe("1 + 2"))
        assertEquals(".binaryOperation(-)", describe("a - b"))
        assertEquals(".binaryOperation(*)", describe("x * y"))
        assertEquals(".binaryOperation(/)", describe("10 / 2"))
    }

    @Test
    fun parseComparison() {
        assertEquals(".binaryOperation(>)", describe("a > b"))
        assertEquals(".binaryOperation(>=)", describe("x >= y"))
        assertEquals(".binaryOperation(<)", describe("m < n"))
        assertEquals(".binaryOperation(<=)", describe("p <= q"))
    }

    @Test
    fun parseEquality() {
        assertEquals(".binaryOperation(===)", describe("a === b"))
        assertEquals(".binaryOperation(!==)", describe("x !== y"))
        assertEquals(".binaryOperation(==)", describe("m == n"))
        assertEquals(".binaryOperation(!=)", describe("p != q"))
    }

    @Test
    fun parseLogical() {
        assertEquals(".binaryOperation(&&)", describe("a && b"))
        assertEquals(".binaryOperation(||)", describe("x || y"))
    }

    @Test
    fun parseUnary() {
        assertEquals(".unaryOperation(!)", describe("!isValid"))
        assertEquals(".unaryOperation(-)", describe("-count"))
        assertEquals(".unaryOperation(!)", describe("!!value"))
    }

    @Test
    fun parseMemberAccess() {
        assertEquals(".memberAccess(name)", describe("user.name"))
        assertEquals(".memberAccess(length)", describe("data.items.length"))
    }

    @Test
    fun parseArrayAccess() {
        assertEquals(".arrayAccess", describe("items[0]"))
        assertEquals(".arrayAccess", describe("matrix[i][j]"))
    }

    @Test
    fun parseFunctionCall() {
        assertEquals(".functionCall(length)", describe("length()"))
        assertEquals(".functionCall(includes)", describe("items.includes('test')"))
        assertEquals(".functionCall(parseInt)", describe("parseInt('123')"))
    }

    @Test
    fun parseTernary() {
        assertEquals(".ternary", describe("a ? b : c"))
        assertEquals(".ternary", describe("x > 0 ? 'positive' : 'negative'"))
    }

    @Test
    fun parseComplexExpressions() {
        assertEquals("+", (parser.parse("a + b * c") as ExpressionNode.BinaryOperation).operator)
        assertEquals("*", (parser.parse("(a + b) * c") as ExpressionNode.BinaryOperation).operator)
        parser.parse("a ? b ? c : d : e")
        parser.parse("isValid && (count > 0 || hasDefault)")
    }

    @Test
    fun parseWithBrackets() {
        assertEquals(".variable(myVar)", describe("{{myVar}}"))
        assertEquals(".binaryOperation(+)", describe("{{ a + b }}"))
    }

    @Test
    fun parseErrors() {
        for (expr in listOf("", "{{}}", "a +", "(a + b", "a ? b", "'unterminated string", "@invalid")) {
            assertFailsWith<ExpressionException>(expr) { parser.parse(expr) }
        }
    }

    // region Additional coverage (not in the Swift suite)

    @Test
    fun method_call_syntax_prepends_receiver_as_first_argument() {
        val node = parser.parse("items.includes('x')") as ExpressionNode.FunctionCall
        assertEquals(ExpressionNode.Variable("items"), node.arguments[0])
        assertEquals(2, node.arguments.size)
    }

    @Test
    fun interpolated_template_compiles_to_left_associative_plus_chain() {
        val node = parser.parse("a{{x}}b{{y}}")
        // ((("a" + x) + "b") + y)
        val top = node as ExpressionNode.BinaryOperation
        assertEquals(ExpressionNode.Variable("y"), top.right)
        val inner = top.left as ExpressionNode.BinaryOperation
        assertEquals(ExpressionNode.Literal(LiteralValue.StringValue("b")), inner.right)
    }

    @Test
    fun array_literal_keeps_non_literal_elements() {
        val node = parser.parse("[1, x, 3]") as ExpressionNode.ArrayLiteral
        assertEquals(
            listOf(ExpressionNode.Literal(LiteralValue.IntValue(1)), ExpressionNode.Variable("x"), ExpressionNode.Literal(LiteralValue.IntValue(3))),
            node.elements,
        )
        assertEquals(ExpressionNode.Literal(LiteralValue.ArrayValue(listOf(LiteralValue.IntValue(1)))), parser.parse("[1]"))
    }

    @Test
    fun number_literals_distinguish_int_and_double() {
        assertEquals(ExpressionNode.Literal(LiteralValue.IntValue(7)), parser.parse("007"))
        assertEquals(ExpressionNode.Literal(LiteralValue.DoubleValue(1.0)), parser.parse("1."))
        // Int overflow parses to 0, as Swift's `Int(s) ?? 0`.
        assertEquals(ExpressionNode.Literal(LiteralValue.IntValue(0)), parser.parse("99999999999999999999"))
    }

    @Test
    fun error_types_and_positions() {
        assertIs<ExpressionException.InvalidCharacter>(runCatching { parser.parse("a = b") }.exceptionOrNull()).also {
            assertEquals(2, it.position)
            assertEquals("Invalid character '=' at position 2", it.message)
        }
        assertIs<ExpressionException.UnexpectedToken>(runCatching { parser.parse("1 +") }.exceptionOrNull())
        assertIs<ExpressionException.ExpectedIdentifier>(runCatching { parser.parse("a.0") }.exceptionOrNull())
        assertIs<ExpressionException.InvalidFunctionCall>(runCatching { parser.parse("(1 + 2)(3)") }.exceptionOrNull())
        assertIs<ExpressionException.EmptyExpression>(runCatching { parser.parse("   ") }.exceptionOrNull())
        assertEquals(
            "Unmatched {{ in expression: x {{y",
            assertIs<ExpressionException.ParseError>(runCatching { parser.parse("x {{y") }.exceptionOrNull()).message,
        )
    }

    @Test
    fun string_escapes() {
        assertEquals(ExpressionNode.Literal(LiteralValue.StringValue("it's\n\t\\q")), parser.parse("'it\\'s\\n\\t\\\\\\q'"))
    }

    @Test
    fun only_horizontal_whitespace_is_trimmed_around_the_expression() {
        // `.whitespaces` excludes newlines, but the tokenizer skips any Unicode whitespace.
        assertEquals(ExpressionNode.Variable("a"), parser.parse(" a\t"))
        assertEquals(ExpressionNode.Variable("a"), parser.parse("a\n"))
    }

    // endregion

    private fun describe(expression: String): String = when (val node = parser.parse(expression)) {
        is ExpressionNode.Literal -> when (val v = node.value) {
            LiteralValue.NullValue -> ".literal(null)"
            is LiteralValue.BoolValue -> ".literal(${v.value})"
            is LiteralValue.IntValue -> ".literal(${v.value})"
            is LiteralValue.DoubleValue -> ".literal(${v.value})"
            is LiteralValue.StringValue -> ".literal(${v.value})"
            is LiteralValue.ArrayValue -> ".literal(array)"
        }
        is ExpressionNode.Variable -> ".variable(${node.name})"
        is ExpressionNode.BinaryOperation -> ".binaryOperation(${node.operator})"
        is ExpressionNode.UnaryOperation -> ".unaryOperation(${node.operator})"
        is ExpressionNode.FunctionCall -> ".functionCall(${node.name})"
        is ExpressionNode.MemberAccess -> ".memberAccess(${node.member})"
        is ExpressionNode.ArrayAccess -> ".arrayAccess"
        is ExpressionNode.Ternary -> ".ternary"
        is ExpressionNode.ArrayLiteral -> ".arrayLiteral"
    }
}
