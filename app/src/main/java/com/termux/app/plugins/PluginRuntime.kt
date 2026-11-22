package com.termux.app.plugins

import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resolves installed, enabled plugins into active host contributions. Fail-closed: any
 * plugin that fails integrity, compatibility, dependency or resolution checks contributes
 * nothing for this generation, without persisting a disable. Pure JVM (no Android imports)
 * so the resolution rules are unit-testable.
 *
 * Host hooks read the pull-based accessors below; [getGeneration] lets activities detect
 * state changes that happened while another activity was on top.
 */
class PluginRuntime(
    private val store: PluginStore,
    private val coreVersion: Semver,
    private val homeDir: String
) {

    /** A validated, resolved session preset. Fields are public for Java host hooks. */
    class SessionPreset(@JvmField val name: String, @JvmField val workingDirectory: String)

    sealed class Status {
        /** Contributes to the host right now. */
        object Active : Status()
        /** Switched off by the user (or cascaded from a dependency). */
        object Disabled : Status()
        /** Failed validation this generation; contributes nothing. */
        data class Invalid(val reason: String) : Status()
        /** Lost an exclusive contribution (theme/extra keys) to another enabled plugin. */
        data class Overridden(val byPluginId: String) : Status()
    }

    data class ActiveState(
        val generation: Int,
        val themeProperties: Properties?,
        val themeProviderId: String?,
        val extraKeysJson: String?,
        val extraKeysStyle: String?,
        val extraKeysProviderId: String?,
        val sessionPresets: List<SessionPreset>,
        val terminalSettings: Map<String, String>,
        val statuses: Map<String, Status>
    )

    @Volatile
    private var state = ActiveState(0, null, null, null, null, null, emptyList(), emptyMap(), emptyMap())

    private val listeners = CopyOnWriteArrayList<Runnable>()

    fun addChangeListener(listener: Runnable) { listeners.add(listener) }
    fun removeChangeListener(listener: Runnable) { listeners.remove(listener) }

    // Java-friendly pull accessors used by the terminal host hooks.
    fun getGeneration(): Int = state.generation
    fun getThemeProperties(): Properties? = state.themeProperties
    fun getExtraKeysJson(): String? = state.extraKeysJson
    fun getExtraKeysStyle(): String? = state.extraKeysStyle
    fun getSessionPresets(): List<SessionPreset> = state.sessionPresets
    fun getTerminalSettings(): Map<String, String> = state.terminalSettings
    fun getStatuses(): Map<String, Status> = state.statuses
    fun snapshot(): ActiveState = state

    /** Re-reads the registry and every enabled plugin, then atomically swaps active state. */
    @Synchronized
    fun reload() {
        val records = when (val load = store.loadRegistry()) {
            is PluginStore.RegistryLoad.Ok -> load.records
            else -> emptyList()
        }.sortedBy { it.id }

        val statuses = LinkedHashMap<String, Status>()
        val valid = LinkedHashMap<String, Pair<PluginManifest, List<Contribution>>>()

        for (record in records) {
            if (!record.enabled) {
                statuses[record.id] = Status.Disabled
                continue
            }
            try {
                val text = store.readVerified(record)
                val manifest = PluginParser.parse(text)
                if (manifest.id != record.id || manifest.version != record.version)
                    throw PluginException("installed document does not match the registry record")
                if (manifest.minCoreVersion > coreVersion)
                    throw PluginException("requires Termux ${manifest.minCoreVersion} or newer")
                checkDependencies(record, manifest, records)
                val values = PluginSettings.effectiveValues(manifest, record.settings)
                val resolved = manifest.contributions.map { PluginSettings.resolveContribution(it, values) }
                valid[record.id] = manifest to resolved
            } catch (e: PluginException) {
                statuses[record.id] = Status.Invalid(e.message ?: "invalid plugin")
            } catch (e: Exception) {
                statuses[record.id] = Status.Invalid(e.message ?: "could not be loaded")
            }
        }

        // Dependency cycles among otherwise-valid enabled plugins.
        val cyclic = findCycles(valid.mapValues { (_, pair) -> pair.first })
        for (id in cyclic) {
            valid.remove(id)
            statuses[id] = Status.Invalid("dependency cycle")
        }

        // Exclusive contributions: first valid provider in id order wins; later plugins
        // with the same exclusive type contribute nothing this generation.
        var themeProvider: String? = null
        var extraKeysProvider: String? = null
        var themeProperties: Properties? = null
        var extraKeysJson: String? = null
        var extraKeysStyle: String? = null
        val presets = ArrayList<SessionPreset>()
        val terminalSettings = LinkedHashMap<String, String>()

        for ((id, pair) in valid) {
            val (_, resolved) = pair
            val theme = resolved.filterIsInstance<Contribution.Theme>().firstOrNull()
            val extraKeys = resolved.filterIsInstance<Contribution.ExtraKeys>().firstOrNull()
            if (theme != null && themeProvider != null) {
                statuses[id] = Status.Overridden(themeProvider)
                continue
            }
            if (extraKeys != null && extraKeysProvider != null) {
                statuses[id] = Status.Overridden(extraKeysProvider)
                continue
            }
            statuses[id] = Status.Active
            if (theme != null) {
                themeProvider = id
                themeProperties = Properties().apply {
                    for ((key, value) in theme.colors) setProperty(key, value)
                }
            }
            if (extraKeys != null) {
                extraKeysProvider = id
                extraKeysJson = PluginSettings.extraKeysToJsonString(extraKeys)
                extraKeysStyle = extraKeys.style
            }
            for (preset in resolved.filterIsInstance<Contribution.SessionPreset>()) {
                resolvePreset(preset)?.let { presets.add(it) }
            }
            resolved.filterIsInstance<Contribution.TerminalSettings>().firstOrNull()?.let {
                terminalSettings.putAll(it.values)
            }
        }

        state = ActiveState(
            state.generation + 1, themeProperties, themeProvider,
            extraKeysJson, extraKeysStyle, extraKeysProvider,
            presets, terminalSettings, statuses
        )
        for (listener in listeners) {
            try { listener.run() } catch (_: Exception) {}
        }
    }

    private fun checkDependencies(record: PluginRecord, manifest: PluginManifest, records: List<PluginRecord>) {
        for (dependency in manifest.dependencies) {
            val depRecord = records.firstOrNull { it.id == dependency.id }
                ?: throw PluginException("missing dependency ${dependency.id}")
            if (!depRecord.enabled)
                throw PluginException("dependency ${dependency.id} is disabled")
            if (depRecord.version < dependency.minVersion)
                throw PluginException("dependency ${dependency.id} needs version ${dependency.minVersion} or newer")
        }
    }

    /** DFS cycle detection over the dependency graph of the given manifests. */
    private fun findCycles(manifests: Map<String, PluginManifest>): Set<String> {
        val color = HashMap<String, Int>() // 0=unvisited, 1=visiting, 2=done
        val inCycle = HashSet<String>()
        val stack = ArrayList<String>()
        fun visit(id: String) {
            color[id] = 1
            stack.add(id)
            val manifest = manifests[id] ?: run { color[id] = 2; stack.removeAt(stack.size - 1); return }
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
            stack.removeAt(stack.size - 1)
            color[id] = 2
        }
        for (id in manifests.keys) if ((color[id] ?: 0) == 0) visit(id)
        return inCycle
    }

    /** Canonicalizes a preset directory under $HOME; returns null when it does not exist. */
    private fun resolvePreset(preset: Contribution.SessionPreset): SessionPreset? {
        return try {
            val home = File(homeDir).canonicalFile
            val target = if (preset.workingDirectory == null) home
            else File(home, preset.workingDirectory).canonicalFile
            if (target != home && !target.path.startsWith(home.path + File.separator)) return null
            if (!target.isDirectory) return null
            SessionPreset(preset.name, target.path)
        } catch (e: Exception) {
            null
        }
    }
}
