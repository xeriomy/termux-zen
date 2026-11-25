package com.termux.app.plugins.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.termux.app.plugins.ChoiceOption
import com.termux.app.plugins.Contribution
import com.termux.app.plugins.KeyCell
import com.termux.app.plugins.PluginDependency
import com.termux.app.plugins.PluginException
import com.termux.app.plugins.PluginManifest
import com.termux.app.plugins.PluginParser
import com.termux.app.plugins.PluginSettings
import com.termux.app.plugins.Semver
import com.termux.app.plugins.SettingSpec
import com.termux.app.plugins.SettingType
import com.termux.app.plugins.ZenApi

/** Mutable extra-keys cell for the editor. */
class EditableCell(
    key: String? = null,
    macro: String? = null,
    display: String? = null,
    popup: EditableCell? = null
) {
    var useMacro by mutableStateOf(macro != null)
    var key by mutableStateOf(key ?: "")
    var macro by mutableStateOf(macro ?: "")
    var display by mutableStateOf(display ?: "")
    var popup by mutableStateOf(popup)

    fun toKeyCell(): KeyCell = KeyCell(
        key = if (!useMacro) key.trim().ifEmpty { null } else null,
        macro = if (useMacro) macro.ifEmpty { null } else null,
        display = display.trim().ifEmpty { null },
        popup = popup?.toKeyCell()
    )

    companion object {
        fun from(cell: KeyCell): EditableCell =
            EditableCell(cell.key, cell.macro, cell.display, cell.popup?.let { from(it) })
    }
}

/** Mutable contribution being edited. */
sealed class EditableContribution {
    abstract val type: String

    class Theme(colors: Map<String, String> = emptyMap()) : EditableContribution() {
        override val type = ZenApi.TYPE_THEME
        val colors = mutableStateMapOf<String, String>().apply {
            put("foreground", "#e6e6e6")
            put("background", "#101820")
            putAll(colors)
        }
    }

    class Keys(rows: List<List<KeyCell>> = emptyList(), style: String? = null) : EditableContribution() {
        override val type = ZenApi.TYPE_EXTRA_KEYS
        val rows: SnapshotStateList<SnapshotStateList<EditableCell>> = mutableStateListOf()
        var style by mutableStateOf(style ?: "default")

        init {
            if (rows.isEmpty()) {
                this.rows.add(mutableStateListOf(EditableCell(key = "ESC"), EditableCell(key = "TAB")))
            } else {
                for (row in rows) this.rows.add(mutableStateListOf<EditableCell>().apply {
                    addAll(row.map { EditableCell.from(it) })
                })
            }
        }
    }

    class Preset(name: String = "", dir: String? = null) : EditableContribution() {
        override val type = ZenApi.TYPE_SESSION_PRESET
        var name by mutableStateOf(name)
        var dir by mutableStateOf(dir ?: "")
    }

    class Terminal(values: Map<String, String> = emptyMap()) : EditableContribution() {
        override val type = ZenApi.TYPE_TERMINAL_SETTINGS
        val values = mutableStateMapOf<String, String>().apply { putAll(values) }
    }

    fun toContribution(): Contribution = when (this) {
        is Theme -> Contribution.Theme(LinkedHashMap(colors.filterValues { it.isNotBlank() }))
        is Keys -> Contribution.ExtraKeys(
            rows.map { row -> row.map { it.toKeyCell() } },
            style.ifBlank { null }
        )
        is Preset -> Contribution.SessionPreset(name.trim(), dir.trim().ifEmpty { null })
        is Terminal -> Contribution.TerminalSettings(LinkedHashMap(values))
    }

    companion object {
        fun from(contribution: Contribution): EditableContribution = when (contribution) {
            is Contribution.Theme -> Theme(contribution.colors)
            is Contribution.ExtraKeys -> Keys(contribution.rows, contribution.style)
            is Contribution.SessionPreset -> Preset(contribution.name, contribution.workingDirectory)
            is Contribution.TerminalSettings -> Terminal(contribution.values)
        }
    }
}

/**
 * The whole plugin being edited: metadata, contributions, settings schema and (read-only)
 * dependencies. [toManifest] validates by round-tripping through the strict parser, so the
 * editor can never save a document the host would reject.
 */
class EditorDraft(
    val originalId: String?,
    id: String,
    name: String,
    version: String,
    author: String,
    description: String,
    contributions: List<EditableContribution>,
    settings: List<SettingSpec>,
    val dependencies: List<PluginDependency>
) {
    var id by mutableStateOf(id)
    var name by mutableStateOf(name)
    var version by mutableStateOf(version)
    var author by mutableStateOf(author)
    var description by mutableStateOf(description)
    val contributions: SnapshotStateList<EditableContribution> = mutableStateListOf<EditableContribution>().apply {
        addAll(contributions)
    }
    val settings: SnapshotStateList<SettingSpec> = mutableStateListOf<SettingSpec>().apply { addAll(settings) }

    var jsonMode by mutableStateOf(false)
    var jsonText by mutableStateOf("")
    var jsonError by mutableStateOf<String?>(null)

    /**
     * Plugins loaded from an existing artifact (including duplicates) keep the author's
     * identity, version and settings schema. Only the contributions are editable in-app;
     * those fields belong in the plugin's own JSON, authored by its developer.
     */
    val schemaLocked: Boolean get() = originalId != null

    fun enterJsonMode() {
        if (schemaLocked) return
        try {
            jsonText = PluginParser.toJson(buildManifest())
            jsonError = null
            jsonMode = true
        } catch (e: Exception) {
            jsonError = e.message
        }
    }

    fun exitJsonMode(): Boolean {
        return try {
            val manifest = PluginParser.parse(jsonText)
            loadFrom(manifest)
            jsonMode = false
            jsonError = null
            true
        } catch (e: Exception) {
            jsonError = e.message
            false
        }
    }

    fun validateJson(): Boolean {
        return try {
            PluginParser.parse(jsonText)
            jsonError = null
            true
        } catch (e: Exception) {
            jsonError = e.message
            false
        }
    }

    private fun loadFrom(manifest: PluginManifest) {
        id = manifest.id
        name = manifest.name
        version = manifest.version.raw
        author = manifest.author ?: ""
        description = manifest.description ?: ""
        contributions.clear()
        contributions.addAll(manifest.contributions.map { EditableContribution.from(it) })
        settings.clear()
        settings.addAll(manifest.settings)
    }

    private fun buildManifest(): PluginManifest {
        if (contributions.isEmpty()) throw PluginException(ZenStrings.EDITOR_NO_CONTRIBUTIONS)
        val parsedVersion = Semver.parseOrThrow(version.trim(), "version")
        return PluginManifest(
            id = id.trim(),
            name = name.trim(),
            version = parsedVersion,
            minCoreVersion = Semver.parse("0.0.0")!!,
            author = author.trim().ifEmpty { null },
            description = description.trim().ifEmpty { null },
            settings = settings.toList(),
            contributions = contributions.map { it.toContribution() },
            dependencies = dependencies,
            rawJson = ""
        )
    }

    /** Builds and strictly validates the document; throws [PluginException] when invalid. */
    fun toManifest(): PluginManifest {
        // Raw JSON authoring is only for new plugins; a locked draft cannot bypass the
        // form restrictions by switching modes.
        if (jsonMode && !schemaLocked) return PluginParser.parse(jsonText)
        val manifest = buildManifest()
        return PluginParser.parse(PluginParser.toJson(manifest))
    }

    companion object {
        fun blank(): EditorDraft = EditorDraft(
            originalId = null,
            id = "com.example.myplugin",
            name = "My plugin",
            version = "1.0.0",
            author = "",
            description = "",
            contributions = listOf(EditableContribution.Theme()),
            settings = emptyList(),
            dependencies = emptyList()
        )

        fun from(manifest: PluginManifest): EditorDraft = EditorDraft(
            originalId = manifest.id,
            id = manifest.id,
            name = manifest.name,
            version = manifest.version.raw,
            author = manifest.author ?: "",
            description = manifest.description ?: "",
            contributions = manifest.contributions.map { EditableContribution.from(it) },
            settings = manifest.settings,
            dependencies = manifest.dependencies
        )
    }
}
