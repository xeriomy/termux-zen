package com.termux.app.plugins;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Strict JSON helpers: unknown fields and wrong value types are rejected. */
final class PluginJson {

    private PluginJson() {
    }

    static void rejectUnknownFields(JSONObject object, Set<String> allowed) throws PluginException {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key))
                throw new PluginException("Unknown field: " + key);
        }
    }

    static String requiredString(JSONObject object, String key) throws PluginException {
        Object value = object.opt(key);
        if (!(value instanceof String))
            throw new PluginException("Field '" + key + "' must be a string");
        return (String) value;
    }

    static String optionalString(JSONObject object, String key, String fallback) throws PluginException {
        if (!object.has(key)) return fallback;
        return requiredString(object, key);
    }

    static int requiredInt(JSONObject object, String key) throws PluginException {
        Object value = object.opt(key);
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new PluginException("Field '" + key + "' must be an integer");
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new PluginException("Field '" + key + "' is out of range");
        return (int) number;
    }

    static List<String> optionalStringArray(JSONObject object, String key, int maxLength)
        throws PluginException {
        List<String> values = new ArrayList<>();
        if (!object.has(key)) return values;
        Object value = object.opt(key);
        if (!(value instanceof JSONArray))
            throw new PluginException("Field '" + key + "' must be an array of strings");
        JSONArray array = (JSONArray) value;
        if (array.length() > maxLength)
            throw new PluginException("Field '" + key + "' has too many entries");
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            if (!(entry instanceof String))
                throw new PluginException("Field '" + key + "' must contain only strings");
            values.add((String) entry);
        }
        return values;
    }

    static void rejectControlCharacters(String value, String what) throws PluginException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f)
                throw new PluginException(what + " must not contain control characters");
        }
    }

    static JSONObject parseObject(String json, String what) throws PluginException {
        if (json == null || json.isEmpty())
            throw new PluginException(what + " is empty");
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new PluginException(what + " is not valid JSON", e);
        }
    }

}
