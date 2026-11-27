package com.termux.app.plugins

/** Raised for any invalid plugin document, artifact, or operation. Messages are user-safe. */
class PluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Plugin API v2 constants.
 *
 * v2 replaces the closed v1 model (one fixed type per plugin, no settings) with an open
 * contribution model: a plugin carries 1..16 typed contributions plus a declarative settings
 * schema. Everything stays declarative data — no plugin code is ever loaded or executed.
 */
object ZenApi {
    const val API_VERSION = 2
    const val SCHEMA_VERSION = 2

    // Artifact and document bounds.
    const val MAX_ARCHIVE_BYTES = 512 * 1024
    const val MAX_PLUGIN_JSON_BYTES = 256 * 1024
    const val MAX_INDEX_BYTES = 256 * 1024
    const val MAX_SIGNATURE_BYTES = 8 * 1024
    const val MAX_CATALOG_ENTRIES = 500

    // Manifest bounds.
    const val MAX_CONTRIBUTIONS = 16
    const val MAX_SETTINGS = 32
    const val MAX_DEPENDENCIES = 16
    const val MAX_PRESETS_PER_PLUGIN = 16

    // Field bounds.
    const val ID_MIN_LENGTH = 3
    const val ID_MAX_LENGTH = 128
    const val NAME_MAX_LENGTH = 64
    const val DESCRIPTION_MAX_LENGTH = 512
    const val AUTHOR_MAX_LENGTH = 128

    /** Lowercase reverse-DNS, path-safe: no '/', '..', or separators can appear. */
    val ID_REGEX = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*)+$")

    /** Setting ids are simple lowercase identifiers. */
    val SETTING_ID_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** References a plugin setting inside a contribution string value. */
    // The closing brace must be escaped: Android's ICU regex rejects a bare `}` while
    // java.util.regex accepts it, which would fail this object's initializer at startup.
    val SETTING_TOKEN_REGEX = Regex("\\$\\{settings\\.([a-z][a-z0-9_]*)\\}")

    /** Color forms accepted by the host TerminalColors parser. */
    val COLOR_REGEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{9}|[0-9a-fA-F]{12})$")

    /** Canonical form for settings of type color. */
    val SETTING_COLOR_REGEX = Regex("^#[0-9a-fA-F]{6}$")

    val THEME_COLOR_KEYS: Set<String> =
        setOf("foreground", "background", "cursor") + (0..15).map { "color$it" }.toSet()

    val EXTRA_KEYS_STYLES = setOf("default", "arrows-only", "arrows-all", "all", "none")

    // Extra-keys shape bounds.
    const val MAX_KEY_ROWS = 6
    const val MAX_KEY_CELLS_PER_ROW = 12
    const val MAX_KEY_CELLS_TOTAL = 64
    const val KEY_NAME_MAX_LENGTH = 32
    const val MACRO_MAX_LENGTH = 512
    const val DISPLAY_MAX_LENGTH = 32

    /**
     * Older plugin macros used a literal backslash-n at the end to mean "press Enter".
     * Control characters are rejected in documents, so this two-character suffix is the
     * only legacy form; parsing migrates it to the explicit `sendOnPress` flag.
     */
    const val LEGACY_MACRO_SEND_SUFFIX = "\\n"

    // Session preset bounds.
    const val PRESET_NAME_MAX_LENGTH = 48
    const val PRESET_DIR_MAX_LENGTH = 256

    // Contribution type names (wire format).
    const val TYPE_THEME = "theme"
    const val TYPE_EXTRA_KEYS = "extra_keys"
    const val TYPE_SESSION_PRESET = "session_preset"
    const val TYPE_TERMINAL_SETTINGS = "terminal_settings"

    val CONTRIBUTION_TYPES = setOf(TYPE_THEME, TYPE_EXTRA_KEYS, TYPE_SESSION_PRESET, TYPE_TERMINAL_SETTINGS)

    /** Types where only one enabled provider may be active at a time. */
    val EXCLUSIVE_TYPES = setOf(TYPE_THEME, TYPE_EXTRA_KEYS)

    // Plugin sources.
    const val SOURCE_BUNDLED = "bundled"
    const val SOURCE_REPOSITORY = "repository"
    const val SOURCE_LOCAL = "local"
    const val SOURCE_IMPORT = "import"
    val SOURCES = setOf(SOURCE_BUNDLED, SOURCE_REPOSITORY, SOURCE_LOCAL, SOURCE_IMPORT)

    /** Sources whose plugins the user may edit in place (user-owned content). */
    val EDITABLE_SOURCES = setOf(SOURCE_LOCAL, SOURCE_IMPORT)

    fun isValidId(id: String): Boolean =
        id.length in ID_MIN_LENGTH..ID_MAX_LENGTH && ID_REGEX.matches(id)

    fun hasControlChars(value: String): Boolean = value.any { it < ' ' || it == '\u007f' }

    /** True when [value] is a literal color or a well-formed settings token. */
    fun isColorOrToken(value: String, settingIds: Set<String>): Boolean {
        if (COLOR_REGEX.matches(value)) return true
        val tokens = SETTING_TOKEN_REGEX.findAll(value).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return false
        if (tokens.any { it !in settingIds }) return false
        // The whole value must be covered by tokens/literal '#' hex parts; simplest safe rule:
        // a token may appear anywhere, but the resolved result is re-validated at apply time.
        return true
    }
}
