package com.termux.app.terminal.io

import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The host-side JSON cell must carry the author's send-on-press flag into the click path. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExtraKeyButtonTest {

    private val displayMap = ExtraKeysConstants.ExtraKeyDisplayMap()

    @Test
    fun readsMacroSendOnPressFlag() {
        val sending = ExtraKeyButton(
            JSONObject().put("macro", "echo hello world").put("sendOnPress", true),
            displayMap, ExtraKeysConstants.CONTROL_CHARS_ALIASES
        )
        assertTrue(sending.isMacro)
        assertTrue(sending.isSendOnPress)
        assertEquals("echo hello world", sending.key)

        val typing = ExtraKeyButton(
            JSONObject().put("macro", "echo hello"),
            displayMap, ExtraKeysConstants.CONTROL_CHARS_ALIASES
        )
        assertFalse(typing.isSendOnPress)
    }
}
