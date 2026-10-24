package com.termux.app.plugins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginTestUtils {

    static final String CORE = "0.118.0";
    static final Charset UTF_8 = Charset.forName("UTF-8");

    private PluginTestUtils() {
    }

    static String capabilityFor(String type) {
        if (PluginApi.TYPE_THEME.equals(type)) return PluginApi.CAPABILITY_THEME;
        if (PluginApi.TYPE_EXTRA_KEYS.equals(type)) return PluginApi.CAPABILITY_EXTRA_KEYS;
        return PluginApi.CAPABILITY_SESSION_PRESET;
    }

    static String manifest(String id, String version, String type, String dependenciesJson) {
        return "{"
            + "\"schemaVersion\":1,"
            + "\"id\":\"" + id + "\","
            + "\"name\":\"Test plugin\","
            + "\"version\":\"" + version + "\","
            + "\"apiVersion\":1,"
            + "\"minCoreVersion\":\"0.0.0\","
            + "\"type\":\"" + type + "\","
            + "\"capabilities\":[\"" + capabilityFor(type) + "\"],"
            + "\"description\":\"Test description\","
            + "\"author\":\"Test\","
            + "\"dependencies\":[" + dependenciesJson + "]"
            + "}";
    }

    static String themePayload() {
        return "{\"schemaVersion\":1,\"type\":\"theme\",\"colors\":{"
            + "\"foreground\":\"#ffffff\",\"background\":\"#000000\",\"cursor\":\"#ffffff\","
            + "\"color0\":\"#000000\",\"color1\":\"#111111\",\"color2\":\"#222222\","
            + "\"color3\":\"#333333\",\"color4\":\"#444444\",\"color5\":\"#555555\","
            + "\"color6\":\"#666666\",\"color7\":\"#777777\",\"color8\":\"#888888\","
            + "\"color9\":\"#999999\",\"color10\":\"#aaaaaa\",\"color11\":\"#bbbbbb\","
            + "\"color12\":\"#cccccc\",\"color13\":\"#dddddd\",\"color14\":\"#eeeeee\","
            + "\"color15\":\"#ffffff\"}}";
    }

    static String presetPayload(String name, String directory) {
        return "{\"schemaVersion\":1,\"type\":\"session_preset\",\"name\":\"" + name
            + "\",\"workingDirectory\":\"" + directory + "\"}";
    }

    static String extraKeysPayload() {
        return "{\"schemaVersion\":1,\"type\":\"extra_keys\","
            + "\"keys\":\"[[\\\"ESC\\\",\\\"TAB\\\",\\\"CTRL\\\"]]\",\"style\":\"default\"}";
    }

    static String themeManifest(String id, String version) {
        return manifest(id, version, PluginApi.TYPE_THEME, "");
    }

    static String presetManifest(String id, String version, String dependenciesJson) {
        return manifest(id, version, PluginApi.TYPE_SESSION_PRESET, dependenciesJson);
    }

    static byte[] zip(String manifest, String payload) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifest.getBytes(UTF_8));
        entries.put("payload.json", payload.getBytes(UTF_8));
        return zip(entries);
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static String base64(byte[] value) {
        return java.util.Base64.getEncoder().encodeToString(value);
    }

}
