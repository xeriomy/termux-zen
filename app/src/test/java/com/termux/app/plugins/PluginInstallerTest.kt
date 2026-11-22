package com.termux.app.plugins

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: PluginStore
    private lateinit var runtime: PluginRuntime
    private lateinit var installer: PluginInstaller
    private val core = Semver.parse("0.118.0")!!

    @Before
    fun setUp() {
        store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        runtime = PluginRuntime(store, core, tmp.newFolder("home").absolutePath)
        installer = PluginInstaller(store, runtime, core)
    }

    private fun records(): List<PluginRecord> =
        (store.loadRegistry() as PluginStore.RegistryLoad.Ok).records

    private fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected PluginException containing \"$fragment\"")
        } catch (e: PluginException) {
            assertTrue("message was: ${e.message}", e.message?.contains(fragment) == true)
        }
    }

    @Test
    fun freshInstallEnabledActivates() {
        installer.installDocument(PluginTestUtils.document(), ZenApi.SOURCE_BUNDLED, enable = true)
        assertTrue(records().single().enabled)
        assertNotNull(runtime.getThemeProperties())
    }

    @Test
    fun freshInstallDisabledStaysInactive() {
        installer.installDocument(PluginTestUtils.document(), ZenApi.SOURCE_REPOSITORY, enable = false)
        assertFalse(records().single().enabled)
    }

    @Test
    fun updatePreservesEnabledStateAndSettings() {
        val settings = JSONArray().put(PluginTestUtils.colorSetting("accent"))
        installer.installDocument(PluginTestUtils.document(settings = settings), ZenApi.SOURCE_LOCAL, enable = true)
        installer.setSetting("com.example.test", "accent", "#123456")
        installer.installDocument(
            PluginTestUtils.document(version = "1.1.0", settings = settings), ZenApi.SOURCE_LOCAL, enable = false
        )
        val record = records().single()
        assertEquals(Semver.parse("1.1.0"), record.version)
        assertTrue(record.enabled) // preserved, not the enable=false argument
        assertEquals("#123456", record.settings["accent"])
        assertFalse(store.pluginDir("com.example.test", Semver.parse("1.0.0")!!).exists())
    }

    @Test
    fun downgradeIsRejected() {
        installer.installDocument(PluginTestUtils.document(version = "2.0.0"), ZenApi.SOURCE_LOCAL, true)
        expectFailure("downgrade") {
            installer.installDocument(PluginTestUtils.document(version = "1.0.0"), ZenApi.SOURCE_LOCAL, true)
        }
    }

    @Test
    fun missingDependencyBlocksInstall() {
        expectFailure("dependency") {
            installer.installDocument(
                PluginTestUtils.document(dependencies = JSONArray().put("com.example.base@1.0.0")),
                ZenApi.SOURCE_LOCAL, true
            )
        }
    }

    @Test
    fun disabledDependencyBlocksEnable() {
        installer.installDocument(PluginTestUtils.document(id = "com.example.base"), ZenApi.SOURCE_LOCAL, false)
        expectFailure("disabled") {
            installer.installDocument(
                PluginTestUtils.document(id = "com.example.child",
                    dependencies = JSONArray().put("com.example.base@1.0.0")),
                ZenApi.SOURCE_LOCAL, true
            )
        }
    }

    @Test
    fun enablingThemeSwapsPreviousProvider() {
        installer.installDocument(PluginTestUtils.document(id = "com.example.old"), ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(PluginTestUtils.document(id = "com.example.new"), ZenApi.SOURCE_LOCAL, true)
        assertFalse(records().first { it.id == "com.example.old" }.enabled)
        assertTrue(records().first { it.id == "com.example.new" }.enabled)
        assertEquals("com.example.new", runtime.snapshot().themeProviderId)
    }

    @Test
    fun disablingDependencyCascades() {
        installer.installDocument(PluginTestUtils.document(id = "com.example.base",
            contributions = JSONArray().put(PluginTestUtils.presetContribution())), ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.child",
                contributions = JSONArray().put(PluginTestUtils.presetContribution("Child", null)),
                dependencies = JSONArray().put("com.example.base@1.0.0")),
            ZenApi.SOURCE_LOCAL, true
        )
        installer.setEnabled("com.example.base", false)
        assertFalse(records().first { it.id == "com.example.child" }.enabled)
        assertTrue(runtime.getSessionPresets().isEmpty())
    }

    @Test
    fun uninstallRemovesRecordFilesAndDependants() {
        File(tmp.root, "home/projects").mkdirs()
        installer.installDocument(PluginTestUtils.document(id = "com.example.base",
            contributions = JSONArray().put(PluginTestUtils.presetContribution())), ZenApi.SOURCE_LOCAL, true)
        installer.installDocument(
            PluginTestUtils.document(id = "com.example.child",
                contributions = JSONArray().put(PluginTestUtils.presetContribution("Child", null)),
                dependencies = JSONArray().put("com.example.base@1.0.0")),
            ZenApi.SOURCE_LOCAL, true
        )
        installer.uninstall("com.example.base")
        assertEquals(1, records().size)
        assertFalse(records().single().enabled)
        assertFalse(File(store.installedDir, "com.example.base").exists())
    }

    @Test
    fun setSettingValidatesAgainstSchema() {
        installer.installDocument(
            PluginTestUtils.document(settings = JSONArray().put(PluginTestUtils.numberSetting("rate"))),
            ZenApi.SOURCE_LOCAL, true
        )
        expectFailure("must be in") { installer.setSetting("com.example.test", "rate", "5") }
        expectFailure("no setting") { installer.setSetting("com.example.test", "ghost", "1") }
        installer.setSetting("com.example.test", "rate", "1500")
        assertEquals("1500", records().single().settings["rate"])
        installer.resetSettings("com.example.test")
        assertTrue(records().single().settings.isEmpty())
    }

    @Test
    fun duplicateCreatesEditableLocalCopy() {
        installer.installDocument(
            PluginTestUtils.document(contributions = JSONArray().put(PluginTestUtils.presetContribution())),
            ZenApi.SOURCE_BUNDLED, true
        )
        val copy = installer.duplicate("com.example.test")
        assertEquals("com.example.test.copy", copy.id)
        val record = records().first { it.id == copy.id }
        assertEquals(ZenApi.SOURCE_LOCAL, record.source)
        assertFalse(record.enabled)
        // Presets are non-exclusive: the copy can be active alongside the original.
        installer.setEnabled(copy.id, true)
        assertTrue(records().first { it.id == "com.example.test" }.enabled)
        installer.setEnabled(copy.id, false)
        assertTrue(records().first { it.id == "com.example.test" }.enabled)
    }

    @Test
    fun catalogIdentityMustMatch() {
        val identity = PluginInstaller.CatalogIdentity("com.example.other", Semver.parse("1.0.0")!!)
        expectFailure("catalog") {
            installer.installDocument(PluginTestUtils.document(), ZenApi.SOURCE_REPOSITORY, false, identity)
        }
    }

    @Test
    fun artifactInstallValidatesArchive() {
        val bytes = PluginArchive.write(PluginTestUtils.document())
        installer.installArtifact(bytes, ZenApi.SOURCE_IMPORT, enable = true)
        assertEquals(1, records().size)
        expectFailure("ZIP") { installer.installArtifact("garbage".toByteArray(), ZenApi.SOURCE_IMPORT, false) }
    }

    @Test
    fun enableIsIdempotentAndValidates() {
        installer.installDocument(PluginTestUtils.document(), ZenApi.SOURCE_LOCAL, true)
        installer.setEnabled("com.example.test", true)
        assertEquals(1, records().size)
        assertTrue(records().single().enabled)
    }
}
