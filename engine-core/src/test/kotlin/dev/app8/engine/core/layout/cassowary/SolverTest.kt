package dev.app8.engine.core.layout.cassowary

import dev.app8.engine.core.layout.cassowary.RelationalOperator.EQ
import dev.app8.engine.core.layout.cassowary.RelationalOperator.GE
import dev.app8.engine.core.layout.cassowary.RelationalOperator.LE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolverTest {
    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    @Test
    fun simpleEqualities() {
        val x = Variable("x")
        val y = Variable("y")
        val s = Solver()
        s.addConstraint(Constraint(x + 2.0, EQ, y + 10.0))
        s.addConstraint(Constraint(x, EQ, 20.0))
        s.updateVariables()
        assertNear(20.0, x.value)
        assertNear(12.0, y.value)
    }

    @Test
    fun inequalitiesBoundAPreference() {
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, LE, 10.0))
        s.addConstraint(Constraint(x, GE, 4.0))
        s.addConstraint(Constraint(x, EQ, 100.0, Strength.weak))
        s.updateVariables()
        assertNear(10.0, x.value)
    }

    @Test
    fun strongerPreferenceWins() {
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 10.0, Strength.weak))
        s.addConstraint(Constraint(x, EQ, 20.0, Strength.strong))
        s.updateVariables()
        assertNear(20.0, x.value)
    }

    @Test
    fun strengthsAreLexicographicNotWeighted() {
        // With kiwi's weights, five errors at 250 outweigh one at 251; Auto Layout
        // priorities never trade a higher priority for lower ones.
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 0.0, 251.0))
        repeat(5) { s.addConstraint(Constraint(x, EQ, 100.0, 250.0)) }
        s.updateVariables()
        assertNear(0.0, x.value)
    }

    @Test
    fun sameStrengthSumsErrors() {
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 0.0, Strength.medium))
        s.addConstraint(Constraint(x, EQ, 100.0, Strength.medium))
        s.addConstraint(Constraint(x, EQ, 100.0, Strength.medium))
        s.updateVariables()
        assertNear(100.0, x.value)
    }

    @Test
    fun editVariablesFollowSuggestions() {
        val left = Variable("left")
        val width = Variable("width")
        val right = Variable("right")
        val s = Solver()
        s.addConstraint(Constraint(left + width, EQ, Expression.of(right)))
        s.addConstraint(Constraint(width, GE, 10.0))
        s.addConstraint(Constraint(left, GE, 0.0))
        s.addConstraint(Constraint(right, LE, 100.0))
        s.addEditVariable(left, Strength.strong)
        s.addEditVariable(width, Strength.medium)
        assertTrue(s.hasEditVariable(left))

        s.suggestValue(left, 30.0)
        s.suggestValue(width, 20.0)
        s.updateVariables()
        assertNear(30.0, left.value)
        assertNear(50.0, right.value)

        // The width can't grow past the right bound; the stronger left edit holds.
        s.suggestValue(width, 200.0)
        s.updateVariables()
        assertNear(30.0, left.value)
        assertNear(70.0, width.value)

        s.suggestValue(left, 95.0)
        s.updateVariables()
        assertNear(90.0, left.value)
        assertNear(10.0, width.value)

        s.removeEditVariable(left)
        assertFalse(s.hasEditVariable(left))
        assertFailsWith<UnknownEditVariableException> { s.suggestValue(left, 1.0) }
        assertFailsWith<DuplicateEditVariableException> { s.addEditVariable(width, Strength.weak) }
        assertFailsWith<BadRequiredStrengthException> { s.addEditVariable(left, Strength.required) }
    }

    @Test
    fun removingAConstraintRestoresTheSolution() {
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 10.0, Strength.weak))
        val strong = Constraint(x, EQ, 20.0, Strength.strong)
        s.addConstraint(strong)
        s.updateVariables()
        assertNear(20.0, x.value)
        assertTrue(s.hasConstraint(strong))
        s.removeConstraint(strong)
        s.updateVariables()
        assertNear(10.0, x.value)
        assertFailsWith<UnknownConstraintException> { s.removeConstraint(strong) }
    }

    @Test
    fun removingRequiredInequalities() {
        val x = Variable("x")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 100.0, Strength.weak))
        val upper = Constraint(x, LE, 50.0)
        val lower = Constraint(x, GE, 70.0, Strength.strong)
        s.addConstraint(upper)
        s.addConstraint(lower)
        s.updateVariables()
        assertNear(50.0, x.value)
        s.removeConstraint(upper)
        s.updateVariables()
        assertNear(100.0, x.value)
    }

    @Test
    fun duplicateConstraintIsRejected() {
        val x = Variable("x")
        val s = Solver()
        val c = Constraint(x, EQ, 1.0)
        s.addConstraint(c)
        assertFailsWith<DuplicateConstraintException> { s.addConstraint(c) }
    }

    @Test
    fun unsatisfiableConstraintLeavesTheSolverIntact() {
        val x = Variable("x")
        val y = Variable("y")
        val s = Solver()
        s.addConstraint(Constraint(x, EQ, 10.0))
        s.addConstraint(Constraint(y, EQ, x + 5.0))
        s.addConstraint(Constraint(y, GE, 0.0))
        assertFailsWith<UnsatisfiableConstraintException> { s.addConstraint(Constraint(x, EQ, 20.0)) }
        assertFailsWith<UnsatisfiableConstraintException> { s.addConstraint(Constraint(y, LE, 3.0)) }
        // Through the artificial-variable path: no external symbol left to solve for.
        assertFailsWith<UnsatisfiableConstraintException> { s.addConstraint(Constraint(y - x, EQ, 7.0)) }
        s.addConstraint(Constraint(x, EQ, 20.0, 999.0))
        s.updateVariables()
        assertNear(10.0, x.value)
        assertNear(15.0, y.value)
    }

    @Test
    fun longChainSolvesQuickly() {
        val n = 2000
        val vars = List(n) { Variable("v$it") }
        val s = Solver()
        val start = System.nanoTime()
        s.addConstraint(Constraint(vars[0], EQ, 0.0))
        for (i in 1 until n) {
            s.addConstraint(Constraint(vars[i], GE, vars[i - 1] + 1.0))
            s.addConstraint(Constraint(vars[i], EQ, 0.0, Strength.weak))
        }
        s.updateVariables()
        val ms = (System.nanoTime() - start) / 1e6
        assertNear((n - 1).toDouble(), vars.last().value)
        assertTrue(ms < 20000, "took $ms ms")  // guard against quadratic blow-ups; CI runners are slow
    }
}
