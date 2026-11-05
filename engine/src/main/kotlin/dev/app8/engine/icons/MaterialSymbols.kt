package dev.app8.engine.icons

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.app8.engine.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SF Symbols are licensed for Apple platforms only, so Android draws `icon` symbols with
 * Material Symbols Rounded (Apache 2.0, bundled as a variable font). Resolution order
 * (app8-dsl `docs/platforms.md`): the node's `android` override (already merged into the
 * name), then the built-in SF → Material table, then nothing (`icon.unmapped`).
 */
internal object MaterialSymbols {
    data class Glyph(val name: String, val filled: Boolean)

    @Volatile private var table: Map<String, String>? = null

    fun load(context: Context): Map<String, String> = table ?: synchronized(this) {
        table ?: runCatching {
            val text = context.assets.open("a8/sf-to-material.json").use { it.readBytes().decodeToString() }
            val root = Json.parseToJsonElement(text) as JsonObject
            (root["symbols"] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
        }.getOrDefault(emptyMap()).also { table = it }
    }

    /** The glyph for an SF Symbol (or Material Symbol) name; null when unmapped. */
    fun resolve(name: String, table: Map<String, String>): Glyph? {
        table[name]?.let { return Glyph(it, filled = name.endsWith(".fill")) }
        val base = name.removeSuffix(".fill")
        val filled = base != name || name.contains(".fill.")
        table[base]?.let { return Glyph(it, filled) }
        table[name.replace(".fill", "")]?.let { return Glyph(it, true) }
        // A Material Symbols name (the `android` override): lowercase, underscores, no dots.
        if (MATERIAL_NAME.matches(name)) return Glyph(name, filled = false)
        return null
    }

    private val MATERIAL_NAME = Regex("^[a-z0-9_]+$")

    private val families = HashMap<String, FontFamily>()

    /** The variable font at [fill] (0/1), [weight] and optical size [opticalSize] (20…48). */
    @OptIn(ExperimentalTextApi::class)
    fun family(filled: Boolean, weight: Int = 400, opticalSize: Float = 24f): FontFamily {
        val opsz = opticalSize.coerceIn(20f, 48f)
        val key = "$filled/$weight/${opsz.toInt()}"
        return families.getOrPut(key) {
            FontFamily(
                Font(
                    R.font.material_symbols_rounded,
                    weight = FontWeight(weight),
                    variationSettings = FontVariation.Settings(
                        FontVariation.Setting("FILL", if (filled) 1f else 0f),
                        FontVariation.weight(weight),
                        FontVariation.Setting("opsz", opsz),
                        FontVariation.Setting("GRAD", 0f),
                    ),
                ),
            )
        }
    }
}
