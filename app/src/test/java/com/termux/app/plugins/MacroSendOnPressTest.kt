package com.termux.app.plugins

import com.termux.app.plugins.ui.EditableCell
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Data-path tests for the macro send-on-press flag and its legacy `\n` migration. */
class MacroSendOnPressTest {

    private fun documentWithCell(cell: JSONObject): String = PluginTestUtils.document(
        contributions = JSONArray().put(
            JSONObject()
                .put("type", ZenApi.TYPE_EXTRA_KEYS)
                .put("style", "default")
                .put("keys", JSONArray().put(JSONArray().put(cell)))
        )
    )

    private fun macroCell(macro: String, sendOnPress: Boolean? = null): KeyCell {
        val cell = JSONObject().put("macro", macro).put("display", "M")
        if (sendOnPress != null) cell.put("sendOnPress", sendOnPress)
        return PluginParser.parse(documentWithCell(cell)).extraKeys()!!.rows[0][0]
    }

    @Test
    fun legacyLiteralNewlineMigratesToSendOnPress() {
        // Control characters are rejected by the document parser, so older macros used
        // the two characters backslash+n to mean "press Enter". They must now be sent as
        // a real Enter action rather than typed as those characters.
        val cell = macroCell("echo \$PWD\\n")
        assertEquals("echo \$PWD", cell.macro)
        assertTrue(cell.sendOnPress)
    }

    @Test
    fun absentFlagDefaultsToTypingOnly() {
        // Newly authored macros in the editor default to send-on-press (asserted below),
        // but documents without the field keep their original type-only behaviour.
        val cell = macroCell("echo hello")
        assertEquals("echo hello", cell.macro)
        assertFalse(cell.sendOnPress)
    }

    @Test
    fun explicitFlagSurvivesSerializationAndHostJson() {
        val manifest = PluginParser.parse(documentWithCell(
            JSONObject().put("macro", "ls").put("sendOnPress", true)
        ))
        val reParsed = PluginParser.parse(PluginParser.toJson(manifest))
        val cell = reParsed.extraKeys()!!.rows[0][0]
        assertEquals("ls", cell.macro)
        assertTrue(cell.sendOnPress)

        val resolved = PluginSettings.resolveContribution(
            manifest.extraKeys()!!, PluginSettings.effectiveValues(manifest, emptyMap())
        ) as Contribution.ExtraKeys
        val hostCell = JSONArray(PluginSettings.extraKeysToJsonString(resolved)).getJSONArray(0).getJSONObject(0)
        assertEquals("ls", hostCell.getString("macro"))
        assertTrue(hostCell.getBoolean("sendOnPress"))
    }

    @Test
    fun sendOnPressRequiresMacro() {
        val cell = JSONObject().put("key", "ESC").put("sendOnPress", true)
        try {
            PluginParser.parse(documentWithCell(cell))
            fail("expected a key-only cell with sendOnPress to be rejected")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("requires a macro"))
        }
    }

    @Test
    fun editorAuthorsSendOnPressByDefaultButKeepsLoadedFlag() {
        val newCell = EditableCell(key = "ESC")
        newCell.useMacro = true
        assertTrue(newCell.sendOnPress)
        assertTrue(newCell.toKeyCell().sendOnPress)

        val loaded = macroCell("echo hello")
        assertFalse(EditableCell.from(loaded).sendOnPress)
        assertTrue(EditableCell.from(macroCell("echo hello\\n")).sendOnPress)
    }
}
