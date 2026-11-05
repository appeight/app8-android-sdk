package dev.app8.engine.core.expr

/**
 * Parses expression strings into [ExpressionNode] trees (Swift `ExpressionParser`).
 *
 * Accepts a bare expression (`a + b`), a single `{{expr}}`, or an interpolated template
 * (`"{{a}} / {{b}}"`) which compiles to a left-associative `+` chain.
 *
 * Stateless and thread-safe. Token positions are code-point offsets (Swift counts Characters;
 * they differ only for strings with combining marks / emoji sequences before the error).
 */
public class ExpressionParser {

    public fun parse(expression: String): ExpressionNode {
        val trimmed = SwiftCompat.trimWhitespaces(expression)
        if (trimmed.contains("{{")) return parseTemplateExpression(trimmed)
        return parseSingleExpression(trimmed)
    }

    private fun parseTemplateExpression(str: String): ExpressionNode {
        val parts = ArrayList<ExpressionNode>()
        var remaining = str
        while (remaining.isNotEmpty()) {
            val open = remaining.indexOf("{{")
            if (open >= 0) {
                val textBefore = remaining.substring(0, open)
                if (textBefore.isNotEmpty()) parts.add(ExpressionNode.Literal(LiteralValue.StringValue(textBefore)))
                remaining = remaining.substring(open + 2)
                val close = remaining.indexOf("}}")
                if (close < 0) throw ExpressionException.ParseError("Unmatched {{ in expression: $str")
                val inner = SwiftCompat.trimWhitespaces(remaining.substring(0, close))
                remaining = remaining.substring(close + 2)
                parts.add(parseSingleExpression(inner))
            } else {
                parts.add(ExpressionNode.Literal(LiteralValue.StringValue(remaining)))
                break
            }
        }
        if (parts.isEmpty()) throw ExpressionException.EmptyExpression()
        return parts.drop(1).fold(parts[0]) { acc, next -> ExpressionNode.BinaryOperation("+", acc, next) }
    }

    private fun parseSingleExpression(expression: String): ExpressionNode {
        val tokens = tokenize(expression)
        if (tokens.isEmpty() || (tokens.size == 1 && tokens[0].type is TokenType.Eof)) {
            throw ExpressionException.EmptyExpression()
        }
        val parser = ParserState(tokens)
        val node = parser.parseTernary()
        if (!parser.isAtEnd()) throw ExpressionException.UnexpectedToken(parser.current().position)
        return node
    }

    // region Tokenizer

    private fun tokenize(text: String): List<Token> {
        val input = text.codePoints().toArray()
        val tokens = ArrayList<Token>()
        var index = 0
        fun next(offset: Int): Int? = input.getOrNull(index + offset)

        while (index < input.size) {
            val c = input[index]
            if (SwiftCompat.isUnicodeWhiteSpace(c)) {
                index++
                continue
            }
            val position = index
            when (c) {
                '('.code -> { tokens.add(Token(TokenType.LeftParen, position)); index++ }
                ')'.code -> { tokens.add(Token(TokenType.RightParen, position)); index++ }
                '['.code -> { tokens.add(Token(TokenType.LeftBracket, position)); index++ }
                ']'.code -> { tokens.add(Token(TokenType.RightBracket, position)); index++ }
                ','.code -> { tokens.add(Token(TokenType.Comma, position)); index++ }
                '.'.code -> { tokens.add(Token(TokenType.Dot, position)); index++ }
                '?'.code -> { tokens.add(Token(TokenType.Question, position)); index++ }
                ':'.code -> { tokens.add(Token(TokenType.Colon, position)); index++ }
                '\''.code, '"'.code -> {
                    val (string, newIndex) = parseString(input, index)
                    tokens.add(Token(TokenType.Literal(LiteralValue.StringValue(string)), position))
                    index = newIndex
                }
                in '0'.code..'9'.code -> {
                    val (literal, newIndex) = parseNumber(input, index)
                    tokens.add(Token(TokenType.Literal(literal), position))
                    index = newIndex
                }
                '!'.code -> if (next(1) == '='.code) {
                    if (next(2) == '='.code) {
                        tokens.add(Token(TokenType.Operator("!=="), position)); index += 3
                    } else {
                        tokens.add(Token(TokenType.Operator("!="), position)); index += 2
                    }
                } else {
                    tokens.add(Token(TokenType.Operator("!"), position)); index++
                }
                '='.code -> if (next(1) == '='.code) {
                    if (next(2) == '='.code) {
                        tokens.add(Token(TokenType.Operator("==="), position)); index += 3
                    } else {
                        tokens.add(Token(TokenType.Operator("=="), position)); index += 2
                    }
                } else {
                    throw ExpressionException.InvalidCharacter(cpString(c), position)
                }
                '<'.code, '>'.code -> {
                    val op = String(Character.toChars(c))
                    if (next(1) == '='.code) {
                        tokens.add(Token(TokenType.Operator("$op="), position)); index += 2
                    } else {
                        tokens.add(Token(TokenType.Operator(op), position)); index++
                    }
                }
                '&'.code, '|'.code -> if (next(1) == c) {
                    tokens.add(Token(TokenType.Operator(cpString(c) + cpString(c)), position)); index += 2
                } else {
                    throw ExpressionException.InvalidCharacter(cpString(c), position)
                }
                '+'.code, '-'.code, '*'.code, '/'.code, '%'.code -> {
                    tokens.add(Token(TokenType.Operator(cpString(c)), position)); index++
                }
                else -> if (Character.isLetter(c) || c == '_'.code || c == '$'.code) {
                    val start = index
                    while (index < input.size && isIdentifierPart(input[index])) index++
                    val identifier = String(input, start, index - start)
                    val type = when (identifier) {
                        "true" -> TokenType.Literal(LiteralValue.BoolValue(true))
                        "false" -> TokenType.Literal(LiteralValue.BoolValue(false))
                        "null" -> TokenType.Literal(LiteralValue.NullValue)
                        else -> TokenType.Identifier(identifier)
                    }
                    tokens.add(Token(type, position))
                } else {
                    throw ExpressionException.InvalidCharacter(cpString(c), position)
                }
            }
        }
        tokens.add(Token(TokenType.Eof, input.size))
        return tokens
    }

    private fun cpString(cp: Int): String = String(Character.toChars(cp))

    /** Swift `Character.isNumber`: any Unicode numeric character. */
    private fun isNumberChar(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Combining marks belong to the preceding Character in Swift, so they continue an identifier. */
    private fun isIdentifierPart(cp: Int): Boolean =
        Character.isLetter(cp) || isNumberChar(cp) || cp == '_'.code || cp == '$'.code ||
            Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
                    it == Character.ENCLOSING_MARK.toInt()
            }

    private fun parseString(input: IntArray, start: Int): Pair<String, Int> {
        val quote = input[start]
        var i = start + 1
        val result = StringBuilder()
        while (i < input.size) {
            val c = input[i]
            if (c == quote) {
                return result.toString() to i + 1
            } else if (c == '\\'.code && i < input.size - 1) {
                i++
                when (val escaped = input[i]) {
                    'n'.code -> result.append('\n')
                    't'.code -> result.append('\t')
                    'r'.code -> result.append('\r')
                    '\\'.code -> result.append('\\')
                    else -> result.appendCodePoint(escaped)
                }
                i++
            } else {
                result.appendCodePoint(c)
                i++
            }
        }
        throw ExpressionException.UnterminatedString()
    }

    private fun parseNumber(input: IntArray, start: Int): Pair<LiteralValue, Int> {
        var i = start
        var hasDecimal = false
        while (i < input.size) {
            val c = input[i]
            if (isNumberChar(c)) {
                i++
            } else if (c == '.'.code && !hasDecimal) {
                hasDecimal = true
                i++
            } else {
                break
            }
        }
        val numberString = String(input, start, i - start)
        return if (hasDecimal) {
            LiteralValue.DoubleValue(SwiftCompat.parseDouble(numberString) ?: 0.0) to i
        } else {
            LiteralValue.IntValue(SwiftCompat.parseInt(numberString) ?: 0) to i
        }
    }

    // endregion
}

private sealed interface TokenType {
    data class Literal(val value: LiteralValue) : TokenType
    data class Identifier(val name: String) : TokenType
    data class Operator(val op: String) : TokenType
    data object LeftParen : TokenType
    data object RightParen : TokenType
    data object LeftBracket : TokenType
    data object RightBracket : TokenType
    data object Comma : TokenType
    data object Dot : TokenType
    data object Question : TokenType
    data object Colon : TokenType
    data object Eof : TokenType
}

private class Token(val type: TokenType, val position: Int)

private class ParserState(private val tokens: List<Token>) {
    private var currentIndex = 0
    private var depth = 0

    fun parseTernary(): ExpressionNode {
        depth++
        try {
            if (depth > EngineLimits.maxExpressionDepth) {
                throw ExpressionException.ParseError("expression nested too deeply")
            }
            var node = parseOr()
            if (match(TokenType.Question)) {
                val trueValue = parseTernary()
                consume(TokenType.Colon, "Expected ':' after true value in ternary expression")
                val falseValue = parseTernary()
                node = ExpressionNode.Ternary(node, trueValue, falseValue)
            }
            return node
        } finally {
            depth--
        }
    }

    private inline fun binaryLevel(vararg ops: String, operand: () -> ExpressionNode): ExpressionNode {
        var node = operand()
        while (true) {
            val op = matchOperator(*ops) ?: break
            val right = operand()
            node = ExpressionNode.BinaryOperation(op, node, right)
        }
        return node
    }

    private fun parseOr(): ExpressionNode = binaryLevel("||") { parseAnd() }
    private fun parseAnd(): ExpressionNode = binaryLevel("&&") { parseEquality() }
    private fun parseEquality(): ExpressionNode = binaryLevel("===", "!==", "==", "!=") { parseComparison() }
    private fun parseComparison(): ExpressionNode = binaryLevel(">", ">=", "<", "<=") { parseAddition() }
    private fun parseAddition(): ExpressionNode = binaryLevel("+", "-") { parseMultiplication() }
    private fun parseMultiplication(): ExpressionNode = binaryLevel("*", "/", "%") { parseUnary() }

    private fun parseUnary(): ExpressionNode {
        val op = matchOperator("!", "-")
        if (op != null) return ExpressionNode.UnaryOperation(op, parseUnary())
        return parsePostfix()
    }

    private fun parseArguments(): List<ExpressionNode> {
        val arguments = ArrayList<ExpressionNode>()
        if (!check(TokenType.RightParen)) {
            do {
                arguments.add(parseTernary())
            } while (match(TokenType.Comma))
        }
        consume(TokenType.RightParen, "Expected ')' after function arguments")
        return arguments
    }

    private fun parsePostfix(): ExpressionNode {
        var node = parsePrimary()
        while (true) {
            if (match(TokenType.Dot)) {
                val type = peek().type
                if (type is TokenType.Identifier) {
                    advance()
                    node = ExpressionNode.MemberAccess(node, type.name)
                } else {
                    throw ExpressionException.ExpectedIdentifier()
                }
            } else if (match(TokenType.LeftBracket)) {
                val index = parseTernary()
                consume(TokenType.RightBracket, "Expected ']' after array index")
                node = ExpressionNode.ArrayAccess(node, index)
            } else if (match(TokenType.LeftParen)) {
                node = when (val callee = node) {
                    is ExpressionNode.MemberAccess -> {
                        val args = parseArguments()
                        ExpressionNode.FunctionCall(callee.member, listOf(callee.obj) + args)
                    }
                    is ExpressionNode.Variable -> ExpressionNode.FunctionCall(callee.name, parseArguments())
                    else -> throw ExpressionException.InvalidFunctionCall()
                }
            } else {
                break
            }
        }
        return node
    }

    private fun parsePrimary(): ExpressionNode {
        val type = peek().type
        if (type is TokenType.Literal) {
            advance()
            return ExpressionNode.Literal(type.value)
        }
        if (type is TokenType.Identifier) {
            advance()
            return ExpressionNode.Variable(type.name)
        }
        if (match(TokenType.LeftParen)) {
            val expr = parseTernary()
            consume(TokenType.RightParen, "Expected ')' after expression")
            return expr
        }
        if (match(TokenType.LeftBracket)) {
            val elements = ArrayList<ExpressionNode>()
            if (!check(TokenType.RightBracket)) {
                do {
                    elements.add(parseTernary())
                } while (match(TokenType.Comma))
            }
            consume(TokenType.RightBracket, "Expected ']' after array elements")
            // All-literal → a literal array; otherwise keep every element node (evaluated at runtime).
            if (elements.all { it is ExpressionNode.Literal }) {
                return ExpressionNode.Literal(LiteralValue.ArrayValue(elements.map { (it as ExpressionNode.Literal).value }))
            }
            return ExpressionNode.ArrayLiteral(elements)
        }
        throw ExpressionException.UnexpectedToken(current().position)
    }

    // region helpers

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun matchOperator(vararg operators: String): String? {
        val type = peek().type
        if (type is TokenType.Operator && type.op in operators) {
            advance()
            return type.op
        }
        return null
    }

    /** Structural check that ignores payloads, like Swift's `check` (`.literal` matches any literal). */
    private fun check(type: TokenType): Boolean {
        if (isAtEnd()) return false
        val current = peek().type
        return when (type) {
            is TokenType.Operator -> current is TokenType.Operator && current.op == type.op
            is TokenType.Identifier -> current is TokenType.Identifier
            is TokenType.Literal -> current is TokenType.Literal
            else -> current == type
        }
    }

    private fun consume(type: TokenType, message: String) {
        if (check(type)) {
            advance()
            return
        }
        throw ExpressionException.ParseError(message)
    }

    private fun advance() {
        if (!isAtEnd()) currentIndex++
    }

    fun isAtEnd(): Boolean = currentIndex >= tokens.size || peek().type is TokenType.Eof

    private fun peek(): Token = tokens[currentIndex]

    fun current(): Token = tokens[currentIndex]

    // endregion
}
