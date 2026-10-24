package com.termux.app.plugins;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginRegistryTest {

    private static final Charset UTF_8 = PluginTestUtils.UTF_8;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesAndLoadsEntries() throws Exception {
        File dir = folder.newFolder("plugins");
        PluginRegistry registry = new PluginRegistry(dir);
        registry.put(new PluginRegistry.Entry("com.termux.sample.theme", "1.0.0",
            PluginApi.TYPE_THEME, true, PluginInstaller.SOURCE_BUNDLED, sha('a'), sha('b')));
        registry.save();

        PluginRegistry reloaded = new PluginRegistry(dir);
        reloaded.load();
        PluginRegistry.Entry entry = reloaded.find("com.termux.sample.theme");
        assertNotNull(entry);
        assertEquals("1.0.0", entry.version);
        assertTrue(entry.enabled);
        assertNull(reloaded.getLastError());
    }

    @Test
    public void corruptRegistryFailsClosed() throws Exception {
        File dir = folder.newFolder("plugins");
        try (FileOutputStream out = new FileOutputStream(new File(dir, "registry.json"))) {
            out.write("{not json".getBytes(UTF_8));
        }
        PluginRegistry registry = new PluginRegistry(dir);
        registry.load();
        assertTrue(registry.entries().isEmpty());
        assertNotNull(registry.getLastError());

        boolean quarantined = false;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files)
                if (file.getName().startsWith("registry.json.corrupt-")) quarantined = true;
        }
        assertTrue(quarantined);
    }

    @Test
    public void skipsInvalidRecordsButKeepsValidOnes() throws Exception {
        File dir = folder.newFolder("plugins");
        String json = "{\"schemaVersion\":1,\"plugins\":["
            + "{\"id\":\"com.termux.sample.theme\",\"version\":\"1.0.0\",\"type\":\"theme\","
            + "\"enabled\":true,\"source\":\"bundled\",\"manifestSha256\":\"" + sha('a')
            + "\",\"payloadSha256\":\"" + sha('b') + "\"},"
            + "{\"id\":\"bad id\",\"version\":\"1.0.0\",\"type\":\"theme\",\"enabled\":true,"
            + "\"source\":\"bundled\",\"manifestSha256\":\"" + sha('a')
            + "\",\"payloadSha256\":\"" + sha('b') + "\"}]}";
        try (FileOutputStream out = new FileOutputStream(new File(dir, "registry.json"))) {
            out.write(json.getBytes(UTF_8));
        }
        PluginRegistry registry = new PluginRegistry(dir);
        registry.load();
        assertEquals(1, registry.entries().size());
        assertNotNull(registry.find("com.termux.sample.theme"));
    }

    private static String sha(char fill) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 64; i++) value.append(fill);
        return value.toString();
    }

}
