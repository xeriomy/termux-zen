package com.termux.app.plugins;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginPayloadTest {

    private final PluginManifest theme = parse(PluginTestUtils.themeManifest(
        "com.termux.sample.theme", "1.0.0"));

    @Test
    public void parsesThemeColors() throws Exception {
        PluginPayload payload = PluginPayload.parse(PluginTestUtils.themePayload(), theme);
        assertEquals("#ffffff", payload.themeProperties.getProperty("foreground"));
        assertEquals("#000000", payload.themeProperties.getProperty("background"));
    }

    @Test
    public void rejectsInvalidOrUnknownColors() {
        String invalid = PluginTestUtils.themePayload().replace("#000000", "#zzzzzz");
        assertThrows(PluginException.class, () -> PluginPayload.parse(invalid, theme));

        String unknown = PluginTestUtils.themePayload()
            .replace("\"color15\":\"#ffffff\"", "\"color15\":\"#ffffff\",\"color16\":\"#ffffff\"");
        assertThrows(PluginException.class, () -> PluginPayload.parse(unknown, theme));
    }

    @Test
    public void requiresForegroundAndBackground() {
        String missingBackground = PluginTestUtils.themePayload()
            .replace("\"background\":\"#000000\",", "");
        assertThrows(PluginException.class,
            () -> PluginPayload.parse(missingBackground, theme));
    }

    @Test
    public void acceptsValidPresetAndRejectsEscapes() throws Exception {
        PluginManifest preset = parse(PluginTestUtils.presetManifest(
            "com.termux.sample.preset", "1.0.0", ""));
        PluginPayload payload = PluginPayload.parse(
            PluginTestUtils.presetPayload("Home", "projects/app"), preset);
        assertEquals("projects/app", payload.presetWorkingDirectory);

        assertThrows(PluginException.class, () -> PluginPayload.parse(
            PluginTestUtils.presetPayload("Escape", "../outside"), preset));
        assertThrows(PluginException.class, () -> PluginPayload.parse(
            PluginTestUtils.presetPayload("Absolute", "/data/data/com.termux"), preset));
        assertThrows(PluginException.class, () -> PluginPayload.parse(
            PluginTestUtils.presetPayload("Tilde", "~/other"), preset));
    }

    @Test
    public void validatesExtraKeysWithHostParser() throws Exception {
        PluginManifest extraKeys = parse(PluginTestUtils.manifest(
            "com.termux.sample.keys", "1.0.0", PluginApi.TYPE_EXTRA_KEYS, ""));
        PluginPayload payload = PluginPayload.parse(PluginTestUtils.extraKeysPayload(), extraKeys);
        assertEquals("default", payload.extraKeysStyle);
        assertTrue(payload.extraKeysJson.contains("ESC"));

        String broken = PluginTestUtils.extraKeysPayload().replace("[[", "[[[");
        assertThrows(PluginException.class, () -> PluginPayload.parse(broken, extraKeys));
    }

    @Test
    public void rejectsNestedExtraKeyPopups() {
        PluginManifest extraKeys = parse(PluginTestUtils.manifest(
            "com.termux.sample.keys", "1.0.0", PluginApi.TYPE_EXTRA_KEYS, ""));
        String nested = "{\"schemaVersion\":1,\"type\":\"extra_keys\","
            + "\"keys\":\"[[{\\\"key\\\":\\\"ESC\\\",\\\"popup\\\":{"
            + "\\\"key\\\":\\\"CTRL\\\",\\\"popup\\\":{\\\"key\\\":\\\"ALT\\\"}}}]]\","
            + "\"style\":\"default\"}";
        assertThrows(PluginException.class, () -> PluginPayload.parse(nested, extraKeys));
    }

    @Test
    public void rejectsPayloadTypeMismatch() {
        String mismatch = PluginTestUtils.themePayload().replace("\"theme\"", "\"session_preset\"");
        assertThrows(PluginException.class, () -> PluginPayload.parse(mismatch, theme));
    }

    private static PluginManifest parse(String json) {
        try {
            return PluginManifest.parse(json, PluginTestUtils.CORE);
        } catch (PluginException e) {
            throw new AssertionError(e);
        }
    }

}
