package com.termux.app.plugins;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginManifestTest {

    @Test
    public void parsesValidManifest() throws Exception {
        PluginManifest manifest = PluginManifest.parse(
            PluginTestUtils.manifest("com.termux.sample.midnight", "1.0.0",
                PluginApi.TYPE_THEME, "\"com.termux.base@1.0.0\""), PluginTestUtils.CORE);
        assertEquals("com.termux.sample.midnight", manifest.id);
        assertEquals(PluginApi.CAPABILITY_THEME, manifest.capabilities.get(0));
        assertEquals(1, manifest.dependencies.size());
        assertEquals("com.termux.base", manifest.dependencies.get(0).id);
    }

    @Test
    public void rejectsUnknownField() {
        String json = PluginTestUtils.themeManifest("com.termux.sample.midnight", "1.0.0");
        final String withUnknownField = json.substring(0, json.length() - 1) + ",\"evil\":true}";
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(withUnknownField, PluginTestUtils.CORE));
    }

    @Test
    public void rejectsUnsupportedTypeAndCapability() {
        String script = PluginTestUtils.manifest("com.termux.sample.script", "1.0.0", "script", "");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(script, PluginTestUtils.CORE));

        String wrongCapability = PluginTestUtils.themeManifest(
            "com.termux.sample.midnight", "1.0.0")
            .replace(PluginApi.CAPABILITY_THEME, PluginApi.CAPABILITY_EXTRA_KEYS);
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(wrongCapability, PluginTestUtils.CORE));
    }

    @Test
    public void rejectsUnknownApiAndNewerCore() {
        String futureApi = PluginTestUtils.themeManifest("com.termux.sample.midnight", "1.0.0")
            .replace("\"apiVersion\":1", "\"apiVersion\":2");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(futureApi, PluginTestUtils.CORE));

        String newerCore = PluginTestUtils.themeManifest("com.termux.sample.midnight", "1.0.0")
            .replace("\"minCoreVersion\":\"0.0.0\"", "\"minCoreVersion\":\"9.0.0\"");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(newerCore, PluginTestUtils.CORE));
    }

    @Test
    public void rejectsOutOfRangeInteger() {
        String json = PluginTestUtils.themeManifest("com.termux.sample.midnight", "1.0.0")
            .replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(json, PluginTestUtils.CORE));
    }

    @Test
    public void rejectsMalformedAndDuplicateDependencies() {
        String malformed = PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
            "\"com.termux.base\"");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(malformed, PluginTestUtils.CORE));

        String duplicate = PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
            "\"com.termux.base@1.0.0\",\"com.termux.base@2.0.0\"");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(duplicate, PluginTestUtils.CORE));
    }

    @Test
    public void rejectsSelfDependency() {
        String self = PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
            "\"com.termux.sample.preset@1.0.0\"");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(self, PluginTestUtils.CORE));
    }

    @Test
    public void strictJsonRejectsNonStringCapabilities() throws Exception {
        String json = PluginTestUtils.themeManifest("com.termux.sample.midnight", "1.0.0")
            .replace("[\"" + PluginApi.CAPABILITY_THEME + "\"]", "[1]");
        assertThrows(PluginException.class,
            () -> PluginManifest.parse(json, PluginTestUtils.CORE));
        assertTrue(json.contains("[1]"));
    }

}
