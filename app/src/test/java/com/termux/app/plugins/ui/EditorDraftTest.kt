package com.termux.app.plugins.ui

import com.termux.app.plugins.PluginParser
import com.termux.app.plugins.PluginSettings
import com.termux.app.plugins.PluginTestUtils
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app editor must not let a user rewrite a plugin author's identity or settings schema. */
class EditorDraftTest {

    @Test
    fun onlyNewPluginsAllowSchemaAuthoring() {
        assertFalse(EditorDraft.blank().schemaLocked)
        val manifest = PluginParser.parse(PluginTestUtils.document())
        assertTrue(EditorDraft.from(manifest).schemaLocked)
    }

    @Test
    fun onlyInstalledDraftsCarryTheSettingsTargetId() {
        assertNull(EditorDraft.blank().pluginId)
        val manifest = PluginParser.parse(PluginTestUtils.document())
        val draft = EditorDraft.from(manifest)
        assertEquals(manifest.id, draft.pluginId)
        assertEquals(draft.pluginId, draft.originalId)
        assertEquals(manifest.settings, draft.settings.toList())
    }

    @Test
    fun installedDraftValuesFollowStoredOverrides() {
        // The installed-plugin editor renders exactly these effective values and writes
        // each change back through HubController.setSetting -> PluginInstaller.setSetting.
        val manifest = PluginParser.parse(
            PluginTestUtils.document(settings = JSONArray().put(PluginTestUtils.colorSetting("accent")))
        )
        val draft = EditorDraft.from(manifest)
        val values = PluginSettings.effectiveValues(manifest, mapOf("accent" to "#123456"))
        assertEquals("#123456", values[draft.settings.single().id])
        assertEquals(
            draft.settings.single().defaultValue,
            PluginSettings.effectiveValues(manifest, emptyMap())[draft.settings.single().id]
        )
    }

    @Test
    fun lockedDraftCannotBypassRestrictionsViaJsonMode() {
        val original = PluginParser.parse(PluginTestUtils.document(version = "1.0.0"))
        val draft = EditorDraft.from(original)
        val rewritten = PluginParser.parse(PluginTestUtils.document(version = "9.9.9"))

        // Entering raw-JSON mode is refused for an existing plugin.
        draft.enterJsonMode()
        assertFalse(draft.jsonMode)

        // Even if a stale jsonMode flag survived, saving must use the locked form values.
        draft.jsonMode = true
        draft.jsonText = PluginParser.toJson(rewritten)
        assertEquals(original.version, draft.toManifest().version)
    }
}
