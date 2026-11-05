package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableException
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Evaluates parsed [ExpressionNode]s against a [VariableContext] (Swift `ExpressionEvaluator`).
 *
 * Values follow the canonical model documented on [Values]. Like the `@MainActor` Swift class,
 * an instance is not thread-safe (it keeps a recursion counter); confine it to one thread.
 */
public class ExpressionEvaluator {

    /**
     * Locale for `formatDate`/`formatTime`/`formatCurrency`/`formatNumber`.
     * Defaults to the JVM default (Swift `Locale.current`); `PropertyResolver` overrides it from
     * its translation provider before each resolve.
     */
    public var locale: Locale = Locale.getDefault()

    /** Backs `i18n(key)`; a miss (or no lookup) returns the key itself. */
    public var translationLookup: ((String) -> String?)? = null

    /**
     * Source of "now" and of the local time zone (Swift uses `Date()` / `Calendar.current`).
     * Android-only addition so date functions are testable; defaults to the system clock/zone.
     */
    public var clock: Clock = Clock.systemDefaultZone()

    private var evalDepth = 0

    public fun evaluate(node: ExpressionNode, context: VariableContext): Any? {
        evalDepth++
        try {
            if (evalDepth > EngineLimits.maxExpressionDepth) {
                throw ExpressionException.ParseError("expression too deeply nested to evaluate")
            }
            return when (node) {
                is ExpressionNode.Literal -> node.value.anyValue
                is ExpressionNode.Variable -> {
                    val value = context.getValue(node.name)
                    when {
                        value != null -> value
                        // Defined but nil (e.g. computed first() with no match) → nil, not an error.
                        context.variableExists(node.name) -> null
                        node.name == "event" -> null
                        else -> throw VariableException.UndefinedVariable(node.name)
                    }
                }
                is ExpressionNode.BinaryOperation -> evaluateBinary(node.operator, node.left, node.right, context)
                is ExpressionNode.UnaryOperation -> evaluateUnary(node.operator, node.operand, context)
                is ExpressionNode.FunctionCall -> evaluateFunctionCall(node.name, node.arguments, context)
                is ExpressionNode.MemberAccess -> evaluateMemberAccess(node.obj, node.member, context)
                is ExpressionNode.ArrayAccess -> evaluateArrayAccess(node.array, node.index, context)
                is ExpressionNode.Ternary -> {
                    val condition = evaluate(node.condition, context)
                    evaluate(if (toBool(condition)) node.trueValue else node.falseValue, context)
                }
                // A nil element becomes null so positions are preserved.
                is ExpressionNode.ArrayLiteral -> node.elements.map { evaluate(it, context) ?: Values.Null }
            }
        } finally {
            evalDepth--
        }
    }

    // region Operators

    private fun evaluateBinary(op: String, left: ExpressionNode, right: ExpressionNode, context: VariableContext): Any? {
        val l = evaluate(left, context)
        when (op) {
            "&&" -> return if (!toBool(l)) false else toBool(evaluate(right, context))
            "||" -> return if (toBool(l)) true else toBool(evaluate(right, context))
        }
        val r = evaluate(right, context)
        return when (op) {
            "===" -> isStrictlyEqual(l, r)
            "!==" -> !isStrictlyEqual(l, r)
            "==" -> isLooselyEqual(l, r)
            "!=" -> !isLooselyEqual(l, r)
            ">" -> compare(l, r) > 0
            ">=" -> compare(l, r) >= 0
            "<" -> compare(l, r) < 0
            "<=" -> compare(l, r) <= 0
            "+" -> add(l, r)
            "-" -> numeric(l, r, "Cannot subtract non-numeric values") { a, b -> Values.wholeOrDouble(a - b) }
            "*" -> numeric(l, r, "Cannot multiply non-numeric values") { a, b -> Values.wholeOrDouble(a * b) }
            "/" -> numeric(l, r, "Cannot divide non-numeric values") { a, b ->
                if (b == 0.0) throw ExpressionException.ParseError("Division by zero")
                a / b
            }
            "%" -> numeric(l, r, "Cannot modulo non-numeric values") { a, b ->
                if (b == 0.0) throw ExpressionException.ParseError("Modulo by zero")
                Values.wholeOrDouble(a % b)
            }
            else -> throw ExpressionException.ParseError("Unknown operator: $op")
        }
    }

    private inline fun numeric(l: Any?, r: Any?, error: String, f: (Double, Double) -> Any): Any {
        val a = toNumber(l)
        val b = toNumber(r)
        if (a == null || b == null) throw ExpressionException.ParseError(error)
        return f(a, b)
    }

    private fun evaluateUnary(op: String, operand: ExpressionNode, context: VariableContext): Any? {
        val value = evaluate(operand, context)
        return when (op) {
            "!" -> !toBool(value)
            "-" -> {
                val num = toNumber(value) ?: throw ExpressionException.ParseError("Cannot negate non-numeric value")
                Values.wholeOrDouble(-num)
            }
            else -> throw ExpressionException.ParseError("Unknown unary operator: $op")
        }
    }

    private fun add(l: Any?, r: Any?): Any {
        if (l is String || r is String) return toString(l) + toString(r)
        val a = toNumber(l)
        val b = toNumber(r)
        if (a != null && b != null) return Values.wholeOrDouble(a + b)
        val la = Values.asList(l)
        val ra = Values.asList(r)
        if (la != null && ra != null) return la + ra
        throw ExpressionException.ParseError("Cannot add values of incompatible types")
    }

    // endregion

    // region Functions

    private fun argCount(valid: Boolean, message: String) {
        if (!valid) throw ExpressionException.ParseError(message)
    }

    private fun evaluateFunctionCall(name: String, args: List<ExpressionNode>, context: VariableContext): Any? {
        if (name !in ALLOWED_FUNCTIONS) throw ExpressionException.ParseError("Function '$name' is not allowed")
        fun arg(i: Int): Any? = evaluate(args[i], context)
        fun stringArg(i: Int): String = arg(i) as? String ?: ""
        fun exactly(n: Int, message: String) = argCount(args.size == n, message)
        fun between(range: IntRange, message: String) = argCount(args.size in range, message)
        fun atLeast(n: Int, message: String) = argCount(args.size >= n, message)

        when (name) {
            "length" -> {
                exactly(1, "length() expects 1 argument")
                return when (val value = arg(0)) {
                    is String -> SwiftCompat.characterCount(value).toLong()
                    is List<*> -> value.size.toLong()
                    is Map<*, *> -> value.size.toLong()
                    else -> 0L
                }
            }
            "includes" -> {
                exactly(2, "includes() expects 2 arguments")
                val collection = arg(0)
                val search = arg(1)
                if (collection is String && search is String) {
                    if (search.isEmpty()) return true
                    return collection.contains(search)
                }
                val array = Values.asList(collection) ?: return false
                return array.any { isLooselyEqual(it, search) }
            }
            "match" -> {
                exactly(2, "match() expects 2 arguments")
                val string = stringArg(0)
                val pattern = stringArg(1)
                if (SwiftCompat.characterCount(pattern) > EngineLimits.maxRegexPatternLength ||
                    string.length > EngineLimits.maxRegexInputLength
                ) {
                    return false
                }
                if (hasNestedQuantifier(pattern)) return false
                return try {
                    Pattern.compile(pattern).matcher(string).find()
                } catch (_: PatternSyntaxException) {
                    false
                }
            }
            "filter" -> {
                atLeast(1, "filter() expects at least 1 argument")
                val array = Values.asList(arg(0)) ?: emptyList()
                if (args.size < 2) return array
                return array.filter { toBool(evaluate(args[1], context.overlaying("item", it))) }
            }
            "map" -> {
                atLeast(1, "map() expects at least 1 argument")
                val array = Values.asList(arg(0)) ?: emptyList()
                if (args.size < 2) return array
                return array.map { evaluate(args[1], context.overlaying("item", it)) }
            }
            "find", "first" -> {
                atLeast(1, "$name() expects at least 1 argument")
                val array = Values.asList(arg(0)) ?: emptyList()
                if (args.size < 2) return array.firstOrNull()
                return array.firstOrNull { toBool(evaluate(args[1], context.overlaying("item", it))) }
            }
            "last" -> {
                atLeast(1, "last() expects at least 1 argument")
                val array = Values.asList(arg(0)) ?: emptyList()
                if (args.size < 2) return array.lastOrNull()
                return array.lastOrNull { toBool(evaluate(args[1], context.overlaying("item", it))) }
            }
            "parseInt" -> {
                atLeast(1, "parseInt() expects at least 1 argument")
                val value = arg(0)
                Values.asInt(value)?.let { return it }
                Values.asDouble(value)?.let { return SwiftCompat.toIntTruncating(it) }
                if (value is String) return SwiftCompat.parseInt(value) ?: 0L
                return 0L
            }
            "parseFloat" -> {
                atLeast(1, "parseFloat() expects at least 1 argument")
                val value = arg(0)
                Values.asDouble(value)?.let { return it }
                Values.asInt(value)?.let { return it.toDouble() }
                if (value is String) return SwiftCompat.parseDouble(value) ?: 0.0
                return 0.0
            }
            "toString" -> {
                exactly(1, "toString() expects 1 argument")
                return toString(arg(0))
            }
            "isArray" -> {
                exactly(1, "isArray() expects 1 argument")
                return arg(0) is List<*>
            }
            "keys" -> {
                exactly(1, "keys() expects 1 argument")
                return Values.asMap(arg(0))?.keys?.toList() ?: emptyList<String>()
            }
            "values" -> {
                exactly(1, "values() expects 1 argument")
                return Values.asMap(arg(0))?.values?.toList() ?: emptyList<Any?>()
            }

            // String functions
            "uppercase" -> {
                exactly(1, "uppercase() expects 1 argument")
                return (arg(0) as? String)?.uppercase(Locale.ROOT) ?: ""
            }
            "lowercase" -> {
                exactly(1, "lowercase() expects 1 argument")
                return (arg(0) as? String)?.lowercase(Locale.ROOT) ?: ""
            }
            "trim" -> {
                exactly(1, "trim() expects 1 argument")
                return (arg(0) as? String)?.let { SwiftCompat.trimWhitespacesAndNewlines(it) } ?: ""
            }
            "replace" -> {
                exactly(3, "replace() expects 3 arguments (string, from, to)")
                val s = stringArg(0)
                val from = stringArg(1)
                val to = stringArg(2)
                if (from.isEmpty()) return s
                return s.replace(from, to)
            }
            "split" -> {
                exactly(2, "split() expects 2 arguments (string, separator)")
                val s = stringArg(0)
                val sep = stringArg(1)
                if (sep.isEmpty()) return SwiftCompat.characters(s)
                return s.split(sep)
            }
            "substring" -> {
                between(2..3, "substring() expects 2 or 3 arguments (string, start, length?)")
                val s = stringArg(0)
                val start = toNumber(arg(1)) ?: return ""
                val count = SwiftCompat.characterCount(s)
                val startIdx = if (start.isNaN()) 0 else start.coerceIn(0.0, count.toDouble()).toInt()
                val from = SwiftCompat.utf16Offset(s, startIdx)
                if (args.size == 3) {
                    val length = toNumber(arg(2)) ?: return ""
                    val safeLength = if (length.isNaN() || length < 0) 0 else minOf(length, count.toDouble()).toInt()
                    val endIdx = minOf(startIdx + safeLength, count)
                    return s.substring(from, SwiftCompat.utf16Offset(s, endIdx))
                }
                return s.substring(from)
            }
            "startsWith" -> {
                exactly(2, "startsWith() expects 2 arguments")
                return stringArg(0).startsWith(stringArg(1))
            }
            "endsWith" -> {
                exactly(2, "endsWith() expects 2 arguments")
                return stringArg(0).endsWith(stringArg(1))
            }

            // Array functions
            "sort" -> {
                between(1..2, "sort() expects 1 or 2 arguments (array, ascending?)")
                val arr = Values.asList(arg(0)) ?: emptyList()
                val ascending = if (args.size == 2) (arg(1) as? Boolean ?: true) else true
                return swiftSorted(arr) { a, b ->
                    val cmp = compareForSort(a, b)
                    if (ascending) cmp < 0 else cmp > 0
                }
            }
            "reverse" -> {
                exactly(1, "reverse() expects 1 argument")
                return (Values.asList(arg(0)) ?: emptyList()).reversed()
            }
            "join" -> {
                exactly(2, "join() expects 2 arguments (array, separator)")
                val arr = Values.asList(arg(0)) ?: emptyList()
                val sep = stringArg(1)
                return arr.joinToString(sep) { Values.stringify(it) }
            }
            "sum" -> {
                exactly(1, "sum() expects 1 argument (array of numbers)")
                val arr = Values.asList(arg(0)) ?: emptyList()
                val total = arr.mapNotNull { toNumber(it) }.fold(0.0) { acc, d -> acc + d }
                return Values.wholeOrDouble(total)
            }
            "concat" -> {
                atLeast(2, "concat() expects at least 2 arguments")
                val result = ArrayList<Any?>()
                for (a in args) Values.asList(evaluate(a, context))?.let { result.addAll(it) }
                return result
            }
            "slice" -> {
                between(2..3, "slice() expects 2 or 3 arguments (array, start, end?)")
                val arr = Values.asList(arg(0)) ?: emptyList()
                val start = toNumber(arg(1)) ?: return emptyList<Any?>()
                val startIdx = SwiftCompat.toIntTruncating(start).coerceIn(0L, arr.size.toLong()).toInt()
                val endIdx = if (args.size == 3) {
                    val e = toNumber(arg(2)) ?: return emptyList<Any?>()
                    maxOf(startIdx.toLong(), minOf(SwiftCompat.toIntTruncating(e), arr.size.toLong())).toInt()
                } else {
                    arr.size
                }
                return arr.subList(startIdx, endIdx).toList()
            }
            "round" -> {
                between(1..2, "round() expects 1 or 2 arguments")
                val value = arg(0)
                if (args.size == 2) {
                    val decimals = arg(1)
                    val num = toNumber(value)
                    val dec = toNumber(decimals)
                    if (num != null && dec != null) {
                        val factor = 10.0.pow(dec)
                        return SwiftCompat.roundHalfAwayFromZero(num * factor) / factor
                    }
                    return value
                }
                val num = toNumber(value) ?: return 0L
                return SwiftCompat.toIntTruncating(SwiftCompat.roundHalfAwayFromZero(num))
            }
            "floor" -> {
                exactly(1, "floor() expects 1 argument")
                return toNumber(arg(0))?.let { SwiftCompat.toIntTruncating(floor(it)) } ?: 0L
            }
            "ceil" -> {
                exactly(1, "ceil() expects 1 argument")
                return toNumber(arg(0))?.let { SwiftCompat.toIntTruncating(ceil(it)) } ?: 0L
            }
            "abs" -> {
                exactly(1, "abs() expects 1 argument")
                return toNumber(arg(0))?.let { Values.wholeOrDouble(abs(it)) } ?: 0L
            }
            "min", "max" -> {
                atLeast(2, "$name() expects at least 2 arguments")
                var result: Double? = null
                for (a in args) {
                    val num = toNumber(evaluate(a, context)) ?: continue
                    val current = result
                    result = if (current == null) num else if (name == "min") swiftMin(current, num) else swiftMax(current, num)
                }
                return result?.let { Values.wholeOrDouble(it) } ?: 0L
            }

            // Formatting — date & time
            "formatDate" -> {
                between(1..2, "formatDate() expects 1 or 2 arguments")
                val date = Formatting.parseDate(arg(0), clock.zone) ?: return ""
                val style = if (args.size == 2) toString(arg(1)) else "medium"
                val pattern = when (style) {
                    "short" -> "MMM d"
                    "medium" -> "MMM d, yyyy"
                    "long" -> "MMMM d, yyyy"
                    "weekday" -> "EEEE, MMM d"
                    "weekdayShort" -> "EEE"
                    else -> style
                }
                return Formatting.formatDate(date, pattern, locale, clock.zone)
            }
            "formatTime" -> {
                between(1..2, "formatTime() expects 1 or 2 arguments")
                val date = Formatting.parseDate(arg(0), clock.zone) ?: return ""
                val timeFormat = if (args.size == 2) toString(arg(1)) else "12h"
                return Formatting.formatDate(date, if (timeFormat == "24h") "HH:mm" else "h:mm a", locale, clock.zone)
            }
            "formatDuration" -> {
                exactly(1, "formatDuration() expects 1 argument")
                val totalSeconds = SwiftCompat.toIntTruncating(toNumber(arg(0)) ?: 0.0)
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                val s = totalSeconds % 60
                return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
            }
            "formatMinutes" -> {
                exactly(1, "formatMinutes() expects 1 argument")
                val total = SwiftCompat.toIntTruncating(toNumber(arg(0)) ?: 0.0)
                val h = total / 60
                val m = total % 60
                if (h == 0L) return "$total min"
                if (m == 0L) return "${h}h"
                return "${h}h ${m}min"
            }
            "ageInYears" -> {
                exactly(1, "ageInYears() expects 1 argument")
                val birth = Formatting.parseDate(arg(0), clock.zone) ?: return 0L
                val years = ChronoUnit.YEARS.between(birth.atZone(clock.zone), clock.instant().atZone(clock.zone))
                return maxOf(0L, years)
            }
            "daysBetween" -> {
                exactly(2, "daysBetween() expects 2 arguments")
                val startVal = arg(0)
                val endVal = arg(1)
                val start = Formatting.parseDate(startVal, clock.zone) ?: return 0L
                val end = Formatting.parseDate(endVal, clock.zone) ?: return 0L
                return abs(ChronoUnit.DAYS.between(start.atZone(clock.zone), end.atZone(clock.zone)))
            }
            "timeAgo" -> {
                exactly(1, "timeAgo() expects 1 argument")
                val date = Formatting.parseDate(arg(0), clock.zone) ?: return ""
                val seconds = Duration.between(date, clock.instant()).toMillis() / 1000
                if (seconds < 60) return "just now"
                val minutes = seconds / 60
                if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"} ago"
                val hours = minutes / 60
                if (hours < 24) return "$hours hour${if (hours == 1L) "" else "s"} ago"
                val days = hours / 24
                if (days == 1L) return "yesterday"
                return "$days days ago"
            }
            "daysUntil" -> {
                exactly(1, "daysUntil() expects 1 argument")
                val future = Formatting.parseDate(arg(0), clock.zone) ?: return 0L
                val today = clock.instant().atZone(clock.zone).toLocalDate()
                val target = future.atZone(clock.zone).toLocalDate()
                return ChronoUnit.DAYS.between(today, target)
            }

            // Formatting — number & currency
            "formatCurrency" -> {
                between(1..2, "formatCurrency() expects 1 or 2 arguments")
                val num = toNumber(arg(0)) ?: return ""
                val code = if (args.size == 2) toString(arg(1)) else null
                return Formatting.formatCurrency(num, locale, code)
            }
            "formatNumber" -> {
                between(1..2, "formatNumber() expects 1 or 2 arguments")
                val num = toNumber(arg(0)) ?: return ""
                val style = if (args.size == 2) toString(arg(1)) else "decimal"
                if (style == "percent") {
                    return "${SwiftCompat.toIntTruncating(SwiftCompat.roundHalfAwayFromZero(num * 100))}%"
                }
                return Formatting.formatDecimal(num, locale)
            }

            // Formatting — string
            "plural" -> {
                exactly(3, "plural() expects 3 arguments: count, singular, plural")
                val countVal = arg(0)
                val singular = toString(arg(1))
                val plural = toString(arg(2))
                val count = SwiftCompat.toIntTruncating(toNumber(countVal) ?: 0.0)
                return if (count == 1L) "$count $singular" else "$count $plural"
            }
            "i18n" -> {
                exactly(1, "i18n() expects 1 argument: key")
                val key = toString(arg(0))
                return translationLookup?.invoke(key) ?: key
            }
            else -> throw ExpressionException.ParseError("Unknown function: $name")
        }
    }

    /**
     * Swift's `sorted(by:)`: arrays shorter than the minimum merge run (64) are insertion-sorted
     * exactly like the stdlib, so results match iOS even for inconsistent orderings (mixed types).
     * Longer arrays use a stable merge sort, which agrees whenever the ordering is consistent.
     */
    private fun swiftSorted(arr: List<Any?>, areInIncreasingOrder: (Any?, Any?) -> Boolean): List<Any?> {
        val a = arr.toMutableList()
        if (a.size >= 64) {
            try {
                return a.sortedWith { x, y -> if (areInIncreasingOrder(x, y)) -1 else if (areInIncreasingOrder(y, x)) 1 else 0 }
            } catch (_: IllegalArgumentException) {
                // TimSort detected an inconsistent ordering; fall back to insertion sort (never throws).
            }
        }
        for (sortedEnd in 1 until a.size) {
            var i = sortedEnd
            do {
                val j = i - 1
                if (!areInIncreasingOrder(a[i], a[j])) break
                val t = a[i]; a[i] = a[j]; a[j] = t
                i--
            } while (i != 0)
        }
        return a
    }

    /** Swift.min / Swift.max semantics with NaN: returns the first argument unless the second is smaller/larger. */
    private fun swiftMin(a: Double, b: Double): Double = if (b < a) b else a
    private fun swiftMax(a: Double, b: Double): Double = if (b > a) b else a

    // endregion

    // region Access

    private fun evaluateMemberAccess(obj: ExpressionNode, member: String, context: VariableContext): Any? {
        val value = evaluate(obj, context)
        Values.asMap(value)?.let { return it[member] }
        if (value is String) {
            if (member == "length") return SwiftCompat.characterCount(value).toLong()
        } else if (value is List<*>) {
            if (member == "length") return value.size.toLong()
        }
        return null
    }

    private fun evaluateArrayAccess(array: ExpressionNode, index: ExpressionNode, context: VariableContext): Any? {
        val arrayValue = evaluate(array, context)
        val indexValue = evaluate(index, context)
        val list = Values.asList(arrayValue) ?: return null
        val idx = toNumber(indexValue)?.let { SwiftCompat.toIntTruncating(it) }
            ?: throw ExpressionException.ParseError("Array index must be a number")
        if (idx < 0 || idx >= list.size) return null
        return list[idx.toInt()]
    }

    // endregion

    // region Coercion

    private fun toBool(value: Any?): Boolean {
        if (value is Boolean) return value
        Values.asNSNumber(value)?.let { return it != 0.0 }
        if (value is String) return value.isNotEmpty()
        if (value is List<*>) return value.isNotEmpty()
        if (value is Map<*, *>) return value.isNotEmpty()
        if (value == null || value === Values.Null) return false
        return true
    }

    private fun toNumber(value: Any?): Double? {
        Values.asDouble(value)?.let { return it }
        Values.asInt(value)?.let { return it.toDouble() }
        Values.asNSNumber(value)?.let { return it }
        if (value is String) return SwiftCompat.parseDouble(value)
        return null
    }

    /**
     * Swift `toString(_:)`. Arrays / objects → compact JSON with sorted keys (JS
     * `JSON.stringify` shape), e.g. `[1,"a",true]`, `{"a":"x","b":1}`.
     */
    private fun toString(value: Any?): String {
        if (value is String) return value
        if (value is Boolean) return if (value) "true" else "false"
        Values.asInt(value)?.let { return it.toString() }
        Values.asDouble(value)?.let { return SwiftCompat.doubleDescription(it) }
        if (value == null || value === Values.Null) return ""
        if (value is List<*> || value is Map<*, *>) CompactJson.encode(value)?.let { return it }
        return Values.describe(value)
    }

    private fun compareForSort(a: Any?, b: Any?): Int {
        val na = toNumber(a)
        val nb = toNumber(b)
        if (na != null && nb != null) {
            return if (na < nb) -1 else if (na > nb) 1 else 0
        }
        return SwiftCompat.compareScalars(Values.stringify(a), Values.stringify(b)).coerceIn(-1, 1)
    }

    private fun isStrictlyEqual(left: Any?, right: Any?): Boolean {
        if (left == null && right == null) return true
        if (left === Values.Null && right === Values.Null) return true
        if (left == null || right == null) return false
        if (left === Values.Null || right === Values.Null) return false
        if (left is String && right is String) return left == right
        if (left is Boolean && right is Boolean) return left == right
        val li = Values.asInt(left)
        val ri = Values.asInt(right)
        if (li != null && ri != null) return li == ri
        val ld = Values.asDouble(left)
        val rd = Values.asDouble(right)
        if (ld != null && rd != null) return ld == rd
        val la = Values.asList(left)
        val ra = Values.asList(right)
        if (la != null && ra != null) {
            return la.size == ra.size && la.indices.all { isStrictlyEqual(la[it], ra[it]) }
        }
        val lm = Values.asMap(left)
        val rm = Values.asMap(right)
        if (lm != null && rm != null) {
            return lm.size == rm.size && lm.all { (k, v) -> rm.containsKey(k) && isStrictlyEqual(v, rm[k]) }
        }
        return false
    }

    private fun isLooselyEqual(left: Any?, right: Any?): Boolean {
        if (Values.isNullish(left) && Values.isNullish(right)) return true
        val ln = toNumber(left)
        val rn = toNumber(right)
        if (ln != null && rn != null) return ln == rn
        if (left is String || right is String) return toString(left) == toString(right)
        if (left is Boolean && right is Boolean) return left == right
        return false
    }

    /** NSString `compare` — UTF-16 ordinal comparison, normalised to -1/0/1. */
    private fun compare(left: Any?, right: Any?): Int {
        val ln = toNumber(left)
        val rn = toNumber(right)
        if (ln != null && rn != null) return if (ln < rn) -1 else if (ln > rn) 1 else 0
        return toString(left).compareTo(toString(right)).coerceIn(-1, 1)
    }

    // endregion

    public companion object {
        private val ALLOWED_FUNCTIONS = setOf(
            "length", "includes", "match", "filter", "map", "find", "first", "last",
            "parseInt", "parseFloat", "toString",
            "isArray", "keys", "values",
            "round", "floor", "ceil", "abs", "min", "max",
            "formatDate", "formatTime", "formatDuration", "formatMinutes",
            "ageInYears", "daysBetween", "timeAgo", "daysUntil",
            "formatCurrency", "formatNumber", "plural",
            "i18n",
            "uppercase", "lowercase", "trim", "replace", "split",
            "substring", "startsWith", "endsWith",
            "sort", "reverse", "join", "concat", "slice", "sum",
        )

        /**
         * Heuristic ReDoS guard: true when [pattern] has a quantified group whose body is also
         * quantified (`(a+)+`, `(.*)*`) — the catastrophic-backtracking shape.
         */
        public fun hasNestedQuantifier(pattern: String): Boolean {
            val chars = pattern.codePoints().toArray()
            val groupHasQuantifier = ArrayList<Boolean>()
            var i = 0
            while (i < chars.size) {
                val c = chars[i]
                if (c == '\\'.code) {
                    i += 2
                    continue
                }
                when (c) {
                    '('.code -> groupHasQuantifier.add(false)
                    '+'.code, '*'.code, '{'.code -> if (groupHasQuantifier.isNotEmpty()) {
                        groupHasQuantifier[groupHasQuantifier.size - 1] = true
                    }
                    ')'.code -> {
                        val innerQuantified = if (groupHasQuantifier.isEmpty()) false else groupHasQuantifier.removeAt(groupHasQuantifier.size - 1)
                        val next = chars.getOrNull(i + 1)
                        if (innerQuantified && (next == '+'.code || next == '*'.code || next == '{'.code)) return true
                    }
                }
                i++
            }
            return false
        }
    }
}
