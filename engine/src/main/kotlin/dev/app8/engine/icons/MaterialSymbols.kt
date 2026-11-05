package dev.app8.engine.icons

import android.content.Context
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.app8.engine.R
import dev.app8.engine.runtime.Diagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SF Symbols are licensed for Apple platforms only, so Android draws `icon` symbols with
 * Material Symbols Rounded (Apache 2.0). Resolution order (app8-dsl `docs/platforms.md`): the
 * node's `android` override (already merged into the name), then the built-in SF → Material
 * table, then nothing (`icon.unmapped`).
 *
 * The bundled font is a subset (`scripts/subset-icons.py`): only the Material names the table
 * maps to plus `engine/extra-icons.txt`, drawn by codepoint (no ligatures). A Material name
 * outside the subset draws nothing and warns `icon.unavailable`.
 */
internal object MaterialSymbols {
    data class Glyph(val name: String, val codepoint: Int, val filled: Boolean) {
        /** The text to draw in [family]: the glyph's Private Use Area codepoint. */
        val text: String get() = String(Character.toChars(codepoint))
    }

    sealed interface Lookup {
        data class Found(val glyph: Glyph) : Lookup
        /** A Material name the bundled subset does not include. */
        data class Unavailable(val material: String) : Lookup
        /** Neither in the SF table nor a Material name. */
        data object Unmapped : Lookup
    }

    @Volatile private var table: Map<String, String>? = null
    @Volatile private var codepoints: Map<String, Int>? = null

    private fun asset(context: Context, path: String, key: String): Map<String, String> = runCatching {
        val text = context.assets.open(path).use { it.readBytes().decodeToString() }
        val root = Json.parseToJsonElement(text) as JsonObject
        (root[key] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }
    }.getOrDefault(emptyMap())

    /** SF Symbol name → Material Symbols name. */
    fun load(context: Context): Map<String, String> = table ?: synchronized(this) {
        table ?: asset(context, "a8/sf-to-material.json", "symbols").also { table = it }
    }

    /** Material Symbols name → codepoint, for exactly the glyphs in the bundled font. */
    fun codepoints(context: Context): Map<String, Int> = codepoints ?: synchronized(this) {
        codepoints ?: asset(context, "a8/material-codepoints.json", "codepoints")
            .mapValues { (_, v) -> v.toInt(16) }.also { codepoints = it }
    }

    /** The glyph for an SF Symbol or Material Symbol [name]; null (and a warning naming the icon) when there is none. */
    fun resolve(context: Context, name: String, diagnostics: Diagnostics?, where: String): Glyph? =
        when (val l = lookup(name, load(context), codepoints(context))) {
            is Lookup.Found -> l.glyph
            is Lookup.Unavailable -> null.also {
                diagnostics?.warn(
                    "icon.unavailable",
                    (if (l.material == name) "'$name' is neither a mapped SF Symbol nor a bundled Material Symbol"
                    else "Material Symbol '${l.material}' (for '$name') is not bundled") +
                        " ($where); if it is a Material Symbols name, " +
                        "add it to engine/extra-icons.txt and run scripts/subset-icons.py",
                )
            }
            Lookup.Unmapped -> null.also { diagnostics?.warn("icon.unmapped", "SF Symbol '$name' has no Material Symbol ($where)") }
        }

    /** A bundled Material Symbol by its Material name (engine chrome); null if not in the subset. */
    fun material(context: Context, name: String, filled: Boolean = false): Glyph? =
        codepoints(context)[name]?.let { Glyph(name, it, filled) }

    fun lookup(name: String, table: Map<String, String>, codepoints: Map<String, Int>): Lookup {
        fun found(material: String, filled: Boolean): Lookup =
            codepoints[material]?.let { Lookup.Found(Glyph(material, it, filled)) } ?: Lookup.Unavailable(material)
        table[name]?.let { return found(it, filled = name.endsWith(".fill")) }
        val base = name.removeSuffix(".fill")
        val filled = base != name || name.contains(".fill.")
        table[base]?.let { return found(it, filled) }
        table[name.replace(".fill", "")]?.let { return found(it, true) }
        // A Material Symbols name (the `android` override): lowercase, underscores, no dots.
        if (MATERIAL_NAME.matches(name)) return found(name, false)
        return Lookup.Unmapped
    }

    private val MATERIAL_NAME = Regex("^[a-z0-9_]+$")

    private val families = HashMap<String, FontFamily>()

    /** The variable font at [filled] (FILL 0/1), [weight] and optical size [opticalSize] (20…48). */
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
                    ),
                ),
            )
        }
    }
}
