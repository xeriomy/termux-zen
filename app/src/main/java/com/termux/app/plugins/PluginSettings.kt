package com.termux.app.plugins

/**
 * Per-plugin settings: effective-value computation, `${settings.id}` token resolution and
 * post-resolution re-validation. A contribution that fails resolution contributes nothing
 * (fail-closed), exactly like a contribution that fails parsing.
 */
object PluginSettings {

    /** Returns an error message when [value] is invalid for [spec], else null. */
    fun validateValue(spec: SettingSpec, value: String): String? = when (val type = spec.type) {
        is SettingType.Toggle ->
            if (value == "true" || value == "false") null else "must be true or false"
        is SettingType.Number -> {
            val parsed = value.toIntOrNull()
            when {
                parsed == null -> "must be an integer"
                parsed < type.min || parsed > type.max -> "must be in [${type.min}, ${type.max}]"
                else -> null
            }
        }
        is SettingType.Text -> when {
            value.length > type.maxLength -> "exceeds ${type.maxLength} characters"
            ZenApi.hasControlChars(value) -> "contains control characters"
            else -> null
        }
        is SettingType.Choice ->
            if (type.options.any { it.value == value }) null else "must be one of the declared options"
        is SettingType.Color ->
            if (ZenApi.SETTING_COLOR_REGEX.matches(value)) null else "must be #RRGGBB"
    }

    /** Defaults overlaid with valid stored overrides. Unknown or invalid stored values fall back to defaults. */
    fun effectiveValues(manifest: PluginManifest, stored: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (spec in manifest.settings) {
            val override = stored[spec.id]
            result[spec.id] = if (override != null && validateValue(spec, override) == null) override
            else spec.defaultValue
        }
        return result
    }

    /**
     * True when every `${` in [value] starts a well-formed token referencing a declared id.
     * Strings without tokens are valid (they are literals). Malformed or undeclared tokens
     * are rejected so they cannot survive into a resolved contribution.
     */
    fun tokensValid(value: String, settingIds: Set<String>): Boolean {
        var index = 0
        while (true) {
            val start = value.indexOf("\${", index)
            if (start < 0) return true
            val match = ZenApi.SETTING_TOKEN_REGEX.matchAt(value, start) ?: return false
            if (match.groupValues[1] !in settingIds) return false
            index = start + match.value.length
        }
    }

    /** Substitutes every `${settings.id}` token. Throws when an id has no value. */
    fun resolveText(text: String, values: Map<String, String>): String =
        ZenApi.SETTING_TOKEN_REGEX.replace(text) { match ->
            val id = match.groupValues[1]
            values[id] ?: throw PluginException("unknown setting \"$id\" referenced by a contribution")
        }

    /**
     * Resolves all tokens in a contribution and re-validates the resolved literals.
     * Throws [PluginException] when the resolved contribution would be invalid.
     */
    fun resolveContribution(contribution: Contribution, values: Map<String, String>): Contribution =
        when (contribution) {
            is Contribution.Theme -> {
                val resolved = LinkedHashMap<String, String>()
                for ((key, value) in contribution.colors) {
                    val color = resolveText(value, values)
                    if (!ZenApi.COLOR_REGEX.matches(color))
                        throw PluginException("theme color \"$key\" resolved to invalid color \"$color\"")
                    resolved[key] = color
                }
                Contribution.Theme(resolved)
            }
            is Contribution.ExtraKeys -> {
                val style = contribution.style?.let { resolveText(it, values) }
                if (style != null && style !in ZenApi.EXTRA_KEYS_STYLES)
                    throw PluginException("extra_keys style resolved to invalid value \"$style\"")
                Contribution.ExtraKeys(contribution.rows.map { row -> row.map { resolveCell(it, values) } }, style)
            }
            is Contribution.SessionPreset -> {
                val name = resolveText(contribution.name, values)
                if (name.isEmpty() || name.length > ZenApi.PRESET_NAME_MAX_LENGTH || ZenApi.hasControlChars(name))
                    throw PluginException("session preset name resolved to an invalid value")
                val dir = contribution.workingDirectory?.let { resolveText(it, values) }
                if (dir != null && !PluginParser.isLiteralRelativeDir(dir))
                    throw PluginException("session preset workingDirectory resolved to an invalid path")
                Contribution.SessionPreset(name, dir)
            }
            is Contribution.TerminalSettings -> {
                val resolved = LinkedHashMap<String, String>()
                for ((key, value) in contribution.values) {
                    val literal = resolveText(value, values)
                    if (!TerminalSettingsSchema.validate(key, literal))
                        throw PluginException("terminal setting \"$key\" resolved to invalid value \"$literal\"")
                    resolved[key] = literal
                }
                Contribution.TerminalSettings(resolved)
            }
        }

    private fun resolveCell(cell: KeyCell, values: Map<String, String>): KeyCell {
        val key = cell.key?.let { resolveText(it, values) }
        val macro = cell.macro?.let { resolveText(it, values) }
        val display = cell.display?.let { resolveText(it, values) }
        if (key != null && (key.isEmpty() || key.length > ZenApi.KEY_NAME_MAX_LENGTH || ZenApi.hasControlChars(key)))
            throw PluginException("extra key resolved to an invalid key name")
        if (macro != null && (macro.isEmpty() || macro.length > ZenApi.MACRO_MAX_LENGTH || ZenApi.hasControlChars(macro)))
            throw PluginException("extra key macro resolved to an invalid value")
        if (display != null && (display.length > ZenApi.DISPLAY_MAX_LENGTH || ZenApi.hasControlChars(display)))
            throw PluginException("extra key display resolved to an invalid value")
        return KeyCell(key, macro, display, cell.popup?.let { resolveCell(it, values) })
    }

    /** Converts resolved extra-keys rows to the JSON string the host ExtraKeysInfo expects. */
    fun extraKeysToJsonString(extraKeys: Contribution.ExtraKeys): String {
        val rows = org.json.JSONArray()
        for (row in extraKeys.rows) {
            val rowArray = org.json.JSONArray()
            for (cell in row) rowArray.put(cellToJson(cell))
            rows.put(rowArray)
        }
        return rows.toString()
    }

    private fun cellToJson(cell: KeyCell): org.json.JSONObject {
        val obj = org.json.JSONObject()
        cell.key?.let { obj.put("key", it) }
        cell.macro?.let { obj.put("macro", it) }
        cell.display?.let { obj.put("display", it) }
        cell.popup?.let { obj.put("popup", cellToJson(it)) }
        return obj
    }
}
