package com.termux.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): PluginStore {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.ensureDirectories()
        return store
    }

    private fun record(id: String = "com.example.test", enabled: Boolean = true) = PluginRecord(
        id, Semver.parse("1.0.0")!!, enabled, ZenApi.SOURCE_LOCAL,
        PluginStore.sha256Hex(PluginTestUtils.document(id = id).toByteArray()), emptyMap()
    )

    @Test
    fun registryRoundTrip() {
        val store = newStore()
        val records = listOf(record(), record("com.example.other", enabled = false).copy(
            settings = mapOf("accent" to "#abcdef")
        ))
        store.saveRegistry(records)
        val load = store.loadRegistry()
        assertTrue(load is PluginStore.RegistryLoad.Ok)
        val loaded = (load as PluginStore.RegistryLoad.Ok).records
        assertEquals(2, loaded.size)
        assertEquals("#abcdef", loaded.first { it.id == "com.example.other" }.settings["accent"])
        assertFalse(loaded.first { it.id == "com.example.other" }.enabled)
    }

    @Test
    fun emptyWhenNoRegistry() {
        assertTrue(newStore().loadRegistry() is PluginStore.RegistryLoad.Empty)
    }

    @Test
    fun corruptRegistryIsQuarantinedNotOverwritten() {
        val store = newStore()
        File(store.root, "registry.json").writeText("{not json")
        assertTrue(store.loadRegistry() is PluginStore.RegistryLoad.Empty)
        val quarantined = store.root.listFiles()!!.filter { it.name.startsWith("registry.json.corrupt-") }
        assertEquals(1, quarantined.size)
        // A subsequent save must not destroy the evidence.
        store.saveRegistry(listOf(record()))
        assertEquals(1, quarantined.size)
        assertTrue(File(store.root, "registry.json").exists())
    }

    @Test
    fun detectsLegacyV1Registry() {
        val store = newStore()
        File(store.root, "registry.json").writeText("""{"schemaVersion":1,"plugins":[]}""")
        assertTrue(store.loadRegistry() is PluginStore.RegistryLoad.LegacyV1)
    }

    @Test
    fun readVerifiedDetectsTampering() {
        val store = newStore()
        val text = PluginTestUtils.document()
        store.writeInstalled("com.example.test", Semver.parse("1.0.0")!!, text)
        val rec = record()
        assertEquals(text, store.readVerified(rec))
        store.pluginFile(rec.id, rec.version).writeText(text.replace("Test Plugin", "Evil"))
        try {
            store.readVerified(rec)
            fail("expected integrity failure")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("integrity"))
        }
    }

    @Test
    fun writeInstalledMovesOldVersionAside() {
        val store = newStore()
        val v1 = Semver.parse("1.0.0")!!
        store.writeInstalled("com.example.test", v1, PluginTestUtils.document(version = "1.0.0"))
        val moved = store.writeInstalled("com.example.test", v1, PluginTestUtils.document(version = "1.0.0", name = "New"))
        assertNotNull(moved)
        assertTrue(moved!!.exists())
        assertEquals("New", PluginParser.parse(store.pluginFile("com.example.test", v1).readText()).name)
        moved.deleteRecursively()
    }

    @Test
    fun deletePluginFilesRemovesEverything() {
        val store = newStore()
        store.writeInstalled("com.example.test", Semver.parse("1.0.0")!!, PluginTestUtils.document())
        store.deletePluginFiles("com.example.test")
        assertFalse(File(store.installedDir, "com.example.test").exists())
    }

    @Test
    fun unsafePathSegmentsAreRejected() {
        val store = newStore()
        for (bad in listOf("../evil", "a/b", "..", ".")) {
            try {
                store.pluginDir(bad, Semver.parse("1.0.0")!!)
                fail("expected rejection of $bad")
            } catch (e: PluginException) {
                assertTrue(e.message!!.contains("unsafe"))
            }
        }
    }

    @Test
    fun cleanupOrphansKeepsOnlyReferencedVersions() {
        val store = newStore()
        store.writeInstalled("com.example.test", Semver.parse("1.0.0")!!, PluginTestUtils.document())
        store.writeInstalled("com.example.test", Semver.parse("2.0.0")!!, PluginTestUtils.document(version = "2.0.0"))
        store.writeInstalled("com.example.gone", Semver.parse("1.0.0")!!, PluginTestUtils.document(id = "com.example.gone"))
        store.cleanupOrphans(listOf(record()))
        assertTrue(store.pluginFile("com.example.test", Semver.parse("1.0.0")!!).exists())
        assertFalse(store.pluginFile("com.example.test", Semver.parse("2.0.0")!!).exists())
        assertFalse(File(store.installedDir, "com.example.gone").exists())
    }

    @Test
    fun stagingIsCleared() {
        val store = newStore()
        val staging = File(store.root, ".staging")
        staging.mkdirs()
        File(staging, "leftover").writeText("x")
        store.clearStaging()
        assertFalse(staging.exists())
    }
}
