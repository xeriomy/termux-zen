package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Best-effort migration of v1 installed plugins (manifest.json + payload.json, one fixed
 * type) into the v2 single-document contribution model. Enabled state and source survive;
 * v1 had no settings. Plugins that cannot be converted are skipped and their files are
 * cleaned up as orphans. The v1 registry is preserved as registry.v1.bak.
 */
object PluginMigration {

    fun migrateIfNeeded(store: PluginStore): Boolean {
        if (store.loadRegistry() !is PluginStore.RegistryLoad.LegacyV1) return false
        val registryFile = File(store.root, "registry.json")
        val text = registryFile.readText(Charsets.UTF_8)
        val obj = JSONObject(text)
        val plugins = obj.optJSONArray("plugins") ?: JSONArray()
        val migrated = ArrayList<PluginRecord>()
        for (i in 0 until plugins.length()) {
            try {
                val recordObj = plugins.getJSONObject(i)
                val id = recordObj.getString("id")
                val version = Semver.parseOrThrow(recordObj.getString("version"), "v1 version")
                val enabled = recordObj.getBoolean("enabled")
                val source = recordObj.getString("source")
                val dir = store.pluginDir(id, version)
                val manifestText = File(dir, "manifest.json").readText(Charsets.UTF_8)
                val payloadText = File(dir, "payload.json").readText(Charsets.UTF_8)
                val v2Text = convert(manifestText, payloadText)
                PluginParser.parse(v2Text) // validate before committing
                val movedAside = store.writeInstalled(id, version, v2Text)
                movedAside?.deleteRecursively()
                migrated.add(
                    PluginRecord(id, version, enabled, source,
                        PluginStore.sha256Hex(v2Text.toByteArray(Charsets.UTF_8)), emptyMap())
                )
            } catch (e: Exception) {
                // Skip unconvertible plugins; the app keeps working.
            }
        }
        try {
            registryFile.copyTo(File(store.root, "registry.v1.bak"), overwrite = true)
        } catch (e: Exception) {
        }
        store.saveRegistry(migrated)
        return true
    }

    /** Converts one v1 manifest+payload pair into a v2 document. */
    internal fun convert(manifestText: String, payloadText: String): String {
        val manifest = JSONObject(manifestText)
        val payload = JSONObject(payloadText)
        val type = manifest.getString("type")
        val contribution = JSONObject()
        contribution.put("type", type)
        when (type) {
            ZenApi.TYPE_THEME -> contribution.put("colors", payload.getJSONObject("colors"))
            ZenApi.TYPE_EXTRA_KEYS -> {
                payload.optString("style").takeIf { it.isNotEmpty() }?.let { contribution.put("style", it) }
                // v1 allowed plain string cells; v2 cells are objects with exactly one of key/macro.
                val v1Rows = JSONArray(payload.getString("keys"))
                val v2Rows = JSONArray()
                for (r in 0 until v1Rows.length()) {
                    val row = v1Rows.getJSONArray(r)
                    val v2Row = JSONArray()
                    for (c in 0 until row.length()) {
                        val cell = row.get(c)
                        v2Row.put(if (cell is String) JSONObject().put("key", cell) else cell)
                    }
                    v2Rows.put(v2Row)
                }
                contribution.put("keys", v2Rows)
            }
            ZenApi.TYPE_SESSION_PRESET -> {
                contribution.put("name", payload.getString("name"))
                payload.optString("workingDirectory").takeIf { it.isNotEmpty() }
                    ?.let { contribution.put("workingDirectory", it) }
            }
            else -> throw PluginException("unknown v1 plugin type \"$type\"")
        }
        val v2 = JSONObject()
        v2.put("schemaVersion", ZenApi.SCHEMA_VERSION)
        v2.put("id", manifest.getString("id"))
        v2.put("name", manifest.getString("name"))
        v2.put("version", manifest.getString("version"))
        v2.put("apiVersion", ZenApi.API_VERSION)
        manifest.optString("minCoreVersion").takeIf { it.isNotEmpty() }
            ?.let { v2.put("minCoreVersion", it) }
        manifest.optString("author").takeIf { it.isNotEmpty() }?.let { v2.put("author", it) }
        manifest.optString("description").takeIf { it.isNotEmpty() }?.let { v2.put("description", it) }
        manifest.optJSONArray("dependencies")?.let { v2.put("dependencies", it) }
        v2.put("contributions", JSONArray().put(contribution))
        return v2.toString(2)
    }
}
