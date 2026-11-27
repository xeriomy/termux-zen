package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses and serializes the v2 plugin document (`plugin.json`). Parsing is strict: unknown
 * fields are rejected at every level, types must match exactly, and every bound is enforced
 * before a [PluginManifest] is produced. Serialization is used by the in-app editor and export.
 */
object PluginParser {

    private val MANIFEST_KEYS = setOf(
        "schemaVersion", "id", "name", "version", "apiVersion", "minCoreVersion",
        "author", "description", "settings", "contributions", "dependencies"
    )

    fun parse(text: String): PluginManifest {
        if (text.toByteArray(Charsets.UTF_8).size > ZenApi.MAX_PLUGIN_JSON_BYTES)
            throw PluginException("plugin.json exceeds ${ZenApi.MAX_PLUGIN_JSON_BYTES} bytes")
        val obj = PluginJson.parseObject(text, "plugin.json")
        PluginJson.rejectUnknownKeys(obj, MANIFEST_KEYS, "manifest")

        val schemaVersion = PluginJson.requireInt(obj, "schemaVersion", "manifest")
        if (schemaVersion != ZenApi.SCHEMA_VERSION)
            throw PluginException("unsupported schemaVersion $schemaVersion (expected ${ZenApi.SCHEMA_VERSION})")
        val apiVersion = PluginJson.requireInt(obj, "apiVersion", "manifest")
        if (apiVersion != ZenApi.API_VERSION)
            throw PluginException("unsupported apiVersion $apiVersion (this app supports API ${ZenApi.API_VERSION})")

        val id = PluginJson.requireString(obj, "id", "manifest", ZenApi.ID_MAX_LENGTH)
        if (!ZenApi.isValidId(id)) throw PluginException("manifest id \"$id\" is not a valid lowercase reverse-DNS id")
        val name = PluginJson.requireString(obj, "name", "manifest", ZenApi.NAME_MAX_LENGTH)
        val version = Semver.parseOrThrow(
            PluginJson.requireString(obj, "version", "manifest", 64), "manifest version")
        val minCoreVersion = Semver.parseOrThrow(
            PluginJson.optionalString(obj, "minCoreVersion", "manifest", 64) ?: "0.0.0", "minCoreVersion")
        val author = PluginJson.optionalString(obj, "author", "manifest", ZenApi.AUTHOR_MAX_LENGTH)
        val description = PluginJson.optionalString(obj, "description", "manifest", ZenApi.DESCRIPTION_MAX_LENGTH)

        val settings = parseSettings(PluginJson.optionalArray(obj, "settings", "manifest"))
        val settingIds = settings.map { it.id }.toSet()
        val contributions = parseContributions(
            PluginJson.requireArray(obj, "contributions", "manifest"), settingIds)
        val dependencies = parseDependencies(
            PluginJson.optionalArray(obj, "dependencies", "manifest"), id)

        return PluginManifest(id, name, version, minCoreVersion, author, description,
            settings, contributions, dependencies, text)
    }

    // ---------------------------------------------------------------- settings

    private val SETTING_KEYS = setOf("id", "label", "description", "type", "default", "min", "max", "maxLength", "options")

    private fun parseSettings(array: JSONArray?): List<SettingSpec> {
        if (array == null) return emptyList()
        if (array.length() > ZenApi.MAX_SETTINGS)
            throw PluginException("settings exceed ${ZenApi.MAX_SETTINGS} entries")
        val result = ArrayList<SettingSpec>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = PluginJson.objectAt(array, i, "settings")
            PluginJson.rejectUnknownKeys(obj, SETTING_KEYS, "settings[$i]")
            val id = PluginJson.requireString(obj, "id", "settings[$i]", 32)
            if (!ZenApi.SETTING_ID_REGEX.matches(id))
                throw PluginException("settings[$i] id \"$id\" must match ${ZenApi.SETTING_ID_REGEX.pattern}")
            if (!seen.add(id)) throw PluginException("duplicate setting id \"$id\"")
            val label = PluginJson.requireString(obj, "label", "settings[$i]", ZenApi.NAME_MAX_LENGTH)
            val description = PluginJson.optionalString(obj, "description", "settings[$i]", 256)
            val type = PluginJson.requireString(obj, "type", "settings[$i]", 16)
            val default = PluginJson.requireString(obj, "default", "settings[$i]", 512)

            val specType: SettingType = when (type) {
                "toggle" -> {
                    if (default != "true" && default != "false")
                        throw PluginException("settings[$i] toggle default must be \"true\" or \"false\"")
                    SettingType.Toggle
                }
                "number" -> {
                    val min = PluginJson.optionalInt(obj, "min", "settings[$i]") ?: 0
                    val max = PluginJson.optionalInt(obj, "max", "settings[$i]") ?: 1_000_000
                    if (min > max) throw PluginException("settings[$i] min exceeds max")
                    val value = default.toIntOrNull()
                        ?: throw PluginException("settings[$i] number default must be an integer string")
                    if (value < min || value > max)
                        throw PluginException("settings[$i] number default is outside [$min, $max]")
                    SettingType.Number(min, max)
                }
                "text" -> {
                    val maxLength = PluginJson.optionalInt(obj, "maxLength", "settings[$i]") ?: 128
                    if (maxLength < 1 || maxLength > 512)
                        throw PluginException("settings[$i] maxLength must be in [1, 512]")
                    if (default.length > maxLength)
                        throw PluginException("settings[$i] text default exceeds maxLength")
                    if (ZenApi.hasControlChars(default))
                        throw PluginException("settings[$i] text default contains control characters")
                    SettingType.Text(maxLength)
                }
                "choice" -> {
                    val optionsArray = PluginJson.requireArray(obj, "options", "settings[$i]")
                    if (optionsArray.length() < 2 || optionsArray.length() > 16)
                        throw PluginException("settings[$i] choice needs 2..16 options")
                    val options = ArrayList<ChoiceOption>(optionsArray.length())
                    val values = HashSet<String>()
                    for (j in 0 until optionsArray.length()) {
                        val optionObj = PluginJson.objectAt(optionsArray, j, "settings[$i].options")
                        PluginJson.rejectUnknownKeys(optionObj, setOf("value", "label"), "settings[$i].options[$j]")
                        val value = PluginJson.requireString(optionObj, "value", "settings[$i].options[$j]", 64)
                        val optionLabel = PluginJson.requireString(optionObj, "label", "settings[$i].options[$j]", ZenApi.NAME_MAX_LENGTH)
                        if (!values.add(value))
                            throw PluginException("settings[$i] duplicate choice value \"$value\"")
                        options.add(ChoiceOption(value, optionLabel))
                    }
                    if (default !in values)
                        throw PluginException("settings[$i] choice default must be one of the option values")
                    SettingType.Choice(options)
                }
                "color" -> {
                    if (!ZenApi.SETTING_COLOR_REGEX.matches(default))
                        throw PluginException("settings[$i] color default must be #RRGGBB")
                    SettingType.Color
                }
                else -> throw PluginException("settings[$i] has unknown type \"$type\"")
            }
            result.add(SettingSpec(id, label, description, specType, default))
        }
        return result
    }

    // ---------------------------------------------------------------- contributions

    private fun parseContributions(array: JSONArray, settingIds: Set<String>): List<Contribution> {
        if (array.length() < 1 || array.length() > ZenApi.MAX_CONTRIBUTIONS)
            throw PluginException("contributions must have 1..${ZenApi.MAX_CONTRIBUTIONS} entries")
        val result = ArrayList<Contribution>(array.length())
        var themes = 0; var extraKeys = 0; var terminalSettings = 0; var presets = 0
        for (i in 0 until array.length()) {
            val obj = PluginJson.objectAt(array, i, "contributions")
            val type = PluginJson.requireString(obj, "type", "contributions[$i]", 32)
            val contribution: Contribution = when (type) {
                ZenApi.TYPE_THEME -> {
                    if (++themes > 1) throw PluginException("a plugin may declare at most one theme contribution")
                    parseTheme(obj, i, settingIds)
                }
                ZenApi.TYPE_EXTRA_KEYS -> {
                    if (++extraKeys > 1) throw PluginException("a plugin may declare at most one extra_keys contribution")
                    parseExtraKeys(obj, i, settingIds)
                }
                ZenApi.TYPE_SESSION_PRESET -> {
                    if (++presets > ZenApi.MAX_PRESETS_PER_PLUGIN)
                        throw PluginException("too many session_preset contributions (max ${ZenApi.MAX_PRESETS_PER_PLUGIN})")
                    parsePreset(obj, i, settingIds)
                }
                ZenApi.TYPE_TERMINAL_SETTINGS -> {
                    if (++terminalSettings > 1)
                        throw PluginException("a plugin may declare at most one terminal_settings contribution")
                    parseTerminalSettings(obj, i, settingIds)
                }
                else -> throw PluginException("contributions[$i] has unknown type \"$type\"")
            }
            result.add(contribution)
        }
        return result
    }

    private fun parseTheme(obj: JSONObject, index: Int, settingIds: Set<String>): Contribution.Theme {
        val what = "contributions[$index](theme)"
        PluginJson.rejectUnknownKeys(obj, setOf("type", "colors"), what)
        val colorsObj = PluginJson.requireObject(obj, "colors", what)
        val keys = colorsObj.keys()
        val colors = LinkedHashMap<String, String>()
        while (keys.hasNext()) {
            val key = keys.next() as String
            if (key !in ZenApi.THEME_COLOR_KEYS) throw PluginException("$what has unknown color key \"$key\"")
            val value = colorsObj.opt(key)
            if (value !is String || value.isEmpty() || value.length > 64)
                throw PluginException("$what color \"$key\" must be a non-empty string of at most 64 characters")
            if (!ZenApi.isColorOrToken(value, settingIds))
                throw PluginException("$what color \"$key\" is not a valid color or settings reference")
            colors[key] = value
        }
        if ("foreground" !in colors || "background" !in colors)
            throw PluginException("$what requires foreground and background colors")
        return Contribution.Theme(colors)
    }

    private fun parseExtraKeys(obj: JSONObject, index: Int, settingIds: Set<String>): Contribution.ExtraKeys {
        val what = "contributions[$index](extra_keys)"
        PluginJson.rejectUnknownKeys(obj, setOf("type", "style", "keys"), what)
        val style = PluginJson.optionalString(obj, "style", what, 64)
        if (style != null && style !in ZenApi.EXTRA_KEYS_STYLES) {
            if (!style.contains("\${settings.") || !PluginSettings.tokensValid(style, settingIds))
                throw PluginException("$what style must be one of ${ZenApi.EXTRA_KEYS_STYLES} or a settings reference")
        }
        val rowsArray = PluginJson.requireArray(obj, "keys", what)
        if (rowsArray.length() < 1 || rowsArray.length() > ZenApi.MAX_KEY_ROWS)
            throw PluginException("$what keys must have 1..${ZenApi.MAX_KEY_ROWS} rows")
        val rows = ArrayList<List<KeyCell>>(rowsArray.length())
        var total = 0
        for (r in 0 until rowsArray.length()) {
            val rowArray = PluginJson.arrayAt(rowsArray, r, "$what keys")
            if (rowArray.length() < 1 || rowArray.length() > ZenApi.MAX_KEY_CELLS_PER_ROW)
                throw PluginException("$what row $r must have 1..${ZenApi.MAX_KEY_CELLS_PER_ROW} keys")
            val row = ArrayList<KeyCell>(rowArray.length())
            for (c in 0 until rowArray.length()) {
                total++
                if (total > ZenApi.MAX_KEY_CELLS_TOTAL)
                    throw PluginException("$what exceeds ${ZenApi.MAX_KEY_CELLS_TOTAL} keys total")
                row.add(parseCell(PluginJson.objectAt(rowArray, c, "$what keys[$r]"), "$what keys[$r][$c]", true, settingIds))
            }
            rows.add(row)
        }
        return Contribution.ExtraKeys(rows, style)
    }

    private fun parseCell(obj: JSONObject, what: String, allowPopup: Boolean, settingIds: Set<String>): KeyCell {
        val commonFields = setOf("key", "macro", "display", "sendOnPress")
        val allowed = if (allowPopup) commonFields + "popup" else commonFields
        PluginJson.rejectUnknownKeys(obj, allowed, what)
        val key = PluginJson.optionalString(obj, "key", what, ZenApi.KEY_NAME_MAX_LENGTH)
        var macro = PluginJson.optionalString(obj, "macro", what, ZenApi.MACRO_MAX_LENGTH)
        var sendOnPress = PluginJson.optionalBool(obj, "sendOnPress", what) ?: false
        if (sendOnPress && macro == null)
            throw PluginException("$what sendOnPress requires a macro")
        // Migration: a literal "\n" suffix was the old way to say "run this command".
        // Strip it and set the flag so the host sends a real Enter instead of typing
        // the two characters backslash and n.
        if (macro != null && macro.endsWith(ZenApi.LEGACY_MACRO_SEND_SUFFIX) &&
            macro.length > ZenApi.LEGACY_MACRO_SEND_SUFFIX.length) {
            macro = macro.removeSuffix(ZenApi.LEGACY_MACRO_SEND_SUFFIX)
            sendOnPress = true
        }
        if ((key == null) == (macro == null))
            throw PluginException("$what must declare exactly one of \"key\" or \"macro\"")
        val display = PluginJson.optionalString(obj, "display", what, ZenApi.DISPLAY_MAX_LENGTH)
        for (value in listOfNotNull(key, macro, display)) {
            if (!PluginSettings.tokensValid(value, settingIds))
                throw PluginException("$what references an undeclared setting")
        }
        val popup = if (allowPopup) {
            PluginJson.optionalObject(obj, "popup", what)?.let { parseCell(it, "$what.popup", false, settingIds) }
        } else null
        return KeyCell(key, macro, display, popup, sendOnPress)
    }

    private fun parsePreset(obj: JSONObject, index: Int, settingIds: Set<String>): Contribution.SessionPreset {
        val what = "contributions[$index](session_preset)"
        PluginJson.rejectUnknownKeys(obj, setOf("type", "name", "workingDirectory"), what)
        val name = PluginJson.requireString(obj, "name", what, ZenApi.PRESET_NAME_MAX_LENGTH)
        val dir = PluginJson.optionalString(obj, "workingDirectory", what, ZenApi.PRESET_DIR_MAX_LENGTH)
        if (!PluginSettings.tokensValid(name, settingIds))
            throw PluginException("$what name references an undeclared setting")
        if (dir != null) {
            if (!PluginSettings.tokensValid(dir, settingIds))
                throw PluginException("$what workingDirectory references an undeclared setting")
            // A literal path must be valid now; a token path is re-validated after resolution.
            if (!dir.contains("\${settings.") && !isLiteralRelativeDir(dir))
                throw PluginException("$what workingDirectory must be a relative path inside \$HOME")
        }
        return Contribution.SessionPreset(name, dir)
    }

    /** Relative-path grammar for preset directories: no absolutes, traversal, or empty segments. */
    fun isLiteralRelativeDir(path: String): Boolean {
        if (path.isEmpty() || path.length > ZenApi.PRESET_DIR_MAX_LENGTH) return false
        if (path.startsWith("/") || path.startsWith("~") || path.contains("\\")) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun parseTerminalSettings(obj: JSONObject, index: Int, settingIds: Set<String>): Contribution.TerminalSettings {
        val what = "contributions[$index](terminal_settings)"
        PluginJson.rejectUnknownKeys(obj, setOf("type", "values"), what)
        val valuesObj = PluginJson.requireObject(obj, "values", what)
        if (valuesObj.length() == 0) throw PluginException("$what values must not be empty")
        val values = LinkedHashMap<String, String>()
        val keys = valuesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            if (key !in TerminalSettingsSchema.KEYS)
                throw PluginException("$what has unsupported key \"$key\" (allowed: ${TerminalSettingsSchema.KEYS})")
            val value = valuesObj.opt(key)
            if (value !is String || value.isEmpty() || value.length > 64)
                throw PluginException("$what value for \"$key\" must be a non-empty string of at most 64 characters")
            val isToken = value.contains("\${settings.")
            if (isToken && !PluginSettings.tokensValid(value, settingIds))
                throw PluginException("$what value for \"$key\" references an undeclared setting")
            if (!TerminalSettingsSchema.isValidDocumentValue(key, value, settingIds))
                throw PluginException("$what value \"$value\" is invalid for \"$key\"")
            values[key] = value
        }
        return Contribution.TerminalSettings(values)
    }

    // ---------------------------------------------------------------- dependencies

    private fun parseDependencies(array: JSONArray?, selfId: String): List<PluginDependency> {
        if (array == null) return emptyList()
        if (array.length() > ZenApi.MAX_DEPENDENCIES)
            throw PluginException("dependencies exceed ${ZenApi.MAX_DEPENDENCIES} entries")
        val result = ArrayList<PluginDependency>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val entry = PluginJson.stringAt(array, i, "dependencies", 200)
            val at = entry.indexOf('@')
            if (at <= 0 || at == entry.length - 1)
                throw PluginException("dependencies[$i] must be \"id@minVersion\"")
            val id = entry.substring(0, at)
            val minVersion = Semver.parseOrThrow(entry.substring(at + 1), "dependencies[$i] version")
            if (!ZenApi.isValidId(id)) throw PluginException("dependencies[$i] id \"$id\" is invalid")
            if (id == selfId) throw PluginException("a plugin cannot depend on itself")
            if (!seen.add(id)) throw PluginException("duplicate dependency id \"$id\"")
            result.add(PluginDependency(id, minVersion))
        }
        return result
    }

    // ---------------------------------------------------------------- serialization

    /** Canonical document text for a model. Used by the editor, duplication and export. */
    fun toJson(manifest: PluginManifest): String {
        val obj = JSONObject()
        obj.put("schemaVersion", ZenApi.SCHEMA_VERSION)
        obj.put("id", manifest.id)
        obj.put("name", manifest.name)
        obj.put("version", manifest.version.raw)
        obj.put("apiVersion", ZenApi.API_VERSION)
        obj.put("minCoreVersion", manifest.minCoreVersion.raw)
        manifest.author?.let { obj.put("author", it) }
        manifest.description?.let { obj.put("description", it) }
        if (manifest.settings.isNotEmpty()) {
            val settings = JSONArray()
            for (spec in manifest.settings) settings.put(settingToJson(spec))
            obj.put("settings", settings)
        }
        val contributions = JSONArray()
        for (contribution in manifest.contributions) contributions.put(contributionToJson(contribution))
        obj.put("contributions", contributions)
        if (manifest.dependencies.isNotEmpty()) {
            val dependencies = JSONArray()
            for (dependency in manifest.dependencies) dependencies.put(dependency.toString())
            obj.put("dependencies", dependencies)
        }
        return obj.toString(2)
    }

    private fun settingToJson(spec: SettingSpec): JSONObject {
        val obj = JSONObject()
        obj.put("id", spec.id)
        obj.put("label", spec.label)
        spec.description?.let { obj.put("description", it) }
        obj.put("default", spec.defaultValue)
        when (val type = spec.type) {
            is SettingType.Toggle -> obj.put("type", "toggle")
            is SettingType.Number -> {
                obj.put("type", "number"); obj.put("min", type.min); obj.put("max", type.max)
            }
            is SettingType.Text -> {
                obj.put("type", "text"); obj.put("maxLength", type.maxLength)
            }
            is SettingType.Choice -> {
                obj.put("type", "choice")
                val options = JSONArray()
                for (option in type.options) {
                    options.put(JSONObject().put("value", option.value).put("label", option.label))
                }
                obj.put("options", options)
            }
            is SettingType.Color -> obj.put("type", "color")
        }
        return obj
    }

    private fun contributionToJson(contribution: Contribution): JSONObject {
        val obj = JSONObject()
        obj.put("type", contribution.type)
        when (contribution) {
            is Contribution.Theme -> {
                val colors = JSONObject()
                for ((key, value) in contribution.colors) colors.put(key, value)
                obj.put("colors", colors)
            }
            is Contribution.ExtraKeys -> {
                contribution.style?.let { obj.put("style", it) }
                val rows = JSONArray()
                for (row in contribution.rows) {
                    val rowArray = JSONArray()
                    for (cell in row) rowArray.put(cellToJson(cell))
                    rows.put(rowArray)
                }
                obj.put("keys", rows)
            }
            is Contribution.SessionPreset -> {
                obj.put("name", contribution.name)
                contribution.workingDirectory?.let { obj.put("workingDirectory", it) }
            }
            is Contribution.TerminalSettings -> {
                val values = JSONObject()
                for ((key, value) in contribution.values) values.put(key, value)
                obj.put("values", values)
            }
        }
        return obj
    }

    private fun cellToJson(cell: KeyCell): JSONObject {
        val obj = JSONObject()
        cell.key?.let { obj.put("key", it) }
        cell.macro?.let { obj.put("macro", it) }
        cell.display?.let { obj.put("display", it) }
        cell.popup?.let { obj.put("popup", cellToJson(it)) }
        if (cell.sendOnPress && cell.macro != null) obj.put("sendOnPress", true)
        return obj
    }
}

/**
 * The whitelisted `terminal_settings` keys and their value grammars. Ranges mirror
 * TermuxPropertyConstants / TerminalView / TerminalEmulator; the host still clamps to its
 * own defaults at apply time, and explicit user properties always win.
 */
object TerminalSettingsSchema {
    const val BELL_CHARACTER = "bell-character"
    const val CURSOR_STYLE = "terminal-cursor-style"
    const val CURSOR_BLINK_RATE = "terminal-cursor-blink-rate"
    const val MARGIN_HORIZONTAL = "terminal-margin-horizontal"
    const val MARGIN_VERTICAL = "terminal-margin-vertical"
    const val TRANSCRIPT_ROWS = "terminal-transcript-rows"
    const val EXTRA_KEYS_TEXT_ALL_CAPS = "extra-keys-text-all-caps"

    val KEYS: Set<String> = setOf(
        BELL_CHARACTER, CURSOR_STYLE, CURSOR_BLINK_RATE,
        MARGIN_HORIZONTAL, MARGIN_VERTICAL, TRANSCRIPT_ROWS, EXTRA_KEYS_TEXT_ALL_CAPS
    )

    /** Human-readable value hints for the editor UI. */
    val VALUE_HINTS: Map<String, String> = mapOf(
        BELL_CHARACTER to "ignore / vibrate / beep",
        CURSOR_STYLE to "block / underline / bar",
        CURSOR_BLINK_RATE to "0 (off) or 100–2000 ms",
        MARGIN_HORIZONTAL to "0–100 dp",
        MARGIN_VERTICAL to "0–100 dp",
        TRANSCRIPT_ROWS to "100–50000 rows",
        EXTRA_KEYS_TEXT_ALL_CAPS to "true / false"
    )

    fun validate(key: String, value: String): Boolean = when (key) {
        BELL_CHARACTER -> value in setOf("ignore", "vibrate", "beep")
        CURSOR_STYLE -> value in setOf("block", "underline", "bar")
        CURSOR_BLINK_RATE -> intInRange(value, 0, 0) || intInRange(value, 100, 2000)
        MARGIN_HORIZONTAL -> intInRange(value, 0, 100)
        MARGIN_VERTICAL -> intInRange(value, 0, 100)
        TRANSCRIPT_ROWS -> intInRange(value, 100, 50000)
        EXTRA_KEYS_TEXT_ALL_CAPS -> value in setOf("true", "false")
        else -> false
    }

    /**
     * Full document-level check shared by the parser and the editor: a literal must satisfy
     * [validate], while a `${settings.id}` token is checked against the declared settings.
     */
    fun isValidDocumentValue(key: String, value: String, settingIds: Set<String>): Boolean {
        if (key !in KEYS || value.isEmpty() || value.length > 64) return false
        if (validate(key, value)) return true
        return value.contains("\${settings.") && PluginSettings.tokensValid(value, settingIds)
    }

    private fun intInRange(value: String, min: Int, max: Int): Boolean {
        val parsed = value.toIntOrNull() ?: return false
        return parsed in min..max
    }
}
