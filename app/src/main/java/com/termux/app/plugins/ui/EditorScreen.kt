package com.termux.app.plugins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.termux.app.plugins.ChoiceOption
import com.termux.app.plugins.PluginSettings
import com.termux.app.plugins.SettingSpec
import com.termux.app.plugins.SettingType
import com.termux.app.plugins.TerminalSettingsSchema
import com.termux.app.plugins.ZenApi

private val THEME_KEY_ORDER = listOf("foreground", "background", "cursor") + (0..15).map { "color$it" }
private val COMMON_KEYS = listOf(
    "ESC", "TAB", "CTRL", "ALT", "FN", "SHIFT", "ENTER", "BACKSPACE", "SPACE",
    "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END", "PGUP", "PGDN", "INS", "DEL",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "KEYBOARD"
)

@Composable
fun EditorScreen(controller: HubController, draft: EditorDraft) {
    var showAddContribution by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (draft.jsonMode) {
            JsonEditorSection(draft)
        } else {
            MetadataSection(draft)
            SectionHeader(ZenStrings.EDITOR_CONTRIBUTIONS)
            if (draft.contributions.isEmpty()) {
                Text(ZenStrings.EDITOR_NO_CONTRIBUTIONS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            draft.contributions.forEachIndexed { index, contribution ->
                ContributionEditorCard(draft, index, contribution)
            }
            OutlinedButton(onClick = { showAddContribution = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(ZenStrings.EDITOR_ADD_CONTRIBUTION)
            }
            SettingsSchemaSection(draft)
            if (draft.dependencies.isNotEmpty()) {
                SectionHeader(ZenStrings.DEPENDENCIES_TITLE)
                Text(
                    draft.dependencies.joinToString(", ") { it.toString() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { controller.saveDraft(draft) }, modifier = Modifier.weight(1f)) {
                Text(ZenStrings.SAVE)
            }
            OutlinedButton(onClick = {
                if (draft.jsonMode) draft.exitJsonMode() else draft.enterJsonMode()
            }, modifier = Modifier.weight(1f)) {
                Text(if (draft.jsonMode) ZenStrings.EDITOR_BASIC_MODE else ZenStrings.EDITOR_ADVANCED_JSON, maxLines = 1)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (showAddContribution) {
        AddContributionDialog(
            onPick = { type ->
                draft.contributions.add(
                    when (type) {
                        ZenApi.TYPE_THEME -> EditableContribution.Theme()
                        ZenApi.TYPE_EXTRA_KEYS -> EditableContribution.Keys()
                        ZenApi.TYPE_SESSION_PRESET -> EditableContribution.Preset()
                        else -> EditableContribution.Terminal()
                    }
                )
                showAddContribution = false
            },
            onDismiss = { showAddContribution = false }
        )
    }
}

@Composable
private fun MetadataSection(draft: EditorDraft) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft.name, { draft.name = it },
                label = { Text(ZenStrings.EDITOR_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.id, { draft.id = it },
                label = { Text(ZenStrings.EDITOR_ID) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                enabled = draft.originalId == null)
            OutlinedTextField(draft.version, { draft.version = it },
                label = { Text(ZenStrings.EDITOR_VERSION) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.author, { draft.author = it },
                label = { Text(ZenStrings.EDITOR_AUTHOR) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.description, { draft.description = it },
                label = { Text(ZenStrings.EDITOR_DESCRIPTION) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        }
    }
}

@Composable
private fun JsonEditorSection(draft: EditorDraft) {
    OutlinedTextField(
        value = draft.jsonText,
        onValueChange = { draft.jsonText = it; draft.jsonError = null },
        modifier = Modifier.fillMaxWidth(),
        minLines = 14,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    )
    draft.jsonError?.let {
        Text("${ZenStrings.JSON_ERROR_PREFIX}: $it",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = {
        if (draft.validateJson()) draft.jsonError = null
    }) { Text(ZenStrings.EDITOR_VALIDATE) }
    if (draft.jsonError == null && draft.jsonText.isNotEmpty()) {
        Text(ZenStrings.EDITOR_VALID, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContributionEditorCard(draft: EditorDraft, index: Int, contribution: EditableContribution) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeChip(contribution.type)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { draft.contributions.removeAt(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = ZenStrings.REMOVE,
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(8.dp))
            when (contribution) {
                is EditableContribution.Theme -> ThemeEditor(contribution)
                is EditableContribution.Keys -> KeysEditor(contribution)
                is EditableContribution.Preset -> PresetEditor(contribution)
                is EditableContribution.Terminal -> TerminalSettingsEditor(contribution)
            }
        }
    }
}

// ---------------------------------------------------------------- theme editor

@Composable
private fun ThemeEditor(theme: EditableContribution.Theme) {
    var pickerFor by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (key in THEME_KEY_ORDER) {
            val value = theme.colors[key] ?: ""
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(key, style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(90.dp))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(parsePreviewColor(value) ?: Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .clickable { pickerFor = key }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { theme.colors[key] = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ThemePalettePreview(theme.colors.filterValues { it.isNotBlank() })
    }
    pickerFor?.let { key ->
        ColorPickerDialog(
            initial = parsePreviewColor(theme.colors[key] ?: "") ?: Color.White,
            onPick = { theme.colors[key] = colorToHex(it); pickerFor = null },
            onDismiss = { pickerFor = null }
        )
    }
}

// ---------------------------------------------------------------- extra keys editor

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeysEditor(keys: EditableContribution.Keys) {
    var editingCell by remember { mutableStateOf<EditableCell?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (style in listOf("default", "arrows-only", "arrows-all", "all", "none")) {
                FilterChip(selected = keys.style == style, onClick = { keys.style = style }, label = { Text(style) })
            }
        }
        keys.rows.forEachIndexed { rowIndex, row ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ZenStrings.EDITOR_ROW} ${rowIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { keys.rows.removeAt(rowIndex) }) {
                        Icon(Icons.Filled.Close, contentDescription = ZenStrings.REMOVE,
                            modifier = Modifier.size(16.dp))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { cellIndex, cell ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { editingCell = cell }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                cell.display.ifEmpty { if (cell.useMacro) cell.macro.take(10).ifEmpty { "macro" } else cell.key.ifEmpty { "?" } },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Icon(
                                Icons.Filled.Close, contentDescription = ZenStrings.REMOVE,
                                modifier = Modifier.size(14.dp).clickable { row.removeAt(cellIndex) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { row.add(EditableCell(key = "ESC")) }) {
                        Text("+ ${ZenStrings.EDITOR_ADD_KEY}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        OutlinedButton(onClick = { keys.rows.add(androidx.compose.runtime.mutableStateListOf(EditableCell(key = "ESC"))) }) {
            Text(ZenStrings.EDITOR_ADD_ROW)
        }
        ExtraKeysPreview(keys.rows.map { row -> row.map { it.toKeyCell() } })
    }
    editingCell?.let { cell ->
        CellEditorDialog(cell = cell, onDismiss = { editingCell = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellEditorDialog(cell: EditableCell, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ZenStrings.EDITOR_KEY) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !cell.useMacro, onClick = { cell.useMacro = false },
                        label = { Text(ZenStrings.EDITOR_KEY) })
                    FilterChip(selected = cell.useMacro, onClick = { cell.useMacro = true },
                        label = { Text(ZenStrings.EDITOR_MACRO) })
                }
                if (!cell.useMacro) {
                    OutlinedTextField(cell.key, { cell.key = it },
                        label = { Text(ZenStrings.EDITOR_KEY) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (suggestion in COMMON_KEYS.take(18)) {
                            FilterChip(selected = cell.key == suggestion, onClick = { cell.key = suggestion },
                                label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                } else {
                    OutlinedTextField(cell.macro, { cell.macro = it },
                        label = { Text(ZenStrings.EDITOR_MACRO) },
                        supportingText = { Text("e.g. echo hello\\n") },
                        modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(cell.display, { cell.display = it },
                    label = { Text(ZenStrings.EDITOR_DISPLAY) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                val popup = cell.popup
                if (popup == null) {
                    TextButton(onClick = { cell.popup = EditableCell(key = "ESC") }) {
                        Text("+ ${ZenStrings.EDITOR_POPUP}")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(popup.key, { popup.key = it },
                            label = { Text(ZenStrings.EDITOR_POPUP) }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { cell.popup = null }) {
                            Icon(Icons.Filled.Delete, contentDescription = ZenStrings.REMOVE)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(ZenStrings.CONFIRM) } }
    )
}

// ---------------------------------------------------------------- preset + terminal editors

@Composable
private fun PresetEditor(preset: EditableContribution.Preset) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(preset.name, { preset.name = it },
            label = { Text(ZenStrings.EDITOR_PRESET_NAME) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(preset.dir, { preset.dir = it },
            label = { Text(ZenStrings.EDITOR_PRESET_DIR) }, singleLine = true,
            supportingText = { Text("projects, notes/work, … (empty = home)") },
            modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalSettingsEditor(terminal: EditableContribution.Terminal) {
    var addingKey by remember { mutableStateOf<String?>(null) }
    var addingValue by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        terminal.values.forEach { (key, value) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(key, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                    Text(value, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { terminal.values.remove(key) }) {
                    Icon(Icons.Filled.Close, contentDescription = ZenStrings.REMOVE, modifier = Modifier.size(16.dp))
                }
            }
        }
        val remaining = TerminalSettingsSchema.KEYS.filter { it !in terminal.values }
        if (remaining.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in remaining) {
                    FilterChip(selected = addingKey == key, onClick = { addingKey = key; addingValue = "" },
                        label = { Text(key, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
        addingKey?.let { key ->
            OutlinedTextField(addingValue, { addingValue = it },
                label = { Text(key) },
                supportingText = { Text(TerminalSettingsSchema.VALUE_HINTS[key] ?: "") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (TerminalSettingsSchema.validate(key, addingValue)) {
                        terminal.values[key] = addingValue
                        addingKey = null
                    }
                }) { Text(ZenStrings.ADD) }
                TextButton(onClick = { addingKey = null }) { Text(ZenStrings.CANCEL) }
            }
            if (addingValue.isNotEmpty() && !TerminalSettingsSchema.validate(key, addingValue)) {
                Text("Invalid value for $key", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------- settings schema designer

@Composable
private fun SettingsSchemaSection(draft: EditorDraft) {
    var editing by remember { mutableStateOf<SettingSpec?>(null) }
    var creating by remember { mutableStateOf(false) }
    SectionHeader(ZenStrings.EDITOR_SETTINGS_SCHEMA)
    Text(
        "Settings you declare here are rendered automatically for the user. Reference them in contribution values with \${settings.id}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    draft.settings.forEachIndexed { index, spec ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("${spec.id} · ${settingTypeName(spec.type)} · default ${spec.defaultValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { editing = spec }) {
                Icon(Icons.Filled.Edit, contentDescription = ZenStrings.EDIT_PLUGIN, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { draft.settings.removeAt(index) }) {
                Icon(Icons.Filled.Delete, contentDescription = ZenStrings.REMOVE,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    OutlinedButton(onClick = { creating = true }) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(ZenStrings.EDITOR_ADD_SETTING)
    }
    if (creating || editing != null) {
        SettingSpecDialog(
            initial = editing,
            existingIds = draft.settings.map { it.id }.toSet(),
            onSave = { spec ->
                val current = editing
                if (current != null) {
                    val index = draft.settings.indexOf(current)
                    if (index >= 0) draft.settings[index] = spec else draft.settings.add(spec)
                } else {
                    draft.settings.add(spec)
                }
                creating = false
                editing = null
            },
            onDismiss = { creating = false; editing = null }
        )
    }
}

fun settingTypeName(type: SettingType): String = when (type) {
    is SettingType.Toggle -> ZenStrings.SETTING_TOGGLE
    is SettingType.Number -> ZenStrings.SETTING_NUMBER
    is SettingType.Text -> ZenStrings.SETTING_TEXT
    is SettingType.Choice -> ZenStrings.SETTING_CHOICE
    is SettingType.Color -> ZenStrings.SETTING_COLOR
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingSpecDialog(
    initial: SettingSpec?,
    existingIds: Set<String>,
    onSave: (SettingSpec) -> Unit,
    onDismiss: () -> Unit
) {
    var id by remember { mutableStateOf(initial?.id ?: "") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var typeName by remember {
        mutableStateOf(when (initial?.type) {
            is SettingType.Number -> "number"
            is SettingType.Text -> "text"
            is SettingType.Choice -> "choice"
            is SettingType.Color -> "color"
            else -> "toggle"
        })
    }
    var default by remember { mutableStateOf(initial?.defaultValue ?: "true") }
    var min by remember { mutableStateOf((initial?.type as? SettingType.Number)?.min?.toString() ?: "0") }
    var max by remember { mutableStateOf((initial?.type as? SettingType.Number)?.max?.toString() ?: "100") }
    var maxLength by remember { mutableStateOf((initial?.type as? SettingType.Text)?.maxLength?.toString() ?: "128") }
    var optionsText by remember {
        mutableStateOf((initial?.type as? SettingType.Choice)?.options?.joinToString("\n") { "${it.value}=${it.label}" } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun build(): SettingSpec? {
        if (!ZenApi.SETTING_ID_REGEX.matches(id)) { error = "ID must match ${ZenApi.SETTING_ID_REGEX.pattern}"; return null }
        if (initial == null && id in existingIds) { error = "ID already used"; return null }
        if (initial != null && id != initial.id && id in existingIds) { error = "ID already used"; return null }
        if (label.isBlank()) { error = "Label is required"; return null }
        val type: SettingType = when (typeName) {
            "toggle" -> SettingType.Toggle
            "number" -> {
                val minValue = min.toIntOrNull(); val maxValue = max.toIntOrNull()
                if (minValue == null || maxValue == null || minValue > maxValue) { error = "Min/max must be integers, min ≤ max"; return null }
                SettingType.Number(minValue, maxValue)
            }
            "text" -> {
                val length = maxLength.toIntOrNull()
                if (length == null || length < 1 || length > 512) { error = "Max length must be 1..512"; return null }
                SettingType.Text(length)
            }
            "choice" -> {
                val options = optionsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) { error = "Options need value=label lines"; return null }
                    ChoiceOption(parts[0].trim(), parts[1].trim())
                }
                if (options.size < 2 || options.size > 16) { error = "Provide 2..16 options"; return null }
                if (options.map { it.value }.toSet().size != options.size) { error = "Option values must be unique"; return null }
                SettingType.Choice(options)
            }
            else -> SettingType.Color
        }
        val spec = SettingSpec(id, label.trim(), description.trim().ifEmpty { null }, type, default)
        val validation = PluginSettings.validateValue(spec, default)
        if (validation != null) { error = "Default $validation"; return null }
        return spec
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ZenStrings.EDITOR_ADD_SETTING) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(id, { id = it }, label = { Text(ZenStrings.EDITOR_SETTING_ID) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(label, { label = it }, label = { Text(ZenStrings.EDITOR_SETTING_LABEL) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in listOf("toggle" to ZenStrings.SETTING_TOGGLE, "number" to ZenStrings.SETTING_NUMBER,
                        "text" to ZenStrings.SETTING_TEXT, "choice" to ZenStrings.SETTING_CHOICE, "color" to ZenStrings.SETTING_COLOR)) {
                        FilterChip(selected = typeName == option.first, onClick = {
                            typeName = option.first
                            default = when (option.first) {
                                "toggle" -> "true"; "number" -> min; "color" -> "#7bd88f"; "choice" ->
                                    optionsText.lines().firstOrNull()?.split('=')?.firstOrNull()?.trim() ?: ""
                                else -> ""
                            }
                        }, label = { Text(option.second) })
                    }
                }
                when (typeName) {
                    "number" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(min, { min = it }, label = { Text(ZenStrings.EDITOR_SETTING_MIN) },
                            singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(max, { max = it }, label = { Text(ZenStrings.EDITOR_SETTING_MAX) },
                            singleLine = true, modifier = Modifier.weight(1f))
                    }
                    "text" -> OutlinedTextField(maxLength, { maxLength = it },
                        label = { Text("Max length") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    "choice" -> OutlinedTextField(optionsText, { optionsText = it },
                        label = { Text(ZenStrings.EDITOR_SETTING_OPTIONS) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
                OutlinedTextField(default, { default = it }, label = { Text(ZenStrings.EDITOR_SETTING_DEFAULT) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { build()?.let(onSave) }) { Text(ZenStrings.SAVE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ZenStrings.CANCEL) } }
    )
}

// ---------------------------------------------------------------- add contribution dialog

@Composable
private fun AddContributionDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ZenStrings.CHOOSE_CONTRIBUTION_TYPE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContributionChoice(ZenApi.TYPE_THEME, "Terminal color palette") { onPick(ZenApi.TYPE_THEME) }
                ContributionChoice(ZenApi.TYPE_EXTRA_KEYS, "Extra-keys bar layout") { onPick(ZenApi.TYPE_EXTRA_KEYS) }
                ContributionChoice(ZenApi.TYPE_SESSION_PRESET, "New-session menu entry") { onPick(ZenApi.TYPE_SESSION_PRESET) }
                ContributionChoice(ZenApi.TYPE_TERMINAL_SETTINGS, "Cursor, bell, margins, scrollback") { onPick(ZenApi.TYPE_TERMINAL_SETTINGS) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(ZenStrings.CANCEL) } }
    )
}

@Composable
private fun ContributionChoice(type: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(typeLabel(type), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TypeChip(type)
    }
}
