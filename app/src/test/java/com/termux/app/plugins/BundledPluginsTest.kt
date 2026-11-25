package com.termux.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Validates the bundled v2 artifacts through the real parser and settings resolver. */
class BundledPluginsTest {

    private fun assetsDir(): File {
        for (path in listOf("src/main/assets/plugins", "app/src/main/assets/plugins")) {
            val dir = File(path)
            if (dir.isDirectory) return dir
        }
        throw IllegalStateException("assets dir not found from ${File("").absolutePath}")
    }

    @Test
    fun allBundledPluginsParseAndResolve() {
        val zips = assetsDir().listFiles { file -> file.name.endsWith(".zip") }!!
        assertTrue("expected at least 2 bundled plugins", zips.size >= 2)
        for (zip in zips) {
            val manifest = PluginParser.parse(PluginArchive.readPluginJson(zip.readBytes()))
            val values = PluginSettings.effectiveValues(manifest, emptyMap())
            for (contribution in manifest.contributions) {
                PluginSettings.resolveContribution(contribution, values)
            }
        }
    }

    @Test
    fun midnightDarkExposesAccentSetting() {
        val zip = File(assetsDir(), "midnight-dark.zip")
        val manifest = PluginParser.parse(PluginArchive.readPluginJson(zip.readBytes()))
        assertEquals("com.xeriomy.zen.midnightdark", manifest.id)
        val accent = manifest.settings.firstOrNull { it.id == "accent" }
        assertNotNull(accent)
        val resolved = PluginSettings.resolveContribution(
            manifest.theme()!!, PluginSettings.effectiveValues(manifest, mapOf("accent" to "#ff0000"))
        ) as Contribution.Theme
        assertEquals("#ff0000", resolved.colors["cursor"])
    }

    @Test
    fun codingKeysPackCombinesContributions() {
        val zip = File(assetsDir(), "coding-keys.zip")
        val manifest = PluginParser.parse(PluginArchive.readPluginJson(zip.readBytes()))
        val keys = manifest.extraKeys()!!
        assertNotNull(keys)
        // The bundled sample bar demonstrates navigation, function, keyboard-toggle
        // and macro cells, not just the four original modifier keys.
        assertTrue(keys.rows.size >= 4)
        assertTrue(keys.rows.any { row -> row.any { it.key == "KEYBOARD" } })
        assertTrue(keys.rows.any { row -> row.any { it.key == "F12" } })
        assertTrue(keys.rows.any { row -> row.any { it.macro != null } })
        assertNotNull(manifest.terminalSettings())
        val resolved = PluginSettings.resolveContribution(
            manifest.terminalSettings()!!, PluginSettings.effectiveValues(manifest, mapOf("all_caps" to "true"))
        ) as Contribution.TerminalSettings
        assertEquals("true", resolved.values["extra-keys-text-all-caps"])
    }
}
