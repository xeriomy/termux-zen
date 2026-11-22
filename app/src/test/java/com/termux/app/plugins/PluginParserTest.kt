package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginParserTest {

    private fun expectReject(text: String, fragment: String? = null) {
        try {
            PluginParser.parse(text)
            fail("expected PluginException for: ${text.take(120)}")
        } catch (e: PluginException) {
            if (fragment != null) assertTrue(
                "message \"${e.message}\" should contain \"$fragment\"",
                e.message?.contains(fragment) == true
            )
        }
    }

    @Test
    fun parsesFullDocument() {
        val settings = JSONArray()
            .put(PluginTestUtils.toggleSetting())
            .put(PluginTestUtils.colorSetting())
            .put(PluginTestUtils.numberSetting())
            .put(PluginTestUtils.choiceSetting())
            .put(PluginTestUtils.textSetting())
        val contributions = JSONArray()
            .put(PluginTestUtils.themeContribution())
            .put(PluginTestUtils.keysContribution())
            .put(PluginTestUtils.presetContribution())
            .put(PluginTestUtils.presetContribution("Docs", "docs"))
            .put(PluginTestUtils.terminalSettingsContribution("bell-character" to "ignore", "terminal-cursor-style" to "bar"))
        val text = PluginTestUtils.document(
            settings = settings, contributions = contributions,
            dependencies = JSONArray().put("com.example.base@1.0.0"),
            minCoreVersion = "0.118.0"
        )
        val manifest = PluginParser.parse(text)
        assertEquals("com.example.test", manifest.id)
        assertEquals(Semver.parse("1.0.0"), manifest.version)
        assertEquals(Semver.parse("0.118.0"), manifest.minCoreVersion)
        assertEquals(5, manifest.settings.size)
        assertEquals(5, manifest.contributions.size)
        assertEquals(2, manifest.sessionPresets().size)
        assertEquals(1, manifest.dependencies.size)
        assertEquals("com.example.base", manifest.dependencies[0].id)
        assertNotNull(manifest.theme())
        assertNotNull(manifest.extraKeys())
        assertNotNull(manifest.terminalSettings())
        assertTrue(manifest.hasExclusiveContribution)
    }

    @Test
    fun roundTripsThroughSerialization() {
        val text = PluginTestUtils.document(
            settings = JSONArray().put(PluginTestUtils.colorSetting()),
            contributions = JSONArray()
                .put(PluginTestUtils.themeContribution())
                .put(PluginTestUtils.presetContribution())
        )
        val manifest = PluginParser.parse(text)
        val reparsed = PluginParser.parse(PluginParser.toJson(manifest))
        assertEquals(manifest.id, reparsed.id)
        assertEquals(manifest.version, reparsed.version)
        assertEquals(manifest.contributions.size, reparsed.contributions.size)
        assertEquals(manifest.theme()!!.colors, reparsed.theme()!!.colors)
        assertEquals(manifest.settings.size, reparsed.settings.size)
    }

    @Test
    fun rejectsUnknownManifestField() {
        val obj = JSONObject(PluginTestUtils.document()).put("surprise", true)
        expectReject(obj.toString(), "unknown field")
    }

    @Test
    fun rejectsWrongSchemaAndApiVersions() {
        expectReject(JSONObject(PluginTestUtils.document()).put("schemaVersion", 1).toString(), "schemaVersion")
        expectReject(JSONObject(PluginTestUtils.document()).put("apiVersion", 1).toString(), "apiVersion")
        expectReject(JSONObject(PluginTestUtils.document()).put("apiVersion", 3).toString(), "apiVersion")
    }

    @Test
    fun rejectsInvalidIds() {
        expectReject(PluginTestUtils.document(id = "abc"), "id")
        expectReject(PluginTestUtils.document(id = "com..bad"), "id")
        expectReject(PluginTestUtils.document(id = "Com.Example"), "id")
        expectReject(PluginTestUtils.document(id = "com.example/../evil"), "id")
    }

    @Test
    fun rejectsMissingAndInvalidFields() {
        expectReject(JSONObject(PluginTestUtils.document()).apply { remove("name") }.toString(), "name")
        expectReject(JSONObject(PluginTestUtils.document()).put("version", "1.2").toString(), "version")
        expectReject(JSONObject(PluginTestUtils.document()).put("name", "bad\u0001name").toString(), "control")
        expectReject(JSONObject(PluginTestUtils.document()).apply { remove("contributions") }.toString(), "contributions")
        expectReject(PluginTestUtils.document(contributions = JSONArray()), "1..16")
    }

    @Test
    fun rejectsDuplicateExclusiveContributions() {
        val contributions = JSONArray()
            .put(PluginTestUtils.themeContribution())
            .put(PluginTestUtils.themeContribution("#ffffff", "#000000"))
        expectReject(PluginTestUtils.document(contributions = contributions), "at most one theme")
    }

    @Test
    fun rejectsUnknownContributionType() {
        val contributions = JSONArray().put(JSONObject().put("type", "shell_script"))
        expectReject(PluginTestUtils.document(contributions = contributions), "unknown type")
    }

    @Test
    fun validatesThemeColors() {
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            JSONObject().put("type", "theme").put("colors", JSONObject().put("foreground", "#fff"))
        )), "background")
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            JSONObject().put("type", "theme").put("colors",
                JSONObject().put("foreground", "#fff").put("background", "#000").put("neon", "#123456"))
        )), "unknown color key")
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            JSONObject().put("type", "theme").put("colors",
                JSONObject().put("foreground", "red").put("background", "#000"))
        )), "not a valid color")
        // All host color forms are accepted.
        for (color in listOf("#abc", "#aabbcc", "#aaabbbccc", "#aaaabbbbcccc")) {
            val manifest = PluginParser.parse(PluginTestUtils.document(contributions = JSONArray().put(
                JSONObject().put("type", "theme").put("colors",
                    JSONObject().put("foreground", color).put("background", "#000"))
            )))
            assertEquals(color, manifest.theme()!!.colors["foreground"])
        }
    }

    @Test
    fun validatesExtraKeysShape() {
        fun keysDoc(keys: Any, style: String? = null): String {
            val obj = JSONObject().put("type", "extra_keys").put("keys", keys)
            style?.let { obj.put("style", it) }
            return PluginTestUtils.document(contributions = JSONArray().put(obj))
        }
        expectReject(keysDoc(JSONArray().put(JSONArray().put(
            JSONObject().put("key", "ESC").put("macro", "x")
        ))), "exactly one")
        expectReject(keysDoc(JSONArray().put(JSONArray().put(JSONObject()))), "exactly one")
        expectReject(keysDoc(JSONArray().put(JSONArray().put(
            JSONObject().put("key", "ESC").put("popup", JSONObject().put("key", "TAB")
                .put("popup", JSONObject().put("key", "CTRL")))
        ))), "unknown field")
        expectReject(keysDoc(JSONArray(), style = "default"), "1..6")
        expectReject(keysDoc(JSONArray().put(JSONArray()), style = "default"), "1..12")
        expectReject(keysDoc(JSONArray().put(JSONArray().put(JSONObject().put("key", "ESC"))), style = "fancy"), "style")
        // One popup level is allowed.
        val ok = keysDoc(JSONArray().put(JSONArray().put(
            JSONObject().put("key", "CTRL").put("popup", JSONObject().put("key", "ALT"))
        )))
        assertNotNull(PluginParser.parse(ok).extraKeys())
    }

    @Test
    fun validatesPresetPaths() {
        fun presetDoc(dir: String?): String =
            PluginTestUtils.document(contributions = JSONArray().put(PluginTestUtils.presetContribution("P", dir)))
        expectReject(presetDoc("/etc"), "relative path")
        expectReject(presetDoc("~/x"), "relative path")
        expectReject(presetDoc("a/../b"), "relative path")
        expectReject(presetDoc("a//b"), "relative path")
        expectReject(presetDoc("./a"), "relative path")
        expectReject(presetDoc("a\\b"), "relative path")
        assertNotNull(PluginParser.parse(presetDoc(null)).sessionPresets()[0])
        assertNotNull(PluginParser.parse(presetDoc("projects/sub")).sessionPresets()[0])
    }

    @Test
    fun validatesTerminalSettingsWhitelist() {
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            PluginTestUtils.terminalSettingsContribution("delete-tmpdir-files-older-than-x-days-on-exit" to "0")
        )), "unsupported key")
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            PluginTestUtils.terminalSettingsContribution("bell-character" to "explode")
        )), "invalid")
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            PluginTestUtils.terminalSettingsContribution("terminal-margin-horizontal" to "101")
        )), "invalid")
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(
            PluginTestUtils.terminalSettingsContribution("terminal-cursor-blink-rate" to "50")
        )), "invalid")
        val ok = PluginParser.parse(PluginTestUtils.document(contributions = JSONArray().put(
            PluginTestUtils.terminalSettingsContribution(
                "bell-character" to "beep",
                "terminal-cursor-style" to "underline",
                "terminal-cursor-blink-rate" to "0",
                "terminal-transcript-rows" to "5000",
                "extra-keys-text-all-caps" to "false"
            )
        )))
        assertEquals(5, ok.terminalSettings()!!.values.size)
    }

    @Test
    fun validatesSettingsSchema() {
        fun settingsDoc(vararg specs: JSONObject): String =
            PluginTestUtils.document(settings = JSONArray().apply { specs.forEach { put(it) } })
        expectReject(settingsDoc(
            PluginTestUtils.toggleSetting("dup"), PluginTestUtils.toggleSetting("dup")
        ), "duplicate setting")
        expectReject(settingsDoc(JSONObject().put("id", "BadId").put("label", "x")
            .put("type", "toggle").put("default", "true")), "id")
        expectReject(settingsDoc(PluginTestUtils.toggleSetting(default = "yes")), "toggle default")
        expectReject(settingsDoc(PluginTestUtils.numberSetting(default = "50")), "outside")
        expectReject(settingsDoc(PluginTestUtils.numberSetting(default = "abc")), "integer")
        expectReject(settingsDoc(PluginTestUtils.colorSetting(default = "#fff")), "#RRGGBB")
        expectReject(settingsDoc(PluginTestUtils.choiceSetting(default = "z")), "option values")
        expectReject(settingsDoc(JSONObject().put("id", "x").put("label", "x")
            .put("type", "slider").put("default", "1")), "unknown type")
    }

    @Test
    fun validatesSettingTokens() {
        val settings = JSONArray().put(PluginTestUtils.colorSetting("accent"))
        val theme = JSONObject().put("type", "theme").put("colors",
            JSONObject().put("foreground", "\${settings.accent}").put("background", "#000000"))
        val ok = PluginParser.parse(PluginTestUtils.document(settings = settings, contributions = JSONArray().put(theme)))
        assertEquals("\${settings.accent}", ok.theme()!!.colors["foreground"])
        val undeclared = JSONObject().put("type", "theme").put("colors",
            JSONObject().put("foreground", "\${settings.missing}").put("background", "#000000"))
        expectReject(PluginTestUtils.document(contributions = JSONArray().put(undeclared)), "not a valid color")
    }

    @Test
    fun validatesDependencies() {
        expectReject(PluginTestUtils.document(dependencies = JSONArray().put("com.example.test@1.0.0")), "itself")
        expectReject(PluginTestUtils.document(dependencies =
            JSONArray().put("com.example.base@1.0.0").put("com.example.base@2.0.0")), "duplicate")
        expectReject(PluginTestUtils.document(dependencies = JSONArray().put("com.example.base")), "id@minVersion")
        expectReject(PluginTestUtils.document(dependencies = JSONArray().put("com.example.base@abc")), "version")
    }
}
