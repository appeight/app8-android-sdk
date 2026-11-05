package dev.app8.engine.core.layout.cassowary

import kotlin.math.max
import kotlin.math.min

public enum class RelationalOperator(public val symbol: String) {
    LE("<="),
    GE(">="),
    EQ("=="),
}

/**
 * Constraint strengths, as in kiwi: `required` constraints must hold; any
 * weaker strength is a preference.
 *
 * Unlike kiwi, this solver compares non-required strengths
 * lexicographically: every distinct strength is its own level, and any
 * amount of violation at a lower level is preferred over the smallest
 * violation at a higher one. That is Auto Layout's priority semantics
 * (a priority-251 constraint always beats any number of priority-250 ones)
 * and the original Cassowary hierarchy; kiwi only approximates it with
 * weights.
 */
public object Strength {
    public fun create(a: Double, b: Double, c: Double, w: Double = 1.0): Double {
        var result = 0.0
        result += max(0.0, min(1000.0, a * w)) * 1_000_000.0
        result += max(0.0, min(1000.0, b * w)) * 1_000.0
        result += max(0.0, min(1000.0, c * w))
        return result
    }

    public val required: Double = create(1000.0, 1000.0, 1000.0)
    public val strong: Double = create(1.0, 0.0, 0.0)
    public val medium: Double = create(0.0, 1.0, 0.0)
    public val weak: Double = create(0.0, 0.0, 1.0)

    public fun clip(value: Double): Double = max(0.0, min(required, value))
}

/**
 * `lhs <op> rhs` at a strength, stored as `expression <op> 0`.
 * Identity-based: adding the same instance twice is an error.
 */
public class Constraint private constructor(
    expression: Expression,
    public val op: RelationalOperator,
    strength: Double = Strength.required,
) {
    public val expression: Expression = expression.reduced()
    public val strength: Double = Strength.clip(strength)

    public constructor(lhs: Expression, op: RelationalOperator, rhs: Expression, strength: Double = Strength.required) :
        this(lhs - rhs, op, strength)

    public constructor(lhs: Expression, op: RelationalOperator, rhs: Double, strength: Double = Strength.required) :
        this(lhs - rhs, op, strength)

    public constructor(lhs: Variable, op: RelationalOperator, rhs: Double, strength: Double = Strength.required) :
        this(Expression.of(lhs) - rhs, op, strength)

    public constructor(lhs: Variable, op: RelationalOperator, rhs: Expression, strength: Double = Strength.required) :
        this(Expression.of(lhs) - rhs, op, strength)

    override fun toString(): String = "$expression ${op.symbol} 0 @$strength"

    public companion object {
        /** `expression <op> 0`. */
        public fun of(expression: Expression, op: RelationalOperator, strength: Double = Strength.required): Constraint =
            Constraint(expression, op, strength)
    }
}

public open class SolverException(message: String) : RuntimeException(message)
public class UnsatisfiableConstraintException(public val constraint: Constraint) :
    SolverException("unsatisfiable constraint: $constraint")
public class DuplicateConstraintException(public val constraint: Constraint) :
    SolverException("duplicate constraint: $constraint")
public class UnknownConstraintException(public val constraint: Constraint) :
    SolverException("unknown constraint: $constraint")
public class DuplicateEditVariableException(public val variable: Variable) :
    SolverException("duplicate edit variable: ${variable.name}")
public class UnknownEditVariableException(public val variable: Variable) :
    SolverException("unknown edit variable: ${variable.name}")
public class BadRequiredStrengthException : SolverException("an edit variable cannot have required strength")
public class InternalSolverError(message: String) : SolverException(message)
