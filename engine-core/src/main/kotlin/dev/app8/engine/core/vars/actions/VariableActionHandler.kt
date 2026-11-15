package dev.app8.engine.core.vars.actions

import dev.app8.engine.core.expr.EngineLimits
import dev.app8.engine.core.vars.PropertyResolver
import dev.app8.engine.core.vars.Values
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.core.vars.VariableException
import dev.app8.engine.core.vars.VariableLogger
import dev.app8.engine.core.vars.VariableStoreProtocol
import dev.app8.engine.core.vars.VariableType

/** Executes variable-related DSL actions (Swift `VariableActionHandler`). Confine to the main thread. */
public class VariableActionHandler {
    private val propertyResolver = PropertyResolver()

    /** Optional diagnostics sink. */
    public var logger: VariableLogger? = null

    /**
     * Executes [action] against [store], resolving `{{…}}` in values with [context].
     * Non-variable action types are ignored.
     * @throws VariableException (and expression errors from whole-string value expressions)
     */
    public fun execute(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        when (action.actionType) {
            VariableActionType.UPDATE_VARIABLE -> handleUpdateVariable(action, store, context)
            VariableActionType.INCREMENT_VARIABLE -> handleIncrementVariable(action, store, context)
            VariableActionType.TOGGLE_ARRAY_VALUE -> handleToggleArrayValue(action, store, context)
            VariableActionType.APPEND_TO_ARRAY -> handleAppendToArray(action, store, context)
            VariableActionType.INCREMENT_ARRAY_ITEM -> handleIncrementArrayItem(action, store, context)
            VariableActionType.INSERT_ARRAY_ITEM -> handleInsertArrayItem(action, store, context)
            VariableActionType.UPSERT_ARRAY_ITEM -> handleUpsertArrayItem(action, store, context)
            VariableActionType.REMOVE_ARRAY_ITEM -> handleRemoveArrayItem(action, store, context)
            VariableActionType.UPDATE_MULTIPLE_VARIABLES -> handleUpdateMultipleVariables(action, store, context)
            VariableActionType.RESET_VARIABLES -> handleResetVariables(action, store)
            null -> Unit
        }
    }

    private fun handleUpdateVariable(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in updateVariable action")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in updateVariable action for '$variableName'")
        val resolved = resolveValue(value.value, context)
        val rawKey = action.key
        if (rawKey == null) {
            store.setValue(variableName, resolved)
            return
        }
        // `key`: write one entry of an object variable, keep the others.
        val key = if (rawKey.contains("{{")) propertyResolver.resolveToString(rawKey, context) else rawKey
        val current = Values.asMap(store.getValue(variableName)) ?: emptyMap()
        store.setValue(variableName, LinkedHashMap(current).apply { put(key, resolved) })
    }

    private fun handleIncrementVariable(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in incrementVariable action")
        val incrementBy = by(action, context)
        logger?.debug("handleIncrementVariable: '$variableName' by $incrementBy")

        val currentValue = store.getValue(variableName) ?: run {
            logger?.debug("Variable '$variableName' not found in store. hasVariable: ${store.hasVariable(variableName)}")
            throw VariableException.UndefinedVariable(variableName)
        }

        val intValue = Values.asInt(currentValue)
        val doubleValue = Values.asDouble(currentValue)
        val nsNumber = Values.asNSNumber(currentValue)
        val newValue: Any = when {
            // Promote to Double when incrementBy is fractional to avoid silent truncation.
            intValue != null -> if (incrementBy % 1.0 == 0.0) intValue + incrementBy.toLong() else intValue.toDouble() + incrementBy
            doubleValue != null -> doubleValue + incrementBy
            nsNumber != null -> nsNumber + incrementBy
            else -> throw VariableException.TypeMismatch(variableName, VariableType.NUMBER, Values.swiftTypeName(currentValue))
        }
        store.setValue(variableName, newValue)
    }

    private fun handleToggleArrayValue(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in toggleArrayValue action")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in toggleArrayValue action for '$variableName'")

        val currentValue = store.getValue(variableName)
        val currentArray = Values.asList(currentValue)
            ?: throw VariableException.TypeMismatch(variableName, VariableType.ARRAY, Values.swiftTypeName(currentValue))

        val resolvedValue = resolveValue(value.value, context)
        val matchKey = action.matchBy
        val foundIndex = if (matchKey != null) {
            currentArray.indexOfFirst { element ->
                val dict = Values.asMap(element) ?: return@indexOfFirst false
                val keyValue = dict[matchKey] ?: return@indexOfFirst false
                isEqual(keyValue, resolvedValue)
            }
        } else {
            currentArray.indexOfFirst { isEqual(it, resolvedValue) }
        }

        val newArray = currentArray.toMutableList()
        if (foundIndex >= 0) {
            newArray.removeAt(foundIndex)
        } else if (matchKey == null) {
            // Primitive arrays only — with matchBy an object can't be synthesized from a scalar.
            newArray.add(resolvedValue)
        } else {
            logger?.debug("toggleArrayValue: matchBy set, key not found — no-op")
            return
        }
        store.setValue(variableName, newArray)
    }

    private fun handleAppendToArray(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in appendToArray action")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in appendToArray action for '$variableName'")

        val resolvedValue = resolveValue(value.value, context)

        // nil / undefined counts as an empty array.
        val existing = store.getValue(variableName)
        val currentArray: List<Any?> = Values.asList(existing)
            ?: if (existing == null) emptyList() else throw VariableException.TypeMismatch(
                variableName, VariableType.ARRAY, Values.swiftTypeName(existing),
            )

        if (currentArray.size >= EngineLimits.maxArrayVariableCount) {
            logger?.warning(
                "appendToArray: '$variableName' already at the ${EngineLimits.maxArrayVariableCount}-element cap — append dropped",
            )
            return
        }
        store.setValue(variableName, currentArray + listOf(resolvedValue))
    }

    /**
     * Adds `by` (default 1) to `field` (default "quantity") of the object whose `matchBy` key
     * equals the value's. `value` is either the full object (appended with `field = by` when
     * missing and `by > 0`) or just the key. An item whose field drops to 0 or below is removed.
     */
    private fun handleIncrementArrayItem(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in incrementArrayItem action")
        val matchKey = action.matchBy
            ?: throw VariableException.UndefinedVariable("No matchBy specified in incrementArrayItem action for '$variableName'")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in incrementArrayItem action for '$variableName'")
        val field = action.field ?: "quantity"
        val by = by(action, context)
        val resolved = resolveValue(value.value, context)
        val obj = Values.asMap(resolved)
        // A null or missing match key is a no-op (never matches, never appends).
        val key: Any = (if (obj != null) obj[matchKey] else resolved)?.takeUnless { Values.isNullish(it) } ?: return

        val array = (Values.asList(store.getValue(variableName)) ?: emptyList()).toMutableList()
        val index = array.indexOfFirst { element -> Values.asMap(element)?.get(matchKey)?.let { isEqual(it, key) } ?: false }
        val existing = if (index >= 0) Values.asMap(array[index]) else null
        if (existing != null) {
            val current = Values.asNSNumber(existing[field]) ?: 0.0
            val next = current + by
            if (next <= 0) {
                array.removeAt(index)
            } else {
                array[index] = LinkedHashMap(existing).apply { put(field, wholeOrDouble(next)) }
            }
        } else if (obj != null && by > 0) {
            if (array.size >= EngineLimits.maxArrayVariableCount) return
            array.add(LinkedHashMap(obj).apply { put(field, wholeOrDouble(by)) })
        } else {
            return
        }
        store.setValue(variableName, array)
    }

    /** The array variable's elements; nil / undefined counts as empty. */
    private fun currentArray(variableName: String, store: VariableStoreProtocol): List<Any?> {
        val existing = store.getValue(variableName)
        return Values.asList(existing)
            ?: if (existing == null || existing === Values.Null) emptyList() else throw VariableException.TypeMismatch(
                variableName, VariableType.ARRAY, Values.swiftTypeName(existing),
            )
    }

    private fun resolvedIndex(action: VariableAction, context: VariableContext): Long? {
        val raw = action.index?.value ?: return null
        val v = resolveValue(raw, context)
        return Values.asInt(v) ?: Values.asDouble(v)?.toLong() ?: (v as? String)?.trim()?.toDoubleOrNull()?.toLong()
    }

    /**
     * Inserts `value` at `index` (default 0: prepend). The index clamps to `[0, count]`; a
     * negative index counts from the end, so -1 appends.
     */
    private fun handleInsertArrayItem(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in insertArrayItem action")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in insertArrayItem action for '$variableName'")
        val array = currentArray(variableName, store)
        if (array.size >= EngineLimits.maxArrayVariableCount) {
            logger?.warning("insertArrayItem: '$variableName' already at the ${EngineLimits.maxArrayVariableCount}-element cap — insert dropped")
            return
        }
        val count = array.size.toLong()
        val requested = resolvedIndex(action, context) ?: 0L
        val at = (if (requested < 0) count + 1 + requested else requested).coerceIn(0L, count).toInt()
        val out = array.toMutableList()
        out.add(at, resolveValue(value.value, context))
        store.setValue(variableName, out)
    }

    /**
     * Replaces the first element whose `matchBy` key equals `value[matchBy]` with `value`
     * exactly; otherwise inserts it at `position` (`end` by default, or `start`). A null or
     * missing match key is a no-op.
     */
    private fun handleUpsertArrayItem(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in upsertArrayItem action")
        val matchKey = action.matchBy
            ?: throw VariableException.UndefinedVariable("No matchBy specified in upsertArrayItem action for '$variableName'")
        val value = action.value
            ?: throw VariableException.UndefinedVariable("No value specified in upsertArrayItem action for '$variableName'")
        val resolved = resolveValue(value.value, context)
        val key = Values.asMap(resolved)?.get(matchKey)?.takeUnless { Values.isNullish(it) } ?: return
        val out = currentArray(variableName, store).toMutableList()
        val index = out.indexOfFirst { e -> Values.asMap(e)?.get(matchKey)?.let { isEqual(it, key) } ?: false }
        if (index >= 0) {
            out[index] = resolved
        } else {
            if (out.size >= EngineLimits.maxArrayVariableCount) {
                logger?.warning("upsertArrayItem: '$variableName' already at the ${EngineLimits.maxArrayVariableCount}-element cap — insert dropped")
                return
            }
            if (action.position == "start") out.add(0, resolved) else out.add(resolved)
        }
        store.setValue(variableName, out)
    }

    /**
     * With `index`: removes that element (negative counts from the end; out of range is a no-op).
     * With `matchBy` + `value`: removes every object whose key equals the value (a full object
     * value contributes its own key). With only `value`: removes every element equal to it.
     */
    private fun handleRemoveArrayItem(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val variableName = action.variableName
            ?: throw VariableException.UndefinedVariable("No variable name specified in removeArrayItem action")
        val array = currentArray(variableName, store)
        val requested = resolvedIndex(action, context)
        val out: List<Any?> = if (requested != null) {
            val at = if (requested < 0) array.size + requested else requested
            if (at < 0 || at >= array.size) return
            array.filterIndexed { i, _ -> i.toLong() != at }
        } else {
            val value = action.value
                ?: throw VariableException.UndefinedVariable("removeArrayItem needs `index` or `value` for '$variableName'")
            val resolved = resolveValue(value.value, context)
            val matchKey = action.matchBy
            if (matchKey != null) {
                val key = (Values.asMap(resolved)?.get(matchKey) ?: resolved)?.takeUnless { Values.isNullish(it) } ?: return
                array.filterNot { e -> Values.asMap(e)?.get(matchKey)?.let { isEqual(it, key) } ?: false }
            } else {
                array.filterNot { isEqual(it, resolved) }
            }
        }
        if (out.size != array.size) store.setValue(variableName, out)
    }

    private fun handleUpdateMultipleVariables(action: VariableAction, store: VariableStoreProtocol, context: VariableContext) {
        val updates = action.updates ?: return
        for ((variableName, value) in updates) {
            store.setValue(variableName, resolveValue(value.value, context))
        }
    }

    /**
     * Restores variables to their initial values (never deletes them). Scopes resolve from the
     * acting component's store chain `[own, parent, …, root]` (root = the app store):
     * `component` → own; `screen` (default) → the chain minus the root (a single-store chain
     * is that store); `app` → root; `all` → the whole chain. Reset root-first so computed
     * variables see fresh parents.
     */
    private fun handleResetVariables(action: VariableAction, store: VariableStoreProtocol) {
        val chain = ArrayList<VariableStoreProtocol>()
        var cursor: VariableStoreProtocol? = store
        while (cursor != null && chain.none { it === cursor }) {
            chain.add(cursor)
            cursor = cursor.getParentStore()
        }
        val targets = when (action.scope) {
            "component" -> listOf(store)
            "app" -> listOf(chain.last())
            "all" -> chain
            else -> if (chain.size > 1) chain.dropLast(1) else chain
        }
        for (target in targets.asReversed()) target.resetToInitialValues()
    }

    private fun wholeOrDouble(d: Double): Any = if (d % 1.0 == 0.0) d.toLong() else d

    /** Resolves `{{…}}` in a value — also inside object / array values. */
    /** `by`: a number, or an expression giving one; absent or not a number → 1. */
    private fun by(action: VariableAction, context: VariableContext): Double {
        action.by?.let { return it }
        val raw = action.byExpression ?: return 1.0
        val v = resolveValue(raw, context)
        return (v as? Number)?.toDouble() ?: (v as? String)?.trim()?.toDoubleOrNull() ?: 1.0
    }

    private fun resolveValue(value: Any?, context: VariableContext): Any? {
        if (value is String && value.contains("{{") && value.contains("}}")) return propertyResolver.resolve(value, context)
        Values.asMap(value)?.let { map -> return map.mapValuesTo(LinkedHashMap()) { (_, v) -> resolveValue(v, context) } }
        Values.asList(value)?.let { list -> return list.map { resolveValue(it, context) } }
        return value
    }

    private fun isEqual(lhs: Any?, rhs: Any?): Boolean {
        if (lhs is String && rhs is String) return lhs == rhs
        val li = Values.asInt(lhs)
        val ri = Values.asInt(rhs)
        if (li != null && ri != null) return li == ri
        val ld = Values.asDouble(lhs)
        val rd = Values.asDouble(rhs)
        if (ld != null && rd != null) return ld == rd
        if (lhs is Boolean && rhs is Boolean) return lhs == rhs
        return Values.describe(lhs) == Values.describe(rhs)
    }
}
