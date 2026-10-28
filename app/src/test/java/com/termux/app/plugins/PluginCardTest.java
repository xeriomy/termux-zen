package com.termux.app.plugins;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginCardTest {

    @Test
    public void matchesVisibleStatusAndStatusDetail() {
        PluginCard disabled = card(PluginCard.Status.DISABLED, "");
        assertTrue(disabled.matches("disabled"));
        assertTrue(disabled.matches("DISABLED"));
        assertFalse(disabled.matches("enabled"));

        PluginCard attention = card(PluginCard.Status.NEEDS_ATTENTION,
            "This plugin could not be validated: bad checksum");
        assertTrue(attention.matches("needs attention"));
        assertTrue(attention.matches("bad checksum"));
    }

    @Test
    public void matchesIdentityAndMetadata() {
        PluginCard card = card(PluginCard.Status.ENABLED, "");
        assertTrue(card.matches("com.termux.sample.theme"));
        assertTrue(card.matches("1.0.0"));
        assertTrue(card.matches("terminal theme"));
        assertFalse(card.matches("extra keys"));
    }

    private static PluginCard card(PluginCard.Status status, String statusDetail) {
        return new PluginCard(PluginCard.Kind.INSTALLED, "com.termux.sample.theme",
            "Sample theme", "1.0.0", "Terminal theme", "A sample theme",
            "Bundled with Termux", statusDetail, Collections.emptyList(),
            Collections.emptyList(), status, PluginCard.PrimaryAction.DISABLE, true, 1);
    }

}
