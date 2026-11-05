package dev.app8.engine.core.layout.cassowary

/**
 * A solver variable. Its [value] is written by [Solver.updateVariables].
 *
 * Identity-based: two variables with the same name are distinct.
 */
public class Variable(public val name: String = "") {
    public var value: Double = 0.0
        internal set

    override fun toString(): String = "$name=$value"

    public operator fun plus(other: Variable): Expression = Expression.of(this) + other
    public operator fun plus(other: Term): Expression = Expression.of(this) + other
    public operator fun plus(other: Expression): Expression = Expression.of(this) + other
    public operator fun plus(constant: Double): Expression = Expression.of(this) + constant
    public operator fun minus(other: Variable): Expression = Expression.of(this) - other
    public operator fun minus(other: Term): Expression = Expression.of(this) - other
    public operator fun minus(other: Expression): Expression = Expression.of(this) - other
    public operator fun minus(constant: Double): Expression = Expression.of(this) - constant
    public operator fun times(coefficient: Double): Term = Term(this, coefficient)
    public operator fun div(denominator: Double): Term = Term(this, 1.0 / denominator)
    public operator fun unaryMinus(): Term = Term(this, -1.0)
}

/** `coefficient * variable`. */
public class Term(public val variable: Variable, public val coefficient: Double = 1.0) {
    override fun toString(): String = "$coefficient*${variable.name}"

    public operator fun plus(other: Variable): Expression = Expression.of(this) + other
    public operator fun plus(other: Term): Expression = Expression.of(this) + other
    public operator fun plus(other: Expression): Expression = Expression.of(this) + other
    public operator fun plus(constant: Double): Expression = Expression.of(this) + constant
    public operator fun minus(other: Variable): Expression = Expression.of(this) - other
    public operator fun minus(other: Term): Expression = Expression.of(this) - other
    public operator fun minus(other: Expression): Expression = Expression.of(this) - other
    public operator fun minus(constant: Double): Expression = Expression.of(this) - constant
    public operator fun times(factor: Double): Term = Term(variable, coefficient * factor)
    public operator fun div(denominator: Double): Term = Term(variable, coefficient / denominator)
    public operator fun unaryMinus(): Term = Term(variable, -coefficient)
}

/** A linear expression: `sum(terms) + constant`. Immutable. */
public class Expression(public val terms: List<Term> = emptyList(), public val constant: Double = 0.0) {

    public constructor(constant: Double) : this(emptyList(), constant)

    public val isConstant: Boolean get() = terms.isEmpty()

    /** The current value, from the variables' last [Solver.updateVariables]. */
    public fun value(): Double = terms.fold(constant) { acc, t -> acc + t.coefficient * t.variable.value }

    public operator fun plus(other: Expression): Expression = Expression(terms + other.terms, constant + other.constant)
    public operator fun plus(other: Term): Expression = Expression(terms + other, constant)
    public operator fun plus(other: Variable): Expression = Expression(terms + Term(other), constant)
    public operator fun plus(value: Double): Expression = Expression(terms, constant + value)
    public operator fun minus(other: Expression): Expression = this + (-other)
    public operator fun minus(other: Term): Expression = this + (-other)
    public operator fun minus(other: Variable): Expression = this + Term(other, -1.0)
    public operator fun minus(value: Double): Expression = Expression(terms, constant - value)
    public operator fun times(factor: Double): Expression =
        Expression(terms.map { it * factor }, constant * factor)
    public operator fun div(denominator: Double): Expression = times(1.0 / denominator)
    public operator fun unaryMinus(): Expression = times(-1.0)

    /** Merges terms of the same variable and drops zero coefficients. */
    public fun reduced(): Expression {
        val merged = LinkedHashMap<Variable, Double>()
        for (t in terms) merged[t.variable] = (merged[t.variable] ?: 0.0) + t.coefficient
        return Expression(merged.map { (v, c) -> Term(v, c) }, constant)
    }

    override fun toString(): String = (terms.map { it.toString() } + constant.toString()).joinToString(" + ")

    public companion object {
        public fun of(variable: Variable): Expression = Expression(listOf(Term(variable)))
        public fun of(term: Term): Expression = Expression(listOf(term))
    }
}

public operator fun Double.times(variable: Variable): Term = Term(variable, this)
public operator fun Double.times(term: Term): Term = term * this
public operator fun Double.times(expression: Expression): Expression = expression * this
public operator fun Double.plus(expression: Expression): Expression = expression + this
public operator fun Double.minus(expression: Expression): Expression = -expression + this
