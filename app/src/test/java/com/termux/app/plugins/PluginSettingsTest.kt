package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginSettingsTest {

    private fun manifestWith(settings: JSONArray, contributions: JSONArray = JSONArray().put(PluginTestUtils.themeContribution())): PluginManifest =
        PluginParser.parse(PluginTestUtils.document(settings = settings, contributions = contributions))

    @Test
    fun effectiveValuesUseDefaultsAndValidOverrides() {
        val manifest = manifestWith(JSONArray()
            .put(PluginTestUtils.toggleSetting("flag", "true"))
            .put(PluginTestUtils.numberSetting("rate", "500"))
            .put(PluginTestUtils.colorSetting("accent", "#ff6b6b")))
        val effective = PluginSettings.effectiveValues(manifest, mapOf(
            "flag" to "false", "rate" to "9999", "accent" to "#00ff00", "unknown" to "x"
        ))
        assertEquals("false", effective["flag"])
        assertEquals("500", effective["rate"]) // out-of-range override falls back
        assertEquals("#00ff00", effective["accent"])
        assertNull(effective["unknown"])
    }

    @Test
    fun validateValueCoversEveryType() {
        val toggle = PluginParser.parse(PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.toggleSetting()))).settings[0]
        assertNull(PluginSettings.validateValue(toggle, "true"))
        assertNotNull(PluginSettings.validateValue(toggle, "1"))

        val number = PluginParser.parse(PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.numberSetting()))).settings[0]
        assertNull(PluginSettings.validateValue(number, "100"))
        assertNotNull(PluginSettings.validateValue(number, "99"))
        assertNotNull(PluginSettings.validateValue(number, "x"))

        val choice = PluginParser.parse(PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.choiceSetting()))).settings[0]
        assertNull(PluginSettings.validateValue(choice, "b"))
        assertNotNull(PluginSettings.validateValue(choice, "c"))

        val color = PluginParser.parse(PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.colorSetting()))).settings[0]
        assertNull(PluginSettings.validateValue(color, "#abcdef"))
        assertNotNull(PluginSettings.validateValue(color, "#abc"))

        val text = PluginParser.parse(PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.textSetting()))).settings[0]
        assertNull(PluginSettings.validateValue(text, "ok"))
        assertNotNull(PluginSettings.validateValue(text, "bad\u0000"))
    }

    @Test
    fun resolvesTokensInThemeColors() {
        val theme = JSONObject().put("type", "theme").put("colors",
            org.json.JSONObject().put("foreground", "\${settings.accent}").put("background", "#101820"))
        val manifest = manifestWith(
            JSONArray().put(PluginTestUtils.colorSetting("accent", "#ff6b6b")),
            JSONArray().put(theme)
        )
        val values = PluginSettings.effectiveValues(manifest, mapOf("accent" to "#123456"))
        val resolved = PluginSettings.resolveContribution(manifest.theme()!!, values) as Contribution.Theme
        assertEquals("#123456", resolved.colors["foreground"])
        assertEquals("#101820", resolved.colors["background"])
    }

    @Test
    fun resolvedInvalidColorFailsClosed() {
        val theme = JSONObject().put("type", "theme").put("colors",
            org.json.JSONObject().put("foreground", "\${settings.label_text}").put("background", "#101820"))
        val manifest = manifestWith(
            JSONArray().put(PluginTestUtils.textSetting("label_text", "not-a-color")),
            JSONArray().put(theme)
        )
        val values = PluginSettings.effectiveValues(manifest, emptyMap())
        try {
            PluginSettings.resolveContribution(manifest.theme()!!, values)
            fail("expected failure for resolved invalid color")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("invalid color"))
        }
    }

    @Test
    fun resolvesPresetDirectoryAndValidates() {
        val manifest = manifestWith(
            JSONArray().put(PluginTestUtils.textSetting("dir", "projects")),
            JSONArray().put(PluginTestUtils.presetContribution("P", "\${settings.dir}"))
        )
        val values = PluginSettings.effectiveValues(manifest, emptyMap())
        val resolved = PluginSettings.resolveContribution(manifest.sessionPresets()[0], values) as Contribution.SessionPreset
        assertEquals("projects", resolved.workingDirectory)

        val bad = PluginSettings.effectiveValues(manifest, mapOf("dir" to "../escape"))
        try {
            PluginSettings.resolveContribution(manifest.sessionPresets()[0], bad)
            fail("expected failure for escaping directory")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("invalid path"))
        }
    }

    @Test
    fun extraKeysJsonStringMatchesHostShape() {
        val manifest = PluginParser.parse(PluginTestUtils.document(
            contributions = JSONArray().put(PluginTestUtils.keysContribution())))
        val json = PluginSettings.extraKeysToJsonString(manifest.extraKeys()!!)
        val array = JSONArray(json)
        assertEquals(1, array.length())
        assertEquals("ESC", array.getJSONArray(0).getJSONObject(0).getString("key"))
    }

    @Test
    fun unknownTokenThrows() {
        try {
            PluginSettings.resolveText("\${settings.nope}", emptyMap())
            fail("expected unknown setting failure")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }

    @Test
    fun settingTokenRegexIsAndroidIcuSafe() {
        // java.util.regex tolerates a bare `}` as a literal, but Android's ICU-backed
        // regex throws PatternSyntaxException for it. Since the regex is a top-level val,
        // that crashed ZenApi's initializer during TermuxApplication.onCreate.
        assertTrue("closing brace must be escaped for Android's regex engine",
            ZenApi.SETTING_TOKEN_REGEX.pattern.endsWith("\\}"))
        val match = ZenApi.SETTING_TOKEN_REGEX.matchEntire("\${settings.accent}")
        assertNotNull(match)
        assertEquals("accent", match!!.groupValues[1])
    }
}
