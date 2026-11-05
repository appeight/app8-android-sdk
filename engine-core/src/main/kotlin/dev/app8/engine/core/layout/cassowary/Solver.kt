package dev.app8.engine.core.layout.cassowary

import kotlin.math.abs
import kotlin.math.max

/**
 * An incremental Cassowary solver: a port of kiwi (C++ kiwisolver / kiwi.js)
 * with one deliberate difference. The objective is lexicographic over
 * strengths instead of a weighted sum, so a stronger preference is never
 * traded for any number of weaker ones (see [Strength]).
 *
 * Also unlike kiwi, a failed [addConstraint] leaves the solver exactly as it
 * was, so callers can retry the constraint at a weaker strength.
 *
 * For speed it keeps a column index (which rows mention each parametric
 * symbol), so a pivot only touches the rows it changes, and the set of
 * objective symbols that can still improve the solution.
 */
public class Solver {

    private val cnMap = LinkedHashMap<Constraint, Tag>()
    private val rowMap = LinkedHashMap<Symbol, Row>()

    /** Parametric symbol → basic symbols whose rows contain it. */
    private val columns = HashMap<Symbol, LinkedHashSet<Symbol>>()
    private val varMap = LinkedHashMap<Variable, Symbol>()
    private val editMap = LinkedHashMap<Variable, EditInfo>()
    private val infeasibleRows = ArrayList<Symbol>()
    private val levels = Levels()
    private var objective = LexRow(levels)
    private var artificial: LexRow? = null

    public fun hasConstraint(constraint: Constraint): Boolean = constraint in cnMap

    public fun addConstraint(constraint: Constraint) {
        if (constraint in cnMap) throw DuplicateConstraintException(constraint)

        // Creating the row may register new external symbols; that is harmless
        // if the constraint is rejected (they appear in no row).
        val tag = Tag()
        val row = createRow(constraint, tag)
        var subject = chooseSubject(row, tag)

        if (subject === Symbol.INVALID && row.allDummies()) {
            if (!nearZero(row.constant)) {
                undoObjective(constraint, tag)
                throw UnsatisfiableConstraintException(constraint)
            }
            subject = tag.marker
        }

        if (subject === Symbol.INVALID) {
            if (!addWithArtificialVariable(row)) {
                undoObjective(constraint, tag)
                // Phase one may have moved to another (equivalent) vertex.
                optimize(objective)
                throw UnsatisfiableConstraintException(constraint)
            }
        } else {
            row.solveFor(subject)
            substitute(subject, row)
            putRow(subject, row)
        }

        cnMap[constraint] = tag
        optimize(objective)
    }

    public fun removeConstraint(constraint: Constraint) {
        val tag = cnMap.remove(constraint) ?: throw UnknownConstraintException(constraint)
        removeConstraintEffects(constraint, tag)

        val row = takeRow(tag.marker)
        if (row == null) {
            val leaving = getMarkerLeavingSymbol(tag.marker)
            if (leaving === Symbol.INVALID) throw InternalSolverError("failed to find leaving row")
            val leavingRow = takeRow(leaving)!!
            leavingRow.solveFor(leaving, tag.marker)
            substitute(tag.marker, leavingRow)
        }
        optimize(objective)
    }

    public fun hasEditVariable(variable: Variable): Boolean = variable in editMap

    public fun addEditVariable(variable: Variable, strength: Double) {
        if (variable in editMap) throw DuplicateEditVariableException(variable)
        val clipped = Strength.clip(strength)
        if (clipped >= Strength.required) throw BadRequiredStrengthException()
        val constraint = Constraint.of(Expression.of(variable), RelationalOperator.EQ, clipped)
        addConstraint(constraint)
        editMap[variable] = EditInfo(cnMap[constraint]!!, constraint, 0.0)
    }

    public fun removeEditVariable(variable: Variable) {
        val info = editMap[variable] ?: throw UnknownEditVariableException(variable)
        removeConstraint(info.constraint)
        editMap.remove(variable)
    }

    public fun suggestValue(variable: Variable, value: Double) {
        val info = editMap[variable] ?: throw UnknownEditVariableException(variable)
        val delta = value - info.constant
        info.constant = value

        // Check first if the positive error variable is basic.
        val marker = info.tag.marker
        rowMap[marker]?.let { row ->
            if (row.add(-delta) < 0.0) infeasibleRows.add(marker)
            dualOptimize()
            return
        }
        // Check next if the negative error variable is basic.
        val other = info.tag.other
        rowMap[other]?.let { row ->
            if (row.add(delta) < 0.0) infeasibleRows.add(other)
            dualOptimize()
            return
        }
        // Otherwise update each row where the error variables exist.
        columns[marker]?.forEach { symbol ->
            val row = rowMap.getValue(symbol)
            val coefficient = row.coefficientFor(marker)
            if (row.add(delta * coefficient) < 0.0 && symbol.type != SymbolType.EXTERNAL) infeasibleRows.add(symbol)
        }
        dualOptimize()
    }

    /** Writes the solved values into the variables. */
    public fun updateVariables() {
        for ((variable, symbol) in varMap) {
            val v = rowMap[symbol]?.constant ?: 0.0
            variable.value = if (v == -0.0) 0.0 else v
        }
    }

    /** Removes every constraint and edit variable. */
    public fun reset() {
        cnMap.clear(); rowMap.clear(); columns.clear(); varMap.clear(); editMap.clear(); infeasibleRows.clear()
        objective = LexRow(levels)
        artificial = null
    }

    // region internals

    private class Tag {
        var marker: Symbol = Symbol.INVALID
        var other: Symbol = Symbol.INVALID
    }

    private class EditInfo(val tag: Tag, val constraint: Constraint, var constant: Double)

    private fun columnAdd(symbol: Symbol, basic: Symbol) {
        columns.getOrPut(symbol) { LinkedHashSet() }.add(basic)
    }

    private fun columnRemove(symbol: Symbol, basic: Symbol) {
        val set = columns[symbol] ?: return
        set.remove(basic)
        if (set.isEmpty()) columns.remove(symbol)
    }

    private fun putRow(basic: Symbol, row: Row) {
        rowMap[basic] = row
        for (symbol in row.cells.keys) columnAdd(symbol, basic)
    }

    private fun takeRow(basic: Symbol): Row? {
        val row = rowMap.remove(basic) ?: return null
        for (symbol in row.cells.keys) columnRemove(symbol, basic)
        return row
    }

    private fun createRow(constraint: Constraint, tag: Tag): Row {
        val expr = constraint.expression
        val row = Row(expr.constant)
        for (term in expr.terms) {
            if (nearZero(term.coefficient)) continue
            val symbol = getVarSymbol(term.variable)
            val basic = rowMap[symbol]
            if (basic != null) row.insert(basic, term.coefficient) else row.insert(symbol, term.coefficient)
        }

        val strength = constraint.strength
        when (constraint.op) {
            RelationalOperator.LE, RelationalOperator.GE -> {
                val coefficient = if (constraint.op == RelationalOperator.LE) 1.0 else -1.0
                val slack = Symbol(SymbolType.SLACK)
                tag.marker = slack
                row.insert(slack, coefficient)
                if (strength < Strength.required) {
                    val error = Symbol(SymbolType.ERROR)
                    tag.other = error
                    row.insert(error, -coefficient)
                    objective.add(error, levels.of(strength), 1.0)
                }
            }
            RelationalOperator.EQ -> {
                if (strength < Strength.required) {
                    val errPlus = Symbol(SymbolType.ERROR)
                    val errMinus = Symbol(SymbolType.ERROR)
                    tag.marker = errPlus
                    tag.other = errMinus
                    row.insert(errPlus, -1.0)
                    row.insert(errMinus, 1.0)
                    val level = levels.of(strength)
                    objective.add(errPlus, level, 1.0)
                    objective.add(errMinus, level, 1.0)
                } else {
                    val dummy = Symbol(SymbolType.DUMMY)
                    tag.marker = dummy
                    row.insert(dummy)
                }
            }
        }
        if (row.constant < 0.0) row.reverseSign()
        return row
    }

    /** Reverts the objective terms [createRow] added for a rejected constraint. */
    private fun undoObjective(constraint: Constraint, tag: Tag) {
        if (constraint.strength >= Strength.required) return
        if (tag.marker.type == SymbolType.ERROR) objective.remove(tag.marker)
        if (tag.other.type == SymbolType.ERROR) objective.remove(tag.other)
    }

    private fun chooseSubject(row: Row, tag: Tag): Symbol {
        for (symbol in row.cells.keys) if (symbol.type == SymbolType.EXTERNAL) return symbol
        if (tag.marker.type == SymbolType.SLACK || tag.marker.type == SymbolType.ERROR) {
            if (row.coefficientFor(tag.marker) < 0.0) return tag.marker
        }
        if (tag.other.type == SymbolType.SLACK || tag.other.type == SymbolType.ERROR) {
            if (row.coefficientFor(tag.other) < 0.0) return tag.other
        }
        return Symbol.INVALID
    }

    private fun addWithArtificialVariable(row: Row): Boolean {
        val art = Symbol(SymbolType.SLACK)
        putRow(art, row.copy())
        val artificialRow = LexRow(levels)
        artificialRow.insertRow(row, levels.artificial, 1.0)
        artificial = artificialRow

        optimize(artificialRow)
        val success = artificialRow.isConstantZero()
        artificial = null

        val artRow = takeRow(art)
        if (artRow != null) {
            // A basic artificial variable means its row was never pivoted, so the
            // rest of the tableau is untouched by it: dropping the row on failure
            // restores the previous system (kiwi would force it in instead).
            if (!success) return false
            if (artRow.cells.isEmpty()) return true
            val entering = anyPivotableSymbol(artRow)
            if (entering === Symbol.INVALID) return false
            artRow.solveFor(art, entering)
            substitute(entering, artRow)
            putRow(entering, artRow)
        }

        columns.remove(art)?.forEach { rowMap.getValue(it).remove(art) }
        objective.remove(art)
        return success
    }

    private fun substitute(symbol: Symbol, row: Row) {
        columns.remove(symbol)?.forEach { s ->
            val r = rowMap.getValue(s)
            r.substitute(symbol, row)
            for (c in row.cells.keys) if (c in r.cells) columnAdd(c, s) else columnRemove(c, s)
            if (s.type != SymbolType.EXTERNAL && r.constant < 0.0) infeasibleRows.add(s)
        }
        objective.substitute(symbol, row)
        artificial?.substitute(symbol, row)
    }

    private fun optimize(target: LexRow) {
        while (true) {
            val entering = getEnteringSymbol(target)
            if (entering === Symbol.INVALID) return
            val leaving = getLeavingSymbol(entering)
            if (leaving === Symbol.INVALID) throw InternalSolverError("the objective is unbounded")
            val row = takeRow(leaving)!!
            row.solveFor(leaving, entering)
            substitute(entering, row)
            putRow(entering, row)
        }
    }

    private fun dualOptimize() {
        while (infeasibleRows.isNotEmpty()) {
            val leaving = infeasibleRows.removeAt(infeasibleRows.size - 1)
            val row = rowMap[leaving]
            if (row != null && row.constant < 0.0) {
                val entering = getDualEnteringSymbol(row)
                if (entering === Symbol.INVALID) throw InternalSolverError("dual optimize failed")
                takeRow(leaving)
                row.solveFor(leaving, entering)
                substitute(entering, row)
                putRow(entering, row)
            }
        }
    }

    private fun getEnteringSymbol(target: LexRow): Symbol {
        val it = target.negatives.iterator()
        return if (it.hasNext()) it.next() else Symbol.INVALID
    }

    private fun getDualEnteringSymbol(row: Row): Symbol {
        var entering = Symbol.INVALID
        var bestVector: DoubleArray? = null
        var bestDivisor = 1.0
        for ((symbol, c) in row.cells) {
            if (c > 0.0 && symbol.type != SymbolType.DUMMY) {
                val coefficient = objective.cells[symbol]
                if (entering === Symbol.INVALID || levels.compareRatio(coefficient, c, bestVector, bestDivisor) < 0) {
                    entering = symbol
                    bestVector = coefficient
                    bestDivisor = c
                }
            }
        }
        return entering
    }

    private fun anyPivotableSymbol(row: Row): Symbol {
        for (symbol in row.cells.keys) {
            if (symbol.type == SymbolType.SLACK || symbol.type == SymbolType.ERROR) return symbol
        }
        return Symbol.INVALID
    }

    private fun getLeavingSymbol(entering: Symbol): Symbol {
        var ratio = Double.MAX_VALUE
        var found = Symbol.INVALID
        for (symbol in columns[entering] ?: return found) {
            if (symbol.type == SymbolType.EXTERNAL) continue
            val row = rowMap.getValue(symbol)
            val c = row.coefficientFor(entering)
            if (c < 0.0) {
                val r = -row.constant / c
                if (r < ratio) {
                    ratio = r
                    found = symbol
                }
            }
        }
        return found
    }

    private fun getMarkerLeavingSymbol(marker: Symbol): Symbol {
        var r1 = Double.MAX_VALUE
        var r2 = Double.MAX_VALUE
        var first = Symbol.INVALID
        var second = Symbol.INVALID
        var third = Symbol.INVALID
        for (symbol in columns[marker] ?: return Symbol.INVALID) {
            val row = rowMap.getValue(symbol)
            val c = row.coefficientFor(marker)
            if (c == 0.0) continue
            if (symbol.type == SymbolType.EXTERNAL) {
                third = symbol
            } else if (c < 0.0) {
                val r = -row.constant / c
                if (r < r1) { r1 = r; first = symbol }
            } else {
                val r = row.constant / c
                if (r < r2) { r2 = r; second = symbol }
            }
        }
        return when {
            first !== Symbol.INVALID -> first
            second !== Symbol.INVALID -> second
            else -> third
        }
    }

    private fun removeConstraintEffects(constraint: Constraint, tag: Tag) {
        if (tag.marker.type == SymbolType.ERROR) removeMarkerEffects(tag.marker, constraint.strength)
        if (tag.other.type == SymbolType.ERROR) removeMarkerEffects(tag.other, constraint.strength)
    }

    private fun removeMarkerEffects(marker: Symbol, strength: Double) {
        val level = levels.of(strength)
        val row = rowMap[marker]
        if (row != null) objective.insertRow(row, level, -1.0) else objective.add(marker, level, -1.0)
    }

    private fun getVarSymbol(variable: Variable): Symbol =
        varMap.getOrPut(variable) { Symbol(SymbolType.EXTERNAL) }

    // endregion
}

internal const val EPSILON: Double = 1.0e-8

internal fun nearZero(value: Double): Boolean = abs(value) < EPSILON

internal enum class SymbolType { INVALID, EXTERNAL, SLACK, ERROR, DUMMY }

/** Identity-keyed tableau symbol. */
internal class Symbol(val type: SymbolType) {
    companion object {
        val INVALID = Symbol(SymbolType.INVALID)
    }
}

/** A tableau row: `basic = constant + sum(cells)`. */
internal class Row(var constant: Double = 0.0) {
    val cells = LinkedHashMap<Symbol, Double>()

    fun copy(): Row = Row(constant).also { it.cells.putAll(cells) }

    fun add(value: Double): Double {
        constant += value
        return constant
    }

    fun allDummies(): Boolean = cells.keys.all { it.type == SymbolType.DUMMY }

    fun insert(symbol: Symbol, coefficient: Double = 1.0) {
        val c = (cells[symbol] ?: 0.0) + coefficient
        if (nearZero(c)) cells.remove(symbol) else cells[symbol] = c
    }

    fun insert(other: Row, coefficient: Double = 1.0) {
        constant += other.constant * coefficient
        for ((s, c) in other.cells) insert(s, c * coefficient)
    }

    fun remove(symbol: Symbol) {
        cells.remove(symbol)
    }

    fun reverseSign() {
        constant = -constant
        for (e in cells.entries) e.setValue(-e.value)
    }

    fun solveFor(symbol: Symbol) {
        val coefficient = -1.0 / cells.remove(symbol)!!
        constant *= coefficient
        for (e in cells.entries) e.setValue(e.value * coefficient)
    }

    fun solveFor(lhs: Symbol, rhs: Symbol) {
        insert(lhs, -1.0)
        solveFor(rhs)
    }

    fun coefficientFor(symbol: Symbol): Double = cells[symbol] ?: 0.0

    fun substitute(symbol: Symbol, row: Row) {
        val coefficient = cells.remove(symbol) ?: return
        insert(row, coefficient)
    }
}

/** Strength levels, ordered strongest first for lexicographic comparison. */
internal class Levels {
    private val index = HashMap<Double, Int>()
    private val strengths = ArrayList<Double>()
    var order: IntArray = IntArray(0)
        private set

    /** Above every strength: the phase-one (artificial) objective. */
    val artificial: Int = of(Double.POSITIVE_INFINITY)

    val size: Int get() = strengths.size

    fun of(strength: Double): Int = index.getOrPut(strength) {
        strengths.add(strength)
        order = strengths.indices.sortedByDescending { strengths[it] }.toIntArray()
        strengths.size - 1
    }

    fun sign(v: DoubleArray): Int {
        for (i in order) {
            if (i >= v.size) continue
            val x = v[i]
            if (x < -EPSILON) return -1
            if (x > EPSILON) return 1
        }
        return 0
    }

    /** Compares `a / da` with `b / db` lexicographically (null = zero vector). */
    fun compareRatio(a: DoubleArray?, da: Double, b: DoubleArray?, db: Double): Int {
        for (i in order) {
            val x = if (a != null && i < a.size) a[i] / da else 0.0
            val y = if (b != null && i < b.size) b[i] / db else 0.0
            val d = x - y
            if (d < -EPSILON) return -1
            if (d > EPSILON) return 1
        }
        return 0
    }
}

/** The objective: one coefficient per strength level for each symbol. */
internal class LexRow(private val levels: Levels) {
    val cells = LinkedHashMap<Symbol, DoubleArray>()

    /** Non-dummy symbols whose coefficient is lexicographically negative: entering candidates. */
    val negatives = LinkedHashSet<Symbol>()
    private var constant = DoubleArray(levels.size)

    fun isConstantZero(): Boolean = constant.all { nearZero(it) }

    private fun vector(symbol: Symbol, minSize: Int): DoubleArray {
        val existing = cells[symbol]
        val size = max(minSize, levels.size)
        val v = when {
            existing == null -> DoubleArray(size)
            existing.size < minSize -> existing.copyOf(size)
            else -> return existing
        }
        cells[symbol] = v
        return v
    }

    /** Drops a zero vector and keeps [negatives] in sync after [symbol]'s vector changed. */
    private fun prune(symbol: Symbol, v: DoubleArray) {
        when (levels.sign(v)) {
            -1 -> if (symbol.type != SymbolType.DUMMY) negatives.add(symbol) // dummies never enter
            1 -> negatives.remove(symbol)
            else -> {
                negatives.remove(symbol)
                cells.remove(symbol)
            }
        }
    }

    fun add(symbol: Symbol, level: Int, value: Double) {
        val v = vector(symbol, level + 1)
        v[level] += value
        prune(symbol, v)
    }

    fun insertRow(row: Row, level: Int, coefficient: Double) {
        if (constant.size <= level) constant = constant.copyOf(max(level + 1, levels.size))
        constant[level] += row.constant * coefficient
        for ((s, c) in row.cells) add(s, level, c * coefficient)
    }

    fun remove(symbol: Symbol) {
        cells.remove(symbol)
        negatives.remove(symbol)
    }

    fun substitute(symbol: Symbol, row: Row) {
        val v = cells.remove(symbol) ?: return
        negatives.remove(symbol)
        if (constant.size < v.size) constant = constant.copyOf(v.size)
        for (i in v.indices) constant[i] += v[i] * row.constant
        for ((s, c) in row.cells) {
            val target = vector(s, v.size)
            for (i in v.indices) target[i] += v[i] * c
            prune(s, target)
        }
    }
}
