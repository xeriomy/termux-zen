package com.termux.app.plugins;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginRepositoryClientTest {

    @Test
    public void parsesSignedIndexEntry() throws Exception {
        List<PluginRepositoryClient.CatalogEntry> entries =
            PluginRepositoryClient.parseIndex(index(
                "\"minCoreVersion\":\"0.100.0\",\"dependencies\":[\"com.termux.base@1.0.0\"],"));
        assertEquals(1, entries.size());
        PluginRepositoryClient.CatalogEntry entry = entries.get(0);
        assertEquals("com.termux.sample.theme", entry.id);
        assertTrue(entry.compatible);
        assertEquals(1, entry.dependencies.size());
    }

    @Test
    public void marksNewerCoreAsIncompatible() throws Exception {
        List<PluginRepositoryClient.CatalogEntry> entries =
            PluginRepositoryClient.parseIndex(index("\"minCoreVersion\":\"99.0.0\","));
        assertFalse(entries.get(0).compatible);
    }

    @Test
    public void rejectsNonHttpsArtifactUrl() {
        assertThrows(PluginException.class, () -> PluginRepositoryClient.parseIndex(
            indexJson("").replace("https://example.com/plugin.zip",
                "http://example.com/plugin.zip").getBytes(PluginTestUtils.UTF_8)));
    }

    @Test
    public void rejectsUnknownFieldsAndDuplicates() {
        String unknown = indexJson("").replace("\"plugins\":[", "\"evil\":1,\"plugins\":[");
        assertThrows(PluginException.class, () -> PluginRepositoryClient.parseIndex(
            unknown.getBytes(PluginTestUtils.UTF_8)));

        String duplicate = indexJson("").replace("\"plugins\":[",
            "\"plugins\":[" + entryJson("") + ",");
        assertThrows(PluginException.class, () -> PluginRepositoryClient.parseIndex(
            duplicate.getBytes(PluginTestUtils.UTF_8)));
    }

    private static byte[] index(String extraFields) {
        return indexJson(extraFields).getBytes(PluginTestUtils.UTF_8);
    }

    private static String indexJson(String extraFields) {
        return "{\"schemaVersion\":1,\"repository\":\"test\",\"plugins\":["
            + entryJson(extraFields) + "]}";
    }

    private static String entryJson(String extraFields) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("id", "com.termux.sample.theme");
            entry.put("name", "Midnight");
            entry.put("version", "1.0.0");
            entry.put("type", "theme");
            entry.put("description", "test");
            entry.put("url", "https://example.com/plugin.zip");
            entry.put("sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            entry.put("size", 1000);
            if (!extraFields.isEmpty()) {
                JSONObject extra = new JSONObject("{" + extraFields.substring(0,
                    extraFields.length() - 1) + "}");
                java.util.Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    entry.put(key, extra.get(key));
                }
            }
            return entry.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

}
