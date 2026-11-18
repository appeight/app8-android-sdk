package dev.app8.engine.core.expr

import dev.app8.engine.core.vars.Values

// sortBy (app8-dsl docs/variables.md): stable, numbers numerically before strings (ascending),
// strings by Unicode code point, descending reverses that, missing values last either way.

private sealed interface SortKey {
    data class Num(val v: Double) : SortKey
    data class Str(val v: String) : SortKey
}

private fun sortKey(v: Any?): SortKey? = when (v) {
    is Boolean -> null
    is Number -> v.toDouble().takeIf { it.isFinite() }?.let { SortKey.Num(it) }
    is String -> SortKey.Str(v)
    else -> null
}

private fun valueAt(element: Any?, path: String): Any? {
    var current = element
    for (part in path.split('.')) current = Values.asMap(current)?.get(part) ?: return null
    return current
}

private fun compareCodePoints(a: String, b: String): Int {
    val x = a.codePoints().toArray()
    val y = b.codePoints().toArray()
    for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return if (x[i] < y[i]) -1 else 1
    return x.size.compareTo(y.size)
}

internal fun sortBy(array: List<Any?>, key: String, ascending: Boolean): List<Any?> {
    val keyed = array.mapIndexed { i, e -> Triple(i, e, sortKey(valueAt(e, key))) }
    return keyed.sortedWith { a, b ->
        val ka = a.third
        val kb = b.third
        if (ka == null || kb == null) {
            if ((ka == null) == (kb == null)) a.first.compareTo(b.first) else if (ka != null) -1 else 1
        } else {
            var c = when {
                ka is SortKey.Num && kb is SortKey.Num -> ka.v.compareTo(kb.v)
                ka is SortKey.Str && kb is SortKey.Str -> compareCodePoints(ka.v, kb.v)
                ka is SortKey.Num -> -1
                else -> 1
            }
            if (!ascending) c = -c
            if (c != 0) c else a.first.compareTo(b.first)
        }
    }.map { it.second }
}
