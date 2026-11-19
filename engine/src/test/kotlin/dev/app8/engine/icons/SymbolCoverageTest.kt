package dev.app8.engine.icons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Every symbol name in `src/test/resources/icons/required-symbols.txt` draws a bundled
 * Material Symbol: an unmapped name renders nothing (an empty tab or row icon).
 */
class SymbolCoverageTest {
    private fun json(path: String, key: String): Map<String, String> =
        ((Json.parseToJsonElement(File(path).readText()) as JsonObject)[key] as JsonObject)
            .mapValues { it.value.jsonPrimitive.content }

    @Test
    fun everyRequiredSymbolResolves() {
        val table = json("src/main/assets/a8/sf-to-material.json", "symbols")
        val codepoints = json("src/main/assets/a8/material-codepoints.json", "codepoints").mapValues { it.value.toInt(16) }
        val names = File("src/test/resources/icons/required-symbols.txt").readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertTrue(names.size > 50, "fixture list not read")
        val missing = names.filter { MaterialSymbols.lookup(it, table, codepoints) !is MaterialSymbols.Lookup.Found }
        assertTrue(missing.isEmpty(), "symbols with no bundled Material Symbol (map them in sf-to-material.json): $missing")
    }
}
