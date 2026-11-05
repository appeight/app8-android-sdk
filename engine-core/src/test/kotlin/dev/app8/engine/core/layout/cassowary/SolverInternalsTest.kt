package dev.app8.engine.core.layout.cassowary

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The primitive tableau structures behave like the `LinkedHashMap`s they replaced. */
class SolverInternalsTest {
    @Test
    fun symbolMapIteratesLikeALinkedHashMap() {
        val random = Random(7)
        val symbols = (0 until 64).map { Symbol(SymbolType.SLACK, it) }
        repeat(50) {
            val map = SymbolMap()
            val reference = LinkedHashMap<Symbol, Double>()
            repeat(2_000) {
                val s = symbols[random.nextInt(if (random.nextBoolean()) 6 else symbols.size)]
                when (random.nextInt(4)) {
                    0 -> { map.remove(s); reference.remove(s) }
                    else -> {
                        // kiwi `insert`: add, and drop the entry when it cancels out.
                        val delta = if (random.nextInt(5) == 0) -(reference[s] ?: 0.0) else random.nextInt(-3, 4).toDouble()
                        map.addTo(s, delta)
                        val v = (reference[s] ?: 0.0) + delta
                        if (nearZero(v)) reference.remove(s) else reference[s] = v
                    }
                }
                assertEquals(reference.size, map.size)
            }
            val entries = ArrayList<Pair<Symbol, Double>>()
            for (i in 0 until map.end) map.keyAt(i)?.let { entries += it to map.valueAt(i) }
            assertEquals(reference.toList(), entries)
        }
    }

    @Test
    fun layoutModeSolvesLikeTheRemovableSolver() {
        // Random systems of required equalities and inequalities plus weak preferences: the
        // marker-free solver used by layout passes must land on the same solution.
        val random = Random(42)
        repeat(200) {
            val vars = (0 until 12).map { Variable("v$it") }
            val constraints = buildList {
                for (v in vars) add(Triple(Expression.of(v), RelationalOperator.GE, 0.0) to Strength.required)
                repeat(10) {
                    val a = vars[random.nextInt(vars.size)]
                    val b = vars[random.nextInt(vars.size)]
                    val op = RelationalOperator.entries[random.nextInt(3)]
                    val strength = if (random.nextInt(3) == 0) Strength.required else listOf(250.0, 251.0, 750.0, 999.0)[random.nextInt(4)]
                    add(Triple(a + b * random.nextInt(-2, 3).toDouble(), op, random.nextInt(0, 100).toDouble()) to strength)
                }
                for (v in vars) add(Triple(Expression.of(v), RelationalOperator.EQ, 0.0) to 0.5)
            }
            fun solve(removable: Boolean): List<Double> {
                val s = Solver(removable)
                for ((c, strength) in constraints) {
                    try {
                        s.addConstraint(Constraint(c.first, c.second, c.third, strength))
                    } catch (_: UnsatisfiableConstraintException) {
                        s.addConstraint(Constraint(c.first, c.second, c.third, 999.5))
                    }
                }
                s.updateVariables()
                return vars.map { it.value }
            }
            val expected = solve(removable = true)
            val actual = solve(removable = false)
            for (i in vars.indices) assertEquals(expected[i], actual[i], 1e-6, "system $it, v$i")
        }
    }

    @Test
    fun layoutModeCannotRemoveConstraints() {
        val x = Variable("x")
        val s = Solver(removable = false)
        val c = Constraint(x, RelationalOperator.EQ, 1.0)
        s.addConstraint(c)
        assertFailsWith<UnsupportedOperationException> { s.removeConstraint(c) }
    }
}
