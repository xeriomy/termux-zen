package com.termux.app.plugins

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var home: File
    private lateinit var runtime: PluginRuntime
    private lateinit var installer: PluginInstaller
    private val core = Semver.parse("0.118.0")!!

    @Before
    fun setUp() {
        store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        home = tmp.newFolder("home")
        runtime = PluginRuntime(store, core, home.absolutePath)
        installer = PluginInstaller(store, runtime, core)
    }

    private fun installTheme(id: String, enabled: Boolean = true, fg: String = "#e6e6e6") {
        installer.installDocument(
            PluginTestUtils.document(id = id, contributions = JSONArray().put(PluginTestUtils.themeContribution(fg))),
            ZenApi.SOURCE_LOCAL, enabled
        )
    }

    @Test
    fun enabledThemeBecomesActive() {
        installTheme("com.example.dark")
        val props = runtime.getThemeProperties()
        assertNotNull(props)
        assertEquals("#e6e6e6", props!!.getProperty("foreground"))
        assertEquals(PluginRuntime.Status.Active, runtime.getStatuses()["com.example.dark"])
    }

    @Test
    fun disabledThemeContributesNothing() {
        installTheme("com.example.dark", enabled = false)
        assertNull(runtime.getThemeProperties())
        assertEquals(PluginRuntime.Status.Disabled, runtime.getStatuses()["com.example.dark"])
    }

    @Test
    fun generationIncrementsOnReload() {
        val before = runtime.getGeneration()
        runtime.reload()
        assertTrue(runtime.getGeneration() > before)
    }

    @Test
    fun tamperedPluginFailsClosed() {
        installTheme("com.example.dark")
        val file = store.pluginFile("com.example.dark", Semver.parse("1.0.0")!!)
        file.writeText(file.readText().replace("#e6e6e6", "#000000"))
        runtime.reload()
        assertNull(runtime.getThemeProperties())
        assertTrue(runtime.getStatuses()["com.example.dark"] is PluginRuntime.Status.Invalid)
    }

    @Test
    fun exclusiveThemeFirstIdWins() {
        installTheme("com.example.aaa")
        installTheme("com.example.zzz")
        // The installer disables the first provider on swap; force both enabled to test runtime ordering.
        val records = (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records
        store.saveRegistry(records.map { it.copy(enabled = true) })
        runtime.reload()
        assertEquals("com.example.aaa", runtime.snapshot().themeProviderId)
        assertTrue(runtime.getStatuses()["com.example.zzz"] is PluginRuntime.Status.Overridden)
    }

    @Test
    fun missingDependencyIsInvalid() {
        installer.installDocument(PluginTestUtils.document(id = "com.example.base",
            contributions = JSONArray().put(PluginTestUtils.presetContribution("Base", null))),
            ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.child",
                contributions = JSONArray().put(PluginTestUtils.presetContribution("Child", null)),
                dependencies = JSONArray().put("com.example.base@1.0.0")),
            ZenApi.SOURCE_LOCAL, true
        )
        // Remove the base record directly (simulates a lost record); the child stays enabled.
        val records = (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records
        store.saveRegistry(records.filter { it.id != "com.example.base" })
        runtime.reload()
        assertTrue(runtime.getStatuses()["com.example.child"] is PluginRuntime.Status.Invalid)
    }

    @Test
    fun disabledDependencyIsInvalidAtRuntime() {
        installer.installDocument(PluginTestUtils.document(id = "com.example.base",
            contributions = JSONArray().put(PluginTestUtils.presetContribution("Base", null))),
            ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.child",
                contributions = JSONArray().put(PluginTestUtils.presetContribution("Child", null)),
                dependencies = JSONArray().put("com.example.base@1.0.0")),
            ZenApi.SOURCE_LOCAL, true
        )
        assertEquals(PluginRuntime.Status.Active, runtime.getStatuses()["com.example.child"])
        // Force base disabled without cascade to prove runtime re-checks.
        val records = (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records
        store.saveRegistry(records.map { if (it.id == "com.example.base") it.copy(enabled = false) else it })
        runtime.reload()
        assertTrue(runtime.getStatuses()["com.example.child"] is PluginRuntime.Status.Invalid)
    }

    @Test
    fun dependencyCycleIsInvalid() {
        val presets = JSONArray().put(PluginTestUtils.presetContribution("P", null))
        installer.installDocument(PluginTestUtils.document(id = "com.example.a", contributions = presets),
            ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(PluginTestUtils.document(id = "com.example.b", contributions = presets),
            ZenApi.SOURCE_LOCAL, true)
        // Rewrite both documents with mutual dependencies, keeping hashes consistent.
        val textA = PluginTestUtils.document(id = "com.example.a", contributions = presets,
            dependencies = JSONArray().put("com.example.b@1.0.0"))
        val textB = PluginTestUtils.document(id = "com.example.b", contributions = presets,
            dependencies = JSONArray().put("com.example.a@1.0.0"))
        store.writeInstalled("com.example.a", Semver.parse("1.0.0")!!, textA)
        store.writeInstalled("com.example.b", Semver.parse("1.0.0")!!, textB)
        val records = (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records
        store.saveRegistry(records.map {
            val text = if (it.id == "com.example.a") textA else textB
            it.copy(sha256 = PluginStore.sha256Hex(text.toByteArray()))
        })
        runtime.reload()
        assertTrue(runtime.getStatuses()["com.example.a"] is PluginRuntime.Status.Invalid)
        assertTrue(runtime.getStatuses()["com.example.b"] is PluginRuntime.Status.Invalid)
    }

    @Test
    fun presetsRequireExistingDirectoryUnderHome() {
        File(home, "projects").mkdirs()
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.presets", contributions = JSONArray()
                .put(PluginTestUtils.presetContribution("Projects", "projects"))
                .put(PluginTestUtils.presetContribution("Missing", "does-not-exist"))
                .put(PluginTestUtils.presetContribution("Home", null))),
            ZenApi.SOURCE_LOCAL, true
        )
        val presets = runtime.getSessionPresets()
        assertEquals(2, presets.size)
        assertEquals(File(home, "projects").canonicalPath, presets.first { it.name == "Projects" }.workingDirectory)
        assertEquals(home.canonicalPath, presets.first { it.name == "Home" }.workingDirectory)
    }

    @Test
    fun terminalSettingsMergeInIdOrder() {
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.a", contributions = JSONArray().put(
                PluginTestUtils.terminalSettingsContribution("bell-character" to "beep", "terminal-cursor-style" to "bar"))),
            ZenApi.SOURCE_LOCAL, true
        )
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.b", contributions = JSONArray().put(
                PluginTestUtils.terminalSettingsContribution("bell-character" to "ignore"))),
            ZenApi.SOURCE_LOCAL, true
        )
        val settings = runtime.getTerminalSettings()
        assertEquals("ignore", settings["bell-character"]) // later id wins
        assertEquals("bar", settings["terminal-cursor-style"])
    }

    @Test
    fun newerCoreRequirementIsInvalid() {
        // Bypass the installer (which rejects future-core plugins) to test the runtime check.
        val text = PluginTestUtils.document(id = "com.example.future", minCoreVersion = "99.0.0")
        store.writeInstalled("com.example.future", Semver.parse("1.0.0")!!, text)
        store.saveRegistry(listOf(PluginRecord(
            "com.example.future", Semver.parse("1.0.0")!!, true, ZenApi.SOURCE_LOCAL,
            PluginStore.sha256Hex(text.toByteArray()), emptyMap()
        )))
        runtime.reload()
        assertTrue(runtime.getStatuses()["com.example.future"] is PluginRuntime.Status.Invalid)
    }

    @Test
    fun settingsTokensResolveIntoActiveTheme() {
        val theme = org.json.JSONObject().put("type", "theme").put("colors",
            org.json.JSONObject().put("foreground", "\${settings.accent}").put("background", "#101820"))
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.configurable",
                settings = JSONArray().put(PluginTestUtils.colorSetting("accent", "#ff6b6b")),
                contributions = JSONArray().put(theme)),
            ZenApi.SOURCE_LOCAL, true
        )
        assertEquals("#ff6b6b", runtime.getThemeProperties()!!.getProperty("foreground"))
        installer.setSetting("com.example.configurable", "accent", "#00ff00")
        assertEquals("#00ff00", runtime.getThemeProperties()!!.getProperty("foreground"))
    }
}
