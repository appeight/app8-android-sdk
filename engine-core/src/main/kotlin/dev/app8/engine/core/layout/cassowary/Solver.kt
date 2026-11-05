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
public class Solver internal constructor(
    /**
     * Whether required equalities keep a dummy marker in the tableau (kiwi does). The markers
     * are always zero and never pivot; they only let [removeConstraint] find the constraint
     * again. A solver that never removes constraints (one layout pass) drops them: on screens
     * with long stacks they fill every row and dominate the solve time.
     */
    private val removable: Boolean,
) {
    public constructor() : this(removable = true)

    private val cnMap = LinkedHashMap<Constraint, Tag>()
    private val varMap = LinkedHashMap<Variable, Symbol>()
    private val editMap = LinkedHashMap<Variable, EditInfo>()
    private val infeasibleRows = ArrayList<Symbol>()
    private val levels = Levels()
    private var objective = LexRow(levels)
    private var artificial: LexRow? = null

    // Rows and the column index live on the symbols themselves ([Symbol.row], [Symbol.column]):
    // no hash lookups on the hot path. Ids number this solver's symbols.
    private var nextId = 0

    private fun symbol(type: SymbolType) = Symbol(type, nextId++)

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
            if (tag.marker === Symbol.INVALID) {
                // A redundant required equality without a marker: nothing to add.
                cnMap[constraint] = tag
                optimize(objective)
                return
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
        if (!removable) throw UnsupportedOperationException("this solver does not support removing constraints")
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
        marker.row?.let { row ->
            if (row.add(-delta) < 0.0) infeasibleRows.add(marker)
            dualOptimize()
            return
        }
        // Check next if the negative error variable is basic.
        val other = info.tag.other
        other.row?.let { row ->
            if (row.add(delta) < 0.0) infeasibleRows.add(other)
            dualOptimize()
            return
        }
        // Otherwise update each row where the error variables exist.
        marker.column?.forEachKey { symbol ->
            val row = symbol.row!!
            val coefficient = row.coefficientFor(marker)
            if (row.add(delta * coefficient) < 0.0 && symbol.type != SymbolType.EXTERNAL) infeasibleRows.add(symbol)
        }
        dualOptimize()
    }

    /** Writes the solved values into the variables. */
    public fun updateVariables() {
        for ((variable, symbol) in varMap) {
            val v = symbol.row?.constant ?: 0.0
            variable.value = if (v == -0.0) 0.0 else v
        }
    }

    /** Removes every constraint and edit variable. */
    public fun reset() {
        cnMap.clear(); varMap.clear(); editMap.clear(); infeasibleRows.clear()
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
        (symbol.column ?: SymbolMap().also { symbol.column = it }).addKey(basic)
    }

    private fun columnRemove(symbol: Symbol, basic: Symbol) {
        val set = symbol.column ?: return
        set.remove(basic)
        if (set.size == 0) symbol.column = null
    }

    private fun takeColumn(symbol: Symbol): SymbolMap? = symbol.column.also { symbol.column = null }

    private fun putRow(basic: Symbol, row: Row) {
        basic.row = row
        row.cells.forEachKey { columnAdd(it, basic) }
    }

    private fun takeRow(basic: Symbol): Row? {
        val row = basic.row ?: return null
        basic.row = null
        row.cells.forEachKey { columnRemove(it, basic) }
        return row
    }

    private fun createRow(constraint: Constraint, tag: Tag): Row {
        val expr = constraint.expression
        val row = Row(expr.constant)
        for (term in expr.terms) {
            if (nearZero(term.coefficient)) continue
            val symbol = getVarSymbol(term.variable)
            val basic = symbol.row
            if (basic != null) row.insert(basic, term.coefficient) else row.insert(symbol, term.coefficient)
        }

        val strength = constraint.strength
        when (constraint.op) {
            RelationalOperator.LE, RelationalOperator.GE -> {
                val coefficient = if (constraint.op == RelationalOperator.LE) 1.0 else -1.0
                val slack = symbol(SymbolType.SLACK)
                tag.marker = slack
                row.insert(slack, coefficient)
                if (strength < Strength.required) {
                    val error = symbol(SymbolType.ERROR)
                    tag.other = error
                    row.insert(error, -coefficient)
                    objective.add(error, levels.of(strength), 1.0)
                }
            }
            RelationalOperator.EQ -> {
                if (strength < Strength.required) {
                    val errPlus = symbol(SymbolType.ERROR)
                    val errMinus = symbol(SymbolType.ERROR)
                    tag.marker = errPlus
                    tag.other = errMinus
                    row.insert(errPlus, -1.0)
                    row.insert(errMinus, 1.0)
                    val level = levels.of(strength)
                    objective.add(errPlus, level, 1.0)
                    objective.add(errMinus, level, 1.0)
                } else if (removable) {
                    val dummy = symbol(SymbolType.DUMMY)
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
        row.cells.firstKey { it.type == SymbolType.EXTERNAL }?.let { return it }
        if (tag.marker.type == SymbolType.SLACK || tag.marker.type == SymbolType.ERROR) {
            if (row.coefficientFor(tag.marker) < 0.0) return tag.marker
        }
        if (tag.other.type == SymbolType.SLACK || tag.other.type == SymbolType.ERROR) {
            if (row.coefficientFor(tag.other) < 0.0) return tag.other
        }
        return Symbol.INVALID
    }

    private fun addWithArtificialVariable(row: Row): Boolean {
        val art = symbol(SymbolType.SLACK)
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
            if (artRow.cells.size == 0) return true
            val entering = anyPivotableSymbol(artRow)
            if (entering === Symbol.INVALID) return false
            artRow.solveFor(art, entering)
            substitute(entering, artRow)
            putRow(entering, artRow)
        }

        takeColumn(art)?.forEachKey { it.row!!.remove(art) }
        objective.remove(art)
        return success
    }

    /**
     * Replaces [symbol] by [row] in every row that mentions it (and the objectives), keeping
     * the column index in step: each cell [row] adds, updates or cancels in a target row.
     */
    private fun substitute(symbol: Symbol, row: Row) {
        takeColumn(symbol)?.forEachKey { s ->
            val r = s.row!!
            val coefficient = r.cells.remove(symbol)
            if (coefficient != null) {
                r.constant += row.constant * coefficient
                val cells = row.cells
                for (i in 0 until cells.end) {
                    val c = cells.keyAt(i) ?: continue
                    if (r.cells.addTo(c, cells.valueAt(i) * coefficient)) columnAdd(c, s) else columnRemove(c, s)
                }
            } else {
                // Not in the row (cannot happen with a consistent index): only the index changes.
                row.cells.forEachKey { c -> if (r.cells.contains(c)) columnAdd(c, s) else columnRemove(c, s) }
            }
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
            val row = leaving.row
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
        val cells = row.cells
        for (i in 0 until cells.end) {
            val symbol = cells.keyAt(i) ?: continue
            val c = cells.valueAt(i)
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

    private fun anyPivotableSymbol(row: Row): Symbol =
        row.cells.firstKey { it.type == SymbolType.SLACK || it.type == SymbolType.ERROR } ?: Symbol.INVALID

    private fun getLeavingSymbol(entering: Symbol): Symbol {
        var ratio = Double.MAX_VALUE
        var found = Symbol.INVALID
        val column = entering.column ?: return found
        for (i in 0 until column.end) {
            val symbol = column.keyAt(i) ?: continue
            if (symbol.type == SymbolType.EXTERNAL) continue
            val row = symbol.row!!
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
        val column = marker.column ?: return Symbol.INVALID
        for (i in 0 until column.end) {
            val symbol = column.keyAt(i) ?: continue
            val row = symbol.row!!
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
        val row = marker.row
        if (row != null) objective.insertRow(row, level, -1.0) else objective.add(marker, level, -1.0)
    }

    private fun getVarSymbol(variable: Variable): Symbol =
        varMap.getOrPut(variable) { symbol(SymbolType.EXTERNAL) }

    // endregion
}

internal const val EPSILON: Double = 1.0e-8

internal fun nearZero(value: Double): Boolean = abs(value) < EPSILON

internal enum class SymbolType { INVALID, EXTERNAL, SLACK, ERROR, DUMMY }

/**
 * Identity-keyed tableau symbol. [id] is unique within its solver (the hash of [SymbolMap]).
 * A basic symbol carries its [row]; a parametric one its [column] (the basic symbols whose
 * rows mention it).
 */
internal class Symbol(val type: SymbolType, val id: Int) {
    var row: Row? = null
    var column: SymbolMap? = null

    override fun hashCode(): Int = id

    override fun equals(other: Any?): Boolean = this === other

    companion object {
        val INVALID = Symbol(SymbolType.INVALID, -1)
    }
}

/**
 * An insertion-ordered `Symbol → Double` map with primitive values (also used as an ordered
 * set). Iterates exactly like `LinkedHashMap` (removal keeps the order of the rest, a re-added
 * key goes last), so pivot choices match the map-based solver. Small maps are scanned
 * linearly; larger ones keep an open-addressing index from [Symbol.id] to the entry slot.
 * Removed entries leave holes, compacted when the arrays fill up.
 */
internal class SymbolMap(capacity: Int = 4) {
    private var keys = arrayOfNulls<Symbol>(capacity)
    private var values = DoubleArray(capacity)
    private var index: IntArray? = null

    /** One past the last used slot: iterate `0 until end`, skipping null [keyAt]. */
    var end: Int = 0
        private set
    var size: Int = 0
        private set

    fun keyAt(i: Int): Symbol? = keys[i]
    fun valueAt(i: Int): Double = values[i]

    inline fun forEachKey(action: (Symbol) -> Unit) {
        for (i in 0 until end) action(keyAt(i) ?: continue)
    }

    inline fun firstKey(predicate: (Symbol) -> Boolean): Symbol? {
        for (i in 0 until end) {
            val k = keyAt(i) ?: continue
            if (predicate(k)) return k
        }
        return null
    }

    private fun slotOf(key: Symbol): Int {
        val idx = index
        if (idx == null) {
            for (i in 0 until end) if (keys[i] === key) return i
            return -1
        }
        val mask = idx.size - 1
        var h = mix(key.id) and mask
        while (true) {
            val v = idx[h]
            if (v == EMPTY) return -1
            if (v >= 0 && keys[v] === key) return v
            h = (h + 1) and mask
        }
    }

    fun contains(key: Symbol): Boolean = slotOf(key) >= 0

    operator fun get(key: Symbol): Double? {
        val s = slotOf(key)
        return if (s < 0) null else values[s]
    }

    fun getOrZero(key: Symbol): Double {
        val s = slotOf(key)
        return if (s < 0) 0.0 else values[s]
    }

    fun set(key: Symbol, value: Double) {
        val s = slotOf(key)
        if (s >= 0) values[s] = value else append(key, value)
    }

    fun addKey(key: Symbol) {
        if (slotOf(key) < 0) append(key, 0.0)
    }

    /**
     * Adds [delta] to [key]'s value, dropping the entry when the sum is near zero
     * (`insert` in kiwi). Returns whether the key is present afterwards.
     */
    fun addTo(key: Symbol, delta: Double): Boolean {
        val s = slotOf(key)
        if (s < 0) {
            if (nearZero(delta)) return false
            append(key, delta)
            return true
        }
        val v = values[s] + delta
        if (nearZero(v)) {
            removeSlot(s, key)
            return false
        }
        values[s] = v
        return true
    }

    fun remove(key: Symbol): Double? {
        val s = slotOf(key)
        if (s < 0) return null
        val v = values[s]
        removeSlot(s, key)
        return v
    }

    fun scale(factor: Double) {
        for (i in 0 until end) if (keys[i] != null) values[i] *= factor
    }

    fun putAll(other: SymbolMap) {
        for (i in 0 until other.end) {
            val k = other.keys[i] ?: continue
            set(k, other.values[i])
        }
    }

    private fun removeSlot(s: Int, key: Symbol) {
        keys[s] = null
        size--
        val idx = index ?: return
        val mask = idx.size - 1
        var h = mix(key.id) and mask
        while (idx[h] != s) h = (h + 1) and mask
        idx[h] = DELETED
    }

    private fun append(key: Symbol, value: Double) {
        if (end == keys.size) {
            if (size * 2 <= keys.size) compact(keys.size) else compact(keys.size * 2)
        }
        keys[end] = key
        values[end] = value
        val idx = index
        if (idx != null) {
            val mask = idx.size - 1
            var h = mix(key.id) and mask
            while (idx[h] >= 0) h = (h + 1) and mask
            idx[h] = end
        }
        end++
        size++
        if (idx == null && end > LINEAR_MAX) rebuildIndex()
    }

    /** Moves the live entries to the front (order kept) of arrays of [capacity]. */
    private fun compact(capacity: Int) {
        val nk = if (capacity == keys.size) keys else arrayOfNulls(capacity)
        val nv = if (capacity == values.size) values else DoubleArray(capacity)
        var j = 0
        for (i in 0 until end) {
            val k = keys[i] ?: continue
            nk[j] = k
            nv[j] = values[i]
            j++
        }
        for (i in j until end) nk[i] = null
        keys = nk
        values = nv
        end = j
        if (index != null || end > LINEAR_MAX) rebuildIndex()
    }

    private fun rebuildIndex() {
        var n = 16
        while (n < keys.size * 2) n = n shl 1
        val idx = index?.takeIf { it.size == n }?.also { it.fill(EMPTY) } ?: IntArray(n).also { it.fill(EMPTY) }
        val mask = n - 1
        for (i in 0 until end) {
            val k = keys[i] ?: continue
            var h = mix(k.id) and mask
            while (idx[h] >= 0) h = (h + 1) and mask
            idx[h] = i
        }
        index = idx
    }

    private companion object {
        const val EMPTY = -1
        const val DELETED = -2
        const val LINEAR_MAX = 8

        fun mix(id: Int): Int = id * -0x61c88647
    }
}

/** A tableau row: `basic = constant + sum(cells)`. */
internal class Row(var constant: Double = 0.0, val cells: SymbolMap = SymbolMap()) {
    fun copy(): Row = Row(constant, SymbolMap(maxOf(4, cells.size)).also { it.putAll(cells) })

    fun add(value: Double): Double {
        constant += value
        return constant
    }

    fun allDummies(): Boolean = cells.firstKey { it.type != SymbolType.DUMMY } == null

    fun insert(symbol: Symbol, coefficient: Double = 1.0) {
        cells.addTo(symbol, coefficient)
    }

    fun insert(other: Row, coefficient: Double = 1.0) {
        constant += other.constant * coefficient
        val o = other.cells
        for (i in 0 until o.end) {
            val s = o.keyAt(i) ?: continue
            cells.addTo(s, o.valueAt(i) * coefficient)
        }
    }

    fun remove(symbol: Symbol) {
        cells.remove(symbol)
    }

    fun reverseSign() {
        constant = -constant
        cells.scale(-1.0)
    }

    fun solveFor(symbol: Symbol) {
        val coefficient = -1.0 / cells.remove(symbol)!!
        constant *= coefficient
        cells.scale(coefficient)
    }

    fun solveFor(lhs: Symbol, rhs: Symbol) {
        insert(lhs, -1.0)
        solveFor(rhs)
    }

    fun coefficientFor(symbol: Symbol): Double = cells.getOrZero(symbol)

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
        val cells = row.cells
        for (i in 0 until cells.end) add(cells.keyAt(i) ?: continue, level, cells.valueAt(i) * coefficient)
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
        val cells = row.cells
        for (j in 0 until cells.end) {
            val s = cells.keyAt(j) ?: continue
            val c = cells.valueAt(j)
            val target = vector(s, v.size)
            for (i in v.indices) target[i] += v[i] * c
            prune(s, target)
        }
    }
}
