package com.termux.app.plugins

/**
 * Lifecycle operations: install/update/uninstall, enable/disable with dependency cascades
 * and exclusive-provider swapping, and per-plugin settings writes. Every operation validates
 * first, commits atomically, rolls back on normal errors and reloads the runtime only after
 * a successful commit. All methods are called on the manager's single background executor.
 */
class PluginInstaller(
    private val store: PluginStore,
    private val runtime: PluginRuntime,
    private val coreVersion: Semver
) {

    /** Identity a signed catalog entry promises; the artifact must match it exactly. */
    data class CatalogIdentity(val id: String, val version: Semver)

    // ---------------------------------------------------------------- install / update

    fun installArtifact(
        bytes: ByteArray,
        source: String,
        enable: Boolean,
        expected: CatalogIdentity? = null
    ): PluginManifest {
        val text = PluginArchive.readPluginJson(bytes)
        return installDocument(text, source, enable, expected)
    }

    /**
     * Installs or updates a plugin from its validated document text.
     * [enable] applies to fresh installs only; updates preserve the installed enabled state
     * and keep setting overrides whose ids are still declared.
     */
    fun installDocument(
        text: String,
        source: String,
        enable: Boolean,
        expected: CatalogIdentity? = null
    ): PluginManifest {
        if (source !in ZenApi.SOURCES) throw PluginException("unknown plugin source \"$source\"")
        val manifest = PluginParser.parse(text)
        if (expected != null && (manifest.id != expected.id || manifest.version != expected.version))
            throw PluginException("artifact does not match the signed catalog entry")
        if (manifest.minCoreVersion > coreVersion)
            throw PluginException("plugin requires Termux ${manifest.minCoreVersion} or newer")

        val records = currentRecords()
        val existing = records.firstOrNull { it.id == manifest.id }
        if (existing != null && manifest.version < existing.version)
            throw PluginException("cannot downgrade ${manifest.id} from ${existing.version} to ${manifest.version}")

        // Dependencies must already be installed; enabling also requires them enabled.
        val willBeEnabled = existing?.enabled ?: enable
        for (dependency in manifest.dependencies) {
            val depRecord = records.firstOrNull { it.id == dependency.id }
                ?: throw PluginException("install dependency ${dependency.id} first")
            if (depRecord.version < dependency.minVersion)
                throw PluginException("dependency ${dependency.id} needs version ${dependency.minVersion} or newer")
            if (willBeEnabled && !depRecord.enabled)
                throw PluginException("dependency ${dependency.id} is disabled; enable it first")
        }
        if (willBeEnabled) {
            val enabledManifests = loadManifests(records.filter { it.enabled && it.id != manifest.id })
            val candidate = enabledManifests + (manifest.id to manifest)
            if (manifest.id in findCyclicIds(candidate))
                throw PluginException("enabling this plugin would create a dependency cycle")
        }

        var updated: List<PluginRecord> = records
        if (willBeEnabled) {
            updated = swapExclusiveProviders(updated, manifest)
            // The exclusive swap must never disable a dependency of the plugin being enabled.
            for (dependency in manifest.dependencies) {
                val dep = updated.firstOrNull { it.id == dependency.id }
                if (dep == null || !dep.enabled)
                    throw PluginException("enabling this plugin would disable its dependency ${dependency.id}")
            }
        }

        val keptSettings = LinkedHashMap<String, String>()
        if (existing != null) {
            for (spec in manifest.settings) {
                existing.settings[spec.id]?.let { keptSettings[spec.id] = it }
            }
        }
        val record = PluginRecord(
            manifest.id, manifest.version, willBeEnabled,
            if (existing != null) existing.source else source,
            PluginStore.sha256Hex(text.toByteArray(Charsets.UTF_8)), keptSettings
        )
        val finalRecords = updated.filter { it.id != manifest.id } + record

        val movedAside = store.writeInstalled(manifest.id, manifest.version, text)
        try {
            store.saveRegistry(finalRecords)
        } catch (e: Exception) {
            // Roll back the directory commit; the old registry is still on disk.
            store.pluginDir(manifest.id, manifest.version).deleteRecursively()
            movedAside?.renameTo(store.pluginDir(manifest.id, manifest.version))
            throw e
        }
        movedAside?.deleteRecursively()
        store.cleanupOrphans(finalRecords)
        runtime.reload()
        return manifest
    }

    // ---------------------------------------------------------------- uninstall

    fun uninstall(id: String) {
        val records = currentRecords()
        if (records.none { it.id == id }) throw PluginException("plugin \"$id\" is not installed")
        var updated = cascadeDisable(records, setOf(id))
        updated = updated.filter { it.id != id }
        store.saveRegistry(updated)
        try {
            store.deletePluginFiles(id)
        } catch (e: PluginException) {
            // Already unregistered: it can never load again. Surface the leftover files.
            runtime.reload()
            throw PluginException("plugin removed, but some files could not be deleted: ${e.message}")
        }
        runtime.reload()
    }

    // ---------------------------------------------------------------- enable / disable

    fun setEnabled(id: String, enabled: Boolean) {
        val records = currentRecords()
        val record = records.firstOrNull { it.id == id }
            ?: throw PluginException("plugin \"$id\" is not installed")
        if (record.enabled == enabled) {
            runtime.reload()
            return
        }
        var updated: List<PluginRecord>
        if (enabled) {
            val manifest = PluginParser.parse(store.readVerified(record))
            if (manifest.minCoreVersion > coreVersion)
                throw PluginException("plugin requires Termux ${manifest.minCoreVersion} or newer")
            for (dependency in manifest.dependencies) {
                val depRecord = records.firstOrNull { it.id == dependency.id }
                    ?: throw PluginException("missing dependency ${dependency.id}")
                if (!depRecord.enabled)
                    throw PluginException("enable dependency ${dependency.id} first")
                if (depRecord.version < dependency.minVersion)
                    throw PluginException("dependency ${dependency.id} needs version ${dependency.minVersion} or newer")
            }
            val enabledManifests = loadManifests(records.filter { it.enabled && it.id != id })
            if (id in findCyclicIds(enabledManifests + (id to manifest)))
                throw PluginException("enabling this plugin would create a dependency cycle")
            updated = swapExclusiveProviders(records, manifest)
            for (dependency in manifest.dependencies) {
                val dep = updated.firstOrNull { it.id == dependency.id }
                if (dep == null || !dep.enabled)
                    throw PluginException("enabling this plugin would disable its dependency ${dependency.id}")
            }
            updated = updated.map { if (it.id == id) it.copy(enabled = true) else it }
        } else {
            updated = cascadeDisable(records, setOf(id))
        }
        store.saveRegistry(updated)
        runtime.reload()
    }

    // ---------------------------------------------------------------- settings

    fun setSetting(id: String, key: String, value: String) {
        val records = currentRecords()
        val record = records.firstOrNull { it.id == id }
            ?: throw PluginException("plugin \"$id\" is not installed")
        val manifest = PluginParser.parse(store.readVerified(record))
        val spec = manifest.settings.firstOrNull { it.id == key }
            ?: throw PluginException("plugin \"$id\" has no setting \"$key\"")
        val error = PluginSettings.validateValue(spec, value)
        if (error != null) throw PluginException("setting \"$key\" $error")
        val settings = LinkedHashMap(record.settings)
        settings[key] = value
        store.saveRegistry(records.map { if (it.id == id) it.copy(settings = settings) else it })
        runtime.reload()
    }

    fun resetSettings(id: String) {
        val records = currentRecords()
        if (records.none { it.id == id }) throw PluginException("plugin \"$id\" is not installed")
        store.saveRegistry(records.map { if (it.id == id) it.copy(settings = emptyMap()) else it })
        runtime.reload()
    }

    // ---------------------------------------------------------------- duplicate

    /** Creates an editable local copy of any installed plugin under a fresh id. */
    fun duplicate(id: String): PluginManifest {
        val records = currentRecords()
        val record = records.firstOrNull { it.id == id }
            ?: throw PluginException("plugin \"$id\" is not installed")
        val manifest = PluginParser.parse(store.readVerified(record))
        var newId = "${manifest.id}.copy"
        var suffix = 2
        while (records.any { it.id == newId } || !ZenApi.isValidId(newId)) {
            newId = "${manifest.id}.copy$suffix"
            suffix++
            if (suffix > 100) throw PluginException("cannot derive a free id for the copy")
        }
        val copy = manifest.copy(
            id = newId,
            name = "${manifest.name} (copy)".take(ZenApi.NAME_MAX_LENGTH),
            rawJson = ""
        )
        val text = PluginParser.toJson(copy)
        installDocument(text, ZenApi.SOURCE_LOCAL, enable = false)
        return PluginParser.parse(text)
    }

    // ---------------------------------------------------------------- helpers

    private fun currentRecords(): List<PluginRecord> = when (val load = store.loadRegistry()) {
        is PluginStore.RegistryLoad.Ok -> load.records
        else -> emptyList()
    }

    private fun loadManifests(records: List<PluginRecord>): Map<String, PluginManifest> {
        val result = LinkedHashMap<String, PluginManifest>()
        for (record in records) {
            try {
                result[record.id] = PluginParser.parse(store.readVerified(record))
            } catch (e: Exception) {
                // Invalid plugins contribute nothing and cannot participate in conflicts.
            }
        }
        return result
    }

    /** Disables every enabled plugin that transitively depends on any id in [disabled]. */
    private fun cascadeDisable(records: List<PluginRecord>, disabled: Set<String>): List<PluginRecord> {
        val off = disabled.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for (record in records) {
                if (!record.enabled || record.id in off) continue
                val manifest = try {
                    PluginParser.parse(store.readVerified(record))
                } catch (e: Exception) { continue }
                if (manifest.dependencies.any { it.id in off }) {
                    off.add(record.id)
                    changed = true
                }
            }
        }
        return records.map { if (it.id in off && it.enabled) it.copy(enabled = false) else it }
    }

    /**
     * When [manifest] will be enabled, disables other enabled providers of the same
     * exclusive contribution types (and their dependants).
     */
    private fun swapExclusiveProviders(records: List<PluginRecord>, manifest: PluginManifest): List<PluginRecord> {
        val exclusiveTypes = manifest.contributionTypes.filter { it in ZenApi.EXCLUSIVE_TYPES }.toSet()
        if (exclusiveTypes.isEmpty()) return records
        val conflicts = HashSet<String>()
        for (record in records) {
            if (!record.enabled || record.id == manifest.id) continue
            val other = try {
                PluginParser.parse(store.readVerified(record))
            } catch (e: Exception) { continue }
            if (other.contributionTypes.any { it in exclusiveTypes }) conflicts.add(record.id)
        }
        if (conflicts.isEmpty()) return records
        return cascadeDisable(records, conflicts)
    }

    private fun findCyclicIds(manifests: Map<String, PluginManifest>): Set<String> {
        val color = HashMap<String, Int>()
        val inCycle = HashSet<String>()
        val stack = ArrayList<String>()
        fun visit(id: String) {
            color[id] = 1
            stack.add(id)
            val manifest = manifests[id]
            if (manifest != null) {
                for (dependency in manifest.dependencies) {
                    val depId = dependency.id
                    if (depId !in manifests) continue
                    when (color[depId] ?: 0) {
                        1 -> {
                            val start = stack.indexOf(depId)
                            if (start >= 0) for (i in start until stack.size) inCycle.add(stack[i])
                        }
                        0 -> visit(depId)
                    }
                }
            }
            stack.removeAt(stack.size - 1)
            color[id] = 2
        }
        for (id in manifests.keys) if ((color[id] ?: 0) == 0) visit(id)
        return inCycle
    }
}
