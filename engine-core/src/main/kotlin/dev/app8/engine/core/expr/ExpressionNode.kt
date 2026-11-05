package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values

/** AST node types for parsed expressions (Swift `ExpressionNode`). */
public sealed interface ExpressionNode {
    public data class Literal(val value: LiteralValue) : ExpressionNode
    public data class Variable(val name: String) : ExpressionNode
    public data class BinaryOperation(val operator: String, val left: ExpressionNode, val right: ExpressionNode) : ExpressionNode
    public data class UnaryOperation(val operator: String, val operand: ExpressionNode) : ExpressionNode
    public data class FunctionCall(val name: String, val arguments: List<ExpressionNode>) : ExpressionNode
    public data class MemberAccess(val obj: ExpressionNode, val member: String) : ExpressionNode
    public data class ArrayAccess(val array: ExpressionNode, val index: ExpressionNode) : ExpressionNode
    public data class Ternary(
        val condition: ExpressionNode,
        val trueValue: ExpressionNode,
        val falseValue: ExpressionNode,
    ) : ExpressionNode

    /**
     * Array literal with at least one non-literal element (e.g. `[1, x, 3]`), evaluated
     * element-wise at runtime. All-literal arrays stay [Literal] of [LiteralValue.ArrayValue].
     */
    public data class ArrayLiteral(val elements: List<ExpressionNode>) : ExpressionNode
}

/** Literal values in the AST (Swift `LiteralValue`). */
public sealed interface LiteralValue {
    public data class BoolValue(val value: Boolean) : LiteralValue
    public data class IntValue(val value: Long) : LiteralValue
    public data class DoubleValue(val value: Double) : LiteralValue
    public data class StringValue(val value: String) : LiteralValue
    public data object NullValue : LiteralValue
    public data class ArrayValue(val values: List<LiteralValue>) : LiteralValue

    /** Runtime value; `null` literal evaluates to [Values.Null] (Swift `NSNull`). */
    public val anyValue: Any
        get() = when (this) {
            is BoolValue -> value
            is IntValue -> value
            is DoubleValue -> value
            is StringValue -> value
            NullValue -> Values.Null
            is ArrayValue -> values.map { it.anyValue }
        }

    public companion object {
        /** Swift `LiteralValue.from(_:)`. */
        public fun from(value: Any?): LiteralValue = when {
            value is Boolean -> BoolValue(value)
            Values.isInt(value) -> IntValue((value as Number).toLong())
            value is Double -> DoubleValue(value)
            value is Float -> DoubleValue(value.toDouble())
            value is String -> StringValue(value)
            value == null || value === Values.Null -> NullValue
            value is List<*> -> ArrayValue(value.map { from(it) })
            value is Number -> DoubleValue(value.toDouble())
            else -> StringValue(Values.describe(value))
        }
    }
}
