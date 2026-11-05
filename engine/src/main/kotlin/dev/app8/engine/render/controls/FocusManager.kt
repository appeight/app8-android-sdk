package dev.app8.engine.render.controls

/**
 * Focus across a screen's text inputs (iOS `FocusManager`). Inputs register while composed;
 * the order is the screen's tree order. `focus`, `focusNext`, `focusPrevious` and
 * `dismissKeyboard` actions land here.
 */
internal class FocusManager {
    /** A registered input: [request] focuses it, [release] clears focus and hides the keyboard. */
    class Entry(val key: String, val id: String, val order: Int, val request: () -> Unit, val release: () -> Unit)

    private val entries = ArrayList<Entry>()

    var currentKey: String? = null
        private set

    val registeredKeys: List<String> get() = entries.map { it.key }

    fun register(entry: Entry) {
        entries.removeAll { it.key == entry.key }
        val at = entries.indexOfFirst { it.order > entry.order }.let { if (it < 0) entries.size else it }
        entries.add(at, entry)
    }

    fun unregister(key: String) {
        entries.removeAll { it.key == key }
        if (currentKey == key) currentKey = null
    }

    /**
     * A `focus` action's `target`: a full component path, a path suffix, or a DSL id.
     * iOS prefixes a dotless target with the acting component's path; ids are matched too
     * because Android keys differ from iOS component paths.
     */
    fun resolve(target: String, fromKey: String?): String? {
        entries.firstOrNull { it.key == target }?.let { return it.key }
        if (fromKey != null) entries.firstOrNull { it.key == "$fromKey.$target" }?.let { return it.key }
        entries.firstOrNull { it.key.endsWith(".$target") }?.let { return it.key }
        return entries.firstOrNull { it.id == target }?.key
    }

    fun focus(key: String): Boolean {
        val e = entries.firstOrNull { it.key == key } ?: return false
        currentKey = key
        e.request()
        return true
    }

    fun focusNext() {
        val i = entries.indexOfFirst { it.key == currentKey }
        when {
            i < 0 -> entries.firstOrNull()?.let { focus(it.key) }
            i + 1 < entries.size -> focus(entries[i + 1].key)
            else -> dismissKeyboard()
        }
    }

    fun focusPrevious() {
        val i = entries.indexOfFirst { it.key == currentKey }
        when {
            i < 0 -> entries.lastOrNull()?.let { focus(it.key) }
            i > 0 -> focus(entries[i - 1].key)
        }
    }

    fun dismissKeyboard() {
        val e = entries.firstOrNull { it.key == currentKey } ?: entries.firstOrNull()
        currentKey = null
        e?.release?.invoke()
    }

    /** Inputs report focus changes they did not get from here (the user tapped a field). */
    fun didFocus(key: String?) {
        currentKey = key
    }

    fun didBlur(key: String) {
        if (currentKey == key) currentKey = null
    }
}
