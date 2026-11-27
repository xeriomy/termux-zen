package com.termux.app.terminal.io

import com.termux.shared.termux.extrakeys.MacroKeySequence
import com.termux.shared.termux.extrakeys.MacroKeySequence.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the macro token path used by TerminalExtraKeys. These are pure JVM
 * tests: [MacroKeySequence] has no Android dependencies, so the exact dispatched actions
 * can be asserted without a terminal view.
 */
class MacroKeySequenceTest {

    private val namedKeys = setOf(
        "SPACE", "ESC", "TAB", "ENTER", "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END",
        "PGUP", "PGDN", "INS", "DEL", "BKSP", "F1", "F12", "KEYBOARD", "COPY", "PASTE"
    )

    private fun parse(macro: String, sendOnPress: Boolean = false) =
        MacroKeySequence.parse(macro, sendOnPress, namedKeys)

    @Test
    fun textMacroPreservesSpacesBetweenTokens() {
        // Before the fix TerminalExtraKeys wrote each token separately without a separator,
        // so "echo hello world" was typed as "echohelloworld".
        assertEquals(listOf(KeyAction("echo hello world", false, false, false, false)),
            parse("echo hello world"))
    }

    @Test
    fun textMacroPreservesRepeatedSpaces() {
        assertEquals(listOf(KeyAction("echo  hello", false, false, false, false)),
            parse("echo  hello"))
    }

    @Test
    fun keyNameMacroDoesNotInjectSpaces() {
        // Spaces in a key sequence are separators, not literal text. CTRL applies to "f"
        // only, then "d" is sent without a space in front of it.
        assertEquals(
            listOf(
                KeyAction("f", true, false, false, false),
                KeyAction("d", false, false, false, false)
            ),
            parse("CTRL f d")
        )
    }

    @Test
    fun namedKeyInsideTextMacroDoesNotDuplicateSpaces() {
        assertEquals(
            listOf(
                KeyAction("echo", false, false, false, false),
                KeyAction("SPACE", false, false, false, false),
                KeyAction("world", false, false, false, false)
            ),
            parse("echo SPACE world")
        )
    }

    @Test
    fun modifiersApplyToTextRun() {
        assertEquals(listOf(KeyAction("a", true, false, false, false)),
            parse("CTRL a"))
    }

    @Test
    fun sendOnPressAppendsEnterOnlyWhenEnabled() {
        val typingOnly = parse("echo hello", sendOnPress = false)
        assertEquals(1, typingOnly.size)
        assertEquals("echo hello", typingOnly[0].key)

        val sending = parse("echo hello", sendOnPress = true)
        assertEquals(2, sending.size)
        assertEquals("echo hello", sending[0].key)
        assertEquals(KeyAction("ENTER", false, false, false, false), sending[1])

        val sendingKeySequence = parse("CTRL a", sendOnPress = true)
        assertEquals(2, sendingKeySequence.size)
        assertEquals(KeyAction("a", true, false, false, false), sendingKeySequence[0])
        assertEquals(KeyAction("ENTER", false, false, false, false), sendingKeySequence[1])
    }
}
