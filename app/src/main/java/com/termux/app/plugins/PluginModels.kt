package com.termux.app.plugins

/**
 * Immutable v2 plugin model. A plugin is one JSON document ([PluginManifest.rawJson]) with
 * metadata, a declarative settings schema, 1..16 typed contributions and optional dependencies.
 * Nothing here executes; contributions are data the host applies through fixed extension points.
 */

data class PluginDependency(val id: String, val minVersion: Semver) {
    override fun toString(): String = "$id@$minVersion"
}

data class ChoiceOption(val value: String, val label: String)

/** The five setting field types the host knows how to render and validate. */
sealed class SettingType {
    object Toggle : SettingType()
    data class Number(val min: Int, val max: Int) : SettingType()
    data class Text(val maxLength: Int) : SettingType()
    data class Choice(val options: List<ChoiceOption>) : SettingType()
    object Color : SettingType()
}

/**
 * One user-configurable field declared by a plugin. The host renders these generically;
 * contribution string values can reference them with `${settings.<id>}` tokens.
 */
data class SettingSpec(
    val id: String,
    val label: String,
    val description: String?,
    val type: SettingType,
    val defaultValue: String
)

/**
 * One button in an extra-keys row. Exactly one of [key] or [macro] is set.
 * [sendOnPress] applies to macros only: when true the host sends Enter after the tokens,
 * so a command runs immediately; when false the macro only types its text.
 */
data class KeyCell(
    val key: String?,
    val macro: String?,
    val display: String?,
    val popup: KeyCell?,
    val sendOnPress: Boolean = false
)

/** A typed, declarative contribution to a host extension point. */
sealed class Contribution {
    abstract val type: String

    /** Terminal palette; applied only when the user has no colors.properties. */
    data class Theme(val colors: Map<String, String>) : Contribution() {
        override val type: String get() = ZenApi.TYPE_THEME
    }

    /** Extra-keys bar; applied only when the user has no explicit extra-keys property. */
    data class ExtraKeys(val rows: List<List<KeyCell>>, val style: String?) : Contribution() {
        override val type: String get() = ZenApi.TYPE_EXTRA_KEYS
    }

    /** A new-session menu entry for a directory under $HOME. Never runs anything by itself. */
    data class SessionPreset(val name: String, val workingDirectory: String?) : Contribution() {
        override val type: String get() = ZenApi.TYPE_SESSION_PRESET
    }

    /** Safe terminal behavior values; applied only for keys the user has not set. */
    data class TerminalSettings(val values: Map<String, String>) : Contribution() {
        override val type: String get() = ZenApi.TYPE_TERMINAL_SETTINGS
    }
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: Semver,
    val minCoreVersion: Semver,
    val author: String?,
    val description: String?,
    val settings: List<SettingSpec>,
    val contributions: List<Contribution>,
    val dependencies: List<PluginDependency>,
    /** The exact validated document text, stored and hash-checked as installed. */
    val rawJson: String
) {
    val settingIds: Set<String> get() = settings.map { it.id }.toSet()

    val contributionTypes: List<String> get() = contributions.map { it.type }.distinct()

    val hasExclusiveContribution: Boolean
        get() = contributions.any { it.type in ZenApi.EXCLUSIVE_TYPES }

    fun theme(): Contribution.Theme? = contributions.filterIsInstance<Contribution.Theme>().firstOrNull()
    fun extraKeys(): Contribution.ExtraKeys? = contributions.filterIsInstance<Contribution.ExtraKeys>().firstOrNull()
    fun terminalSettings(): Contribution.TerminalSettings? =
        contributions.filterIsInstance<Contribution.TerminalSettings>().firstOrNull()
    fun sessionPresets(): List<Contribution.SessionPreset> =
        contributions.filterIsInstance<Contribution.SessionPreset>()
}
