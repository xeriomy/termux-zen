package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun v1Manifest(id: String, type: String): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", id)
        .put("name", "Legacy $type")
        .put("version", "1.0.0")
        .put("apiVersion", 1)
        .put("type", type)
        .put("capabilities", JSONArray().put(when (type) {
            "theme" -> "terminal.theme"
            "extra_keys" -> "terminal.extra_keys"
            else -> "sessions.preset"
        }))
        .toString()

    private fun v1Payload(type: String): String = when (type) {
        "theme" -> JSONObject().put("schemaVersion", 1).put("type", "theme")
            .put("colors", JSONObject().put("foreground", "#eeeeee").put("background", "#101820")).toString()
        "extra_keys" -> JSONObject().put("schemaVersion", 1).put("type", "extra_keys")
            .put("keys", "[[\"ESC\",\"TAB\"]]").toString()
        else -> JSONObject().put("schemaVersion", 1).put("type", "session_preset")
            .put("name", "Projects").put("workingDirectory", "projects").toString()
    }

    private fun seedV1(store: PluginStore, id: String, type: String, enabled: Boolean) {
        val dir = store.pluginDir(id, Semver.parse("1.0.0")!!)
        dir.mkdirs()
        File(dir, "manifest.json").writeText(v1Manifest(id, type))
        File(dir, "payload.json").writeText(v1Payload(type))
    }

    @Test
    fun migratesAllThreeV1Types() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        seedV1(store, "com.example.theme", "theme", true)
        seedV1(store, "com.example.keys", "extra_keys", false)
        seedV1(store, "com.example.preset", "session_preset", true)
        val registry = JSONObject().put("schemaVersion", 1).put("plugins", JSONArray()
            .put(v1Record("com.example.theme", "theme", true))
            .put(v1Record("com.example.keys", "extra_keys", false))
            .put(v1Record("com.example.preset", "session_preset", true)))
        File(store.root, "registry.json").writeText(registry.toString())

        assertTrue(PluginMigration.migrateIfNeeded(store))
        val load = store.loadRegistry()
        assertTrue(load is PluginStore.RegistryLoad.Ok)
        val records = (load as PluginStore.RegistryLoad.Ok).records
        assertEquals(3, records.size)
        assertTrue(records.first { it.id == "com.example.theme" }.enabled)
        assertFalse(records.first { it.id == "com.example.keys" }.enabled)
        assertTrue(File(store.root, "registry.v1.bak").exists())

        // The migrated documents parse as v2 and load into a runtime.
        val home = tmp.newFolder("home")
        File(home, "projects").mkdirs()
        val runtime = PluginRuntime(store, Semver.parse("0.118.0")!!, home.absolutePath)
        runtime.reload()
        assertEquals("#eeeeee", runtime.getThemeProperties()!!.getProperty("foreground"))
        assertEquals(1, runtime.getSessionPresets().size)
        // Old v1 files are gone from the version directory.
        assertFalse(File(store.pluginDir("com.example.theme", Semver.parse("1.0.0")!!), "manifest.json").exists())
    }

    private fun v1Record(id: String, type: String, enabled: Boolean): JSONObject = JSONObject()
        .put("id", id).put("version", "1.0.0").put("type", type).put("enabled", enabled)
        .put("source", "bundled").put("manifestSha256", "x").put("payloadSha256", "y")

    @Test
    fun skipsUnconvertiblePlugins() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        seedV1(store, "com.example.good", "theme", true)
        val badDir = store.pluginDir("com.example.bad", Semver.parse("1.0.0")!!)
        badDir.mkdirs()
        File(badDir, "manifest.json").writeText("{broken")
        File(badDir, "payload.json").writeText("{}")
        File(store.root, "registry.json").writeText(JSONObject().put("schemaVersion", 1)
            .put("plugins", JSONArray()
                .put(v1Record("com.example.good", "theme", true))
                .put(v1Record("com.example.bad", "theme", true))).toString())
        assertTrue(PluginMigration.migrateIfNeeded(store))
        val records = (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records
        assertEquals(1, records.size)
        assertEquals("com.example.good", records[0].id)
    }

    @Test
    fun noMigrationForV2OrEmpty() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        assertFalse(PluginMigration.migrateIfNeeded(store))
        store.saveRegistry(emptyList())
        assertFalse(PluginMigration.migrateIfNeeded(store))
    }
}
