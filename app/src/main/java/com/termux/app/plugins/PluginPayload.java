package com.termux.app.plugins;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

/** Parsed and strictly validated {@code payload.json}. Its meaning is fixed by the plugin type. */
public final class PluginPayload {

    public static final int MAX_BYTES = 128 * 1024;

    private static final Set<String> THEME_COLOR_KEYS;
    private static final Set<String> EXTRA_KEYS_STYLES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("default", "arrows-only", "arrows-all", "all", "none")));

    static {
        Set<String> keys = new HashSet<>();
        keys.add("foreground");
        keys.add("background");
        keys.add("cursor");
        for (int i = 0; i <= 15; i++) keys.add("color" + i);
        THEME_COLOR_KEYS = Collections.unmodifiableSet(keys);
    }

    public final String type;
    public final Properties themeProperties;
    public final String extraKeysJson;
    public final String extraKeysStyle;
    public final String presetName;
    public final String presetWorkingDirectory;

    private PluginPayload(String type, Properties themeProperties, String extraKeysJson,
                          String extraKeysStyle, String presetName, String presetWorkingDirectory) {
        this.type = type;
        this.themeProperties = themeProperties;
        this.extraKeysJson = extraKeysJson;
        this.extraKeysStyle = extraKeysStyle;
        this.presetName = presetName;
        this.presetWorkingDirectory = presetWorkingDirectory;
    }

    public static PluginPayload parse(String json, PluginManifest manifest) throws PluginException {
        if (json == null || json.getBytes().length > MAX_BYTES)
            throw new PluginException("Payload is missing or larger than " + MAX_BYTES + " bytes");

        JSONObject object = PluginJson.parseObject(json, "Payload");
        Set<String> allowed = new HashSet<>();
        allowed.add("schemaVersion");
        allowed.add("type");
        if (PluginApi.TYPE_THEME.equals(manifest.type)) allowed.add("colors");
        if (PluginApi.TYPE_EXTRA_KEYS.equals(manifest.type)) {
            allowed.add("keys");
            allowed.add("style");
        }
        if (PluginApi.TYPE_SESSION_PRESET.equals(manifest.type)) {
            allowed.add("name");
            allowed.add("workingDirectory");
        }
        PluginJson.rejectUnknownFields(object, allowed);

        if (PluginJson.requiredInt(object, "schemaVersion") != PluginApi.SCHEMA_VERSION)
            throw new PluginException("Unsupported payload schema version");
        String type = PluginJson.requiredString(object, "type");
        if (!type.equals(manifest.type))
            throw new PluginException("Payload type does not match manifest type");

        if (PluginApi.TYPE_THEME.equals(type)) return parseTheme(object);
        if (PluginApi.TYPE_EXTRA_KEYS.equals(type)) return parseExtraKeys(object);
        return parseSessionPreset(object);
    }

    private static PluginPayload parseTheme(JSONObject object) throws PluginException {
        Object colorsValue = object.opt("colors");
        if (!(colorsValue instanceof JSONObject))
            throw new PluginException("Theme payload must contain a 'colors' object");
        JSONObject colors = (JSONObject) colorsValue;

        PluginJson.rejectUnknownFields(colors, THEME_COLOR_KEYS);
        if (!colors.has("foreground") || !colors.has("background"))
            throw new PluginException("Theme must define foreground and background colors");

        Properties properties = new Properties();
        Iterator<String> keys = colors.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = colors.opt(key);
            if (!(value instanceof String))
                throw new PluginException("Color '" + key + "' must be a string");
            String color = (String) value;
            if (!isValidColor(color))
                throw new PluginException("Color '" + key + "' is not a supported hex color: " + color);
            properties.setProperty(key, color);
        }
        return new PluginPayload(PluginApi.TYPE_THEME, properties, null, null, null, null);
    }

    private static PluginPayload parseExtraKeys(JSONObject object) throws PluginException {
        String keys = PluginJson.requiredString(object, "keys");
        if (keys.isEmpty() || keys.length() > 8192)
            throw new PluginException("Extra keys definition must be 1 to 8192 characters");
        String style = PluginJson.optionalString(object, "style", "default");
        if (!EXTRA_KEYS_STYLES.contains(style))
            throw new PluginException("Unknown extra keys style: " + style);
        // Validate the host's JSON shape here without constructing ExtraKeysInfo, which needs
        // Android classes and would make payload validation untestable on the JVM. The host
        // parser still runs at render time and fails closed to the default layout.
        validateExtraKeysDefinition(keys);
        return new PluginPayload(PluginApi.TYPE_EXTRA_KEYS, null, keys, style, null, null);
    }

    private static void validateExtraKeysDefinition(String keys) throws PluginException {
        try {
            JSONArray rows = new JSONArray(keys);
            for (int i = 0; i < rows.length(); i++) {
                Object rowValue = rows.get(i);
                if (!(rowValue instanceof JSONArray))
                    throw new PluginException("Each extra keys row must be an array");
                JSONArray row = (JSONArray) rowValue;
                for (int j = 0; j < row.length(); j++) validateExtraKey(row.get(j), 0);
            }
        } catch (JSONException e) {
            throw new PluginException("Invalid extra keys definition", e);
        }
    }

    private static void validateExtraKey(Object value, int depth) throws PluginException {
        if (value instanceof String) return;
        if (!(value instanceof JSONObject))
            throw new PluginException("Extra keys entries must be strings or objects");
        if (depth > 4) throw new PluginException("Extra keys popup nesting is too deep");

        JSONObject button = (JSONObject) value;
        boolean hasKey = button.has("key");
        boolean hasMacro = button.has("macro");
        if (hasKey == hasMacro)
            throw new PluginException("Each extra key must define exactly one of key or macro");
        String field = hasKey ? "key" : "macro";
        if (!(button.opt(field) instanceof String))
            throw new PluginException("Extra key '" + field + "' must be a string");
        if (button.has("display") && !(button.opt("display") instanceof String))
            throw new PluginException("Extra key 'display' must be a string");
        if (button.has("popup")) {
            Object popup = button.opt("popup");
            if (!(popup instanceof JSONObject))
                throw new PluginException("Extra key 'popup' must be an object");
            validateExtraKey(popup, depth + 1);
        }
    }

    private static PluginPayload parseSessionPreset(JSONObject object) throws PluginException {
        String name = PluginJson.requiredString(object, "name");
        if (name.isEmpty() || name.length() > 48)
            throw new PluginException("Preset name must be 1 to 48 characters");
        PluginJson.rejectControlCharacters(name, "Preset name");

        String workingDirectory = PluginJson.optionalString(object, "workingDirectory", "");
        if (!isSafeRelativePath(workingDirectory))
            throw new PluginException("Preset working directory must be a relative path inside the home directory");

        return new PluginPayload(PluginApi.TYPE_SESSION_PRESET, null, null, null, name, workingDirectory);
    }

    private static boolean isValidColor(String color) {
        if (color == null || color.length() < 4 || color.charAt(0) != '#') return false;
        int digits = color.length() - 1;
        if (digits != 3 && digits != 6 && digits != 9 && digits != 12) return false;
        for (int i = 1; i < color.length(); i++) {
            char c = color.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static boolean isSafeRelativePath(String path) {
        if (path == null || path.length() > 256) return false;
        if (path.isEmpty() || ".".equals(path)) return true;
        if (path.charAt(0) == '/' || path.charAt(0) == '~' || path.indexOf('\\') >= 0) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }


}
