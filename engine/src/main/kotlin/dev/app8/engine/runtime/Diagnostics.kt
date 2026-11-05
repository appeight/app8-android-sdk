package dev.app8.engine.runtime

import android.util.Log
import dev.app8.engine.App8LogLevel
import dev.app8.engine.core.vars.VariableLogger

/**
 * Engine warnings for authors and tooling (unmapped icons, placeholder components, decode
 * drops, layout conflicts). Deduplicated by message; the preview host writes them next to
 * each screenshot.
 */
internal class Diagnostics(var level: App8LogLevel) : VariableLogger {
    private val lock = Any()
    private val seen = LinkedHashSet<String>()

    /** `code: message`, e.g. `icon.unmapped: 'cart.badge.questionmark' has no Material Symbol`. */
    fun warn(code: String, message: String) {
        val line = "$code: $message"
        val added = synchronized(lock) { seen.add(line) }
        if (added && level >= App8LogLevel.WARNING) Log.w(TAG, line)
    }

    fun snapshot(): List<String> = synchronized(lock) { seen.toList() }

    fun clear() = synchronized(lock) { seen.clear() }

    fun log(message: String) {
        if (level >= App8LogLevel.DEBUG) Log.d(TAG, message)
    }

    override fun debug(message: String) = log(message)
    override fun warning(message: String) = warn("variables", message)
    override fun error(message: String) = warn("variables", message)

    companion object {
        const val TAG = "App8"
    }
}
