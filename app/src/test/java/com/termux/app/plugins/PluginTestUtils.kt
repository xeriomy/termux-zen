package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/** Builders for valid v2 documents used across the core unit tests. */
object PluginTestUtils {

    fun themeContribution(fg: String = "#e6e6e6", bg: String = "#101820"): JSONObject =
        JSONObject()
            .put("type", "theme")
            .put("colors", JSONObject().put("foreground", fg).put("background", bg).put("cursor", "#ffb000"))

    fun keysContribution(): JSONObject =
        JSONObject()
            .put("type", "extra_keys")
            .put("style", "default")
            .put("keys", JSONArray().put(
                JSONArray().put(JSONObject().put("key", "ESC")).put(JSONObject().put("key", "TAB"))
            ))

    fun presetContribution(name: String = "Projects", dir: String? = "projects"): JSONObject {
        val obj = JSONObject().put("type", "session_preset").put("name", name)
        dir?.let { obj.put("workingDirectory", it) }
        return obj
    }

    fun terminalSettingsContribution(vararg values: Pair<String, String>): JSONObject {
        val obj = JSONObject().put("type", "terminal_settings")
        val map = JSONObject()
        for ((key, value) in values) map.put(key, value)
        return obj.put("values", map)
    }

    fun document(
        id: String = "com.example.test",
        name: String = "Test Plugin",
        version: String = "1.0.0",
        contributions: JSONArray = JSONArray().put(themeContribution()),
        settings: JSONArray? = null,
        dependencies: JSONArray? = null,
        minCoreVersion: String? = null,
        description: String? = "A test plugin."
    ): String {
        val obj = JSONObject()
            .put("schemaVersion", 2)
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("apiVersion", 2)
        minCoreVersion?.let { obj.put("minCoreVersion", it) }
        description?.let { obj.put("description", it) }
        settings?.let { obj.put("settings", it) }
        obj.put("contributions", contributions)
        dependencies?.let { obj.put("dependencies", it) }
        return obj.toString()
    }

    fun toggleSetting(id: String = "enabled_flag", default: String = "true"): JSONObject =
        JSONObject().put("id", id).put("label", "Flag").put("type", "toggle").put("default", default)

    fun colorSetting(id: String = "accent", default: String = "#ff6b6b"): JSONObject =
        JSONObject().put("id", id).put("label", "Accent").put("type", "color").put("default", default)

    fun numberSetting(id: String = "rate", default: String = "500", min: Int = 100, max: Int = 2000): JSONObject =
        JSONObject().put("id", id).put("label", "Rate").put("type", "number")
            .put("default", default).put("min", min).put("max", max)

    fun choiceSetting(id: String = "mode", default: String = "a"): JSONObject =
        JSONObject().put("id", id).put("label", "Mode").put("type", "choice").put("default", default)
            .put("options", JSONArray()
                .put(JSONObject().put("value", "a").put("label", "A"))
                .put(JSONObject().put("value", "b").put("label", "B")))

    fun textSetting(id: String = "label_text", default: String = "hello"): JSONObject =
        JSONObject().put("id", id).put("label", "Label").put("type", "text")
            .put("default", default).put("maxLength", 64)
}
