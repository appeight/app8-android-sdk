package dev.app8.engine.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.app8.engine.core.vars.VariablePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persisted app variables on Android (app8-dsl `docs/variables.md`): Jetpack DataStore
 * (Preferences) file `app8_<appId>`, key `var.<name>`, JSON string values.
 *
 * [preload] reads the file once before the app variables are defined, so [stored] is a
 * synchronous lookup. Writes are coalesced per name and flushed at most every 0.5 s, and right
 * away when the app goes to the background (`TRIM_MEMORY_UI_HIDDEN`).
 */
internal class DataStoreVariablePersistence(
    context: Context,
    appId: String,
) : VariablePersistence {
    private val store: DataStore<Preferences> = dataStore(context.applicationContext, fileName(appId))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var snapshot: Map<String, String> = emptyMap()

    /** Pending writes: a JSON string, or null for a removal. Main thread only. */
    private val pending = LinkedHashMap<String, String?>()
    private var flushJob: Job? = null

    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushNow()
        }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    init {
        context.applicationContext.registerComponentCallbacks(trimCallback)
    }

    suspend fun preload() {
        val prefs = withContext(Dispatchers.IO) { runCatching { store.data.first() }.getOrNull() } ?: return
        snapshot = prefs.asMap().entries.mapNotNull { (k, v) ->
            val name = k.name.removePrefix(KEY_PREFIX).takeIf { k.name.startsWith(KEY_PREFIX) } ?: return@mapNotNull null
            (v as? String)?.let { name to it }
        }.toMap()
    }

    override fun stored(name: String): String? = snapshot[name]

    override fun save(name: String, json: String) = enqueue(name, json)

    override fun remove(name: String) = enqueue(name, null)

    private fun enqueue(name: String, json: String?) {
        pending[name] = json
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.Main.immediate) {
            delay(DEBOUNCE_MS)
            flushNow()
        }
    }

    private fun flushNow() {
        if (pending.isEmpty()) return
        val batch = LinkedHashMap(pending)
        pending.clear()
        flushJob?.cancel()
        flushJob = null
        scope.launch {
            runCatching {
                store.edit { prefs ->
                    for ((name, json) in batch) {
                        val key = stringPreferencesKey(KEY_PREFIX + name)
                        if (json == null) prefs.remove(key) else prefs[key] = json
                    }
                }
            }
        }
    }

    companion object {
        private const val KEY_PREFIX = "var."
        private const val DEBOUNCE_MS = 500L

        fun fileName(appId: String): String = "app8_" + appId.replace(Regex("[^A-Za-z0-9._-]"), "_")

        // DataStore allows one instance per file per process.
        private val stores = HashMap<String, DataStore<Preferences>>()

        private fun dataStore(context: Context, name: String): DataStore<Preferences> = synchronized(stores) {
            stores.getOrPut(name) {
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { context.preferencesDataStoreFile(name) },
                )
            }
        }
    }
}
