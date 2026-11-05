package dev.app8.engine.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.app8.engine.core.vars.TranslationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * i18n tables and the active locale (iOS `TranslationStore`): override → device locale →
 * the bundle's `defaultLocale`. The override is snapshot state, so a locale switch re-resolves
 * visible text.
 */
internal class TranslationStore : TranslationProvider {
    private var defaultLocale = "en"
    private var locales: Map<String, Map<String, String>> = emptyMap()
    private var override by mutableStateOf<String?>(null)

    fun load(json: ByteArray) {
        val root = Json.parseToJsonElement(json.decodeToString()) as? JsonObject ?: return
        defaultLocale = (root["defaultLocale"] as? JsonPrimitive)?.contentOrNull ?: "en"
        locales = (root["locales"] as? JsonObject)?.mapValues { (_, table) ->
            (table as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        }.orEmpty()
    }

    fun setActive(locale: String?) {
        override = locale
    }

    /** The locale whose table is used: the best available match. */
    val activeLocaleTag: String
        get() {
            val wanted = override ?: Locale.getDefault().toLanguageTag()
            return matching(wanted) ?: matching(defaultLocale) ?: override ?: defaultLocale
        }

    private fun matching(tag: String): String? {
        if (locales.containsKey(tag)) return tag
        val language = tag.substringBefore('-')
        return locales.keys.firstOrNull { it == language || it.substringBefore('-') == language }
    }

    override val activeLocale: Locale get() = Locale.forLanguageTag(override ?: activeLocaleTag)

    override fun lookup(key: String): String? =
        locales[activeLocaleTag]?.get(key) ?: locales[defaultLocale]?.get(key)
}
