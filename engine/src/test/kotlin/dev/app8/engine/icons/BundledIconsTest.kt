package dev.app8.engine.icons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled Material Symbols subset (scripts/subset-icons.py) covers every icon the engine can
 * be asked for, and material-codepoints.json describes exactly the font's cmap.
 */
class BundledIconsTest {
    private fun json(path: String): Map<String, String> {
        val root = Json.parseToJsonElement(File(path).readText()) as JsonObject
        val key = if ("symbols" in root) "symbols" else "codepoints"
        return (root[key] as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    private val mapping = json("src/main/assets/a8/sf-to-material.json")
    private val codepoints = json("src/main/assets/a8/material-codepoints.json").mapValues { it.value.toInt(16) }
    private val extra = File("extra-icons.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    private val cmap = TrueTypeCmap.read(File("src/main/res/font/material_symbols_rounded.ttf").readBytes())

    @Test
    fun everyMappedAndExtraIconHasACodepoint() {
        val missing = (mapping.values + extra).filter { it !in codepoints }.distinct()
        assertTrue(missing.isEmpty(), "not in material-codepoints.json (run scripts/subset-icons.py): $missing")
    }

    @Test
    fun codepointsMatchTheFontCmap() {
        val missing = codepoints.filterValues { it !in cmap }
        assertTrue(missing.isEmpty(), "codepoints without a glyph in the bundled font: $missing")
        // No glyphs beyond the table (other than the font's control/space characters).
        val unexpected = cmap.filter { it >= 0xE000 } - codepoints.values.toSet()
        assertTrue(unexpected.isEmpty(), "font glyphs not in material-codepoints.json: ${unexpected.map { it.toString(16) }}")
        assertEquals((mapping.values + extra).toSet(), codepoints.keys, "the subset is exactly the mapped and extra icons")
    }
}

/** Reads the Unicode code points a TrueType font maps (cmap formats 4 and 12). */
internal object TrueTypeCmap {
    fun read(bytes: ByteArray): Set<Int> {
        val b = ByteBuffer.wrap(bytes)
        val numTables = b.getShort(4).toInt() and 0xFFFF
        val cmap = (0 until numTables).map { 12 + 16 * it }
            .first { String(bytes, it, 4, Charsets.US_ASCII) == "cmap" }
            .let { b.getInt(it + 8) }
        val subtables = (0 until (b.getShort(cmap + 2).toInt() and 0xFFFF)).map { cmap + b.getInt(cmap + 4 + 8 * it + 4) }
        val out = HashSet<Int>()
        for (off in subtables.distinct()) {
            when (b.getShort(off).toInt() and 0xFFFF) {
                4 -> {
                    val segX2 = b.getShort(off + 6).toInt() and 0xFFFF
                    val ends = off + 14
                    val starts = ends + segX2 + 2
                    val deltas = starts + segX2
                    val ranges = deltas + segX2
                    for (i in 0 until segX2 / 2) {
                        val end = b.getShort(ends + 2 * i).toInt() and 0xFFFF
                        val start = b.getShort(starts + 2 * i).toInt() and 0xFFFF
                        val delta = b.getShort(deltas + 2 * i).toInt()
                        val rangeOffset = b.getShort(ranges + 2 * i).toInt() and 0xFFFF
                        if (start == 0xFFFF) continue
                        for (c in start..end) {
                            val gid = if (rangeOffset == 0) (c + delta) and 0xFFFF
                            else (b.getShort(ranges + 2 * i + rangeOffset + 2 * (c - start)).toInt() and 0xFFFF)
                                .let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                            if (gid != 0) out += c
                        }
                    }
                }
                12 -> {
                    val groups = b.getInt(off + 12)
                    for (i in 0 until groups) {
                        val g = off + 16 + 12 * i
                        val start = b.getInt(g)
                        val end = b.getInt(g + 4)
                        val gid = b.getInt(g + 8)
                        for (c in start..end) if (gid + (c - start) != 0) out += c
                    }
                }
            }
        }
        return out
    }
}
