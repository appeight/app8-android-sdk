package dev.app8.engine.core.expr

/** Errors during expression parsing and evaluation (Swift `ExpressionError`). */
public sealed class ExpressionException(message: String) : Exception(message) {
    public class EmptyExpression : ExpressionException("Empty expression")

    /** [character] is the offending character; [position] is its code-point offset. */
    public class InvalidCharacter(public val character: String, public val position: Int) :
        ExpressionException("Invalid character '$character' at position $position")

    public class UnterminatedString : ExpressionException("Unterminated string literal")

    public class UnexpectedToken(public val position: Int) : ExpressionException("Unexpected token at position $position")

    public class ExpectedIdentifier : ExpressionException("Expected identifier after '.'")

    public class InvalidFunctionCall : ExpressionException("Invalid function call")

    public class ParseError(message: String) : ExpressionException(message)
}

/** Recursion / size limits shared with iOS `EngineLimits` (only the ones this layer uses). */
internal object EngineLimits {
    const val maxExpressionDepth: Int = 64
    const val maxJSONDepth: Int = 512
    const val maxArrayVariableCount: Int = 10_000
    const val maxRegexPatternLength: Int = 200
    const val maxRegexInputLength: Int = 10_000
}
