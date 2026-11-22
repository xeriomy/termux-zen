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
        assertTrue("expected at least 3 bundled plugins", zips.size >= 3)
        for (zip in zips) {
            val manifest = PluginParser.parse(PluginArchive.readPluginJson(zip.readBytes()))
            val values = PluginSettings.effectiveValues(manifest, emptyMap())
            for (contribution in manifest.contributions) {
                PluginSettings.resolveContribution(contribution, values)
            }
        }
    }

    @Test
    fun solarizedDarkExposesAccentSetting() {
        val zip = File(assetsDir(), "solarized-dark.zip")
        val manifest = PluginParser.parse(PluginArchive.readPluginJson(zip.readBytes()))
        assertEquals("com.xeriomy.zen.solarizeddark", manifest.id)
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
        assertNotNull(manifest.extraKeys())
        assertNotNull(manifest.terminalSettings())
        val resolved = PluginSettings.resolveContribution(
            manifest.terminalSettings()!!, PluginSettings.effectiveValues(manifest, mapOf("all_caps" to "true"))
        ) as Contribution.TerminalSettings
        assertEquals("true", resolved.values["extra-keys-text-all-caps"])
    }
}
