package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Properties;

import org.junit.Test;

/** A removed theme must leave the same palette as a fresh terminal, not the last plugin's. */
public class TerminalColorSchemeTest {

    @Test
    public void emptyPropertiesRestoreDefaultPalette() {
        int defaultBackground = new TerminalColorScheme().mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND];

        TerminalColorScheme scheme = new TerminalColorScheme();
        Properties pluginTheme = new Properties();
        pluginTheme.setProperty("background", "#123456");
        scheme.updateWith(pluginTheme);
        assertNotEquals(defaultBackground, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);

        // checkForFontAndColors() passes an empty Properties when no plugin theme is active.
        scheme.updateWith(new Properties());
        assertEquals(defaultBackground, scheme.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND]);
    }
}
