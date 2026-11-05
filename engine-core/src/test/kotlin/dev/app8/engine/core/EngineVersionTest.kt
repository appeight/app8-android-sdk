package dev.app8.engine.core

import kotlin.test.Test
import kotlin.test.assertTrue

class EngineVersionTest {
    @Test
    fun versionIsSemVer() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(EngineVersion.CURRENT))
    }
}
