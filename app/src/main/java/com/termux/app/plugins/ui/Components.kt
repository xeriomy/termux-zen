package com.termux.app.plugins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.plugins.Contribution
import com.termux.app.plugins.KeyCell
import com.termux.app.plugins.PluginRuntime
import com.termux.app.plugins.SettingSpec
import com.termux.app.plugins.SettingType
import com.termux.app.plugins.ZenApi
import kotlin.math.roundToInt

// ---------------------------------------------------------------- chrome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = ZenStrings.BACK)
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ZenStrings.CANCEL) }
        }
    )
}

// ---------------------------------------------------------------- chips and rows

fun typeLabel(type: String): String = when (type) {
    ZenApi.TYPE_THEME -> ZenStrings.TYPE_THEME
    ZenApi.TYPE_EXTRA_KEYS -> ZenStrings.TYPE_EXTRA_KEYS
    ZenApi.TYPE_SESSION_PRESET -> ZenStrings.TYPE_SESSION_PRESET
    ZenApi.TYPE_TERMINAL_SETTINGS -> ZenStrings.TYPE_TERMINAL_SETTINGS
    else -> type
}

@Composable
fun TypeChip(type: String) {
    val icon = when (type) {
        ZenApi.TYPE_THEME -> Icons.Filled.Palette
        ZenApi.TYPE_EXTRA_KEYS -> Icons.Filled.Keyboard
        ZenApi.TYPE_SESSION_PRESET -> Icons.Filled.Folder
        else -> Icons.Filled.Terminal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(typeLabel(type), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(status: PluginRuntime.Status?) {
    val (label, color) = when (status) {
        is PluginRuntime.Status.Active -> ZenStrings.ACTIVE_LABEL to MaterialTheme.colorScheme.primary
        is PluginRuntime.Status.Disabled -> ZenStrings.DISABLED_LABEL to MaterialTheme.colorScheme.onSurfaceVariant
        is PluginRuntime.Status.Invalid -> ZenStrings.INVALID_LABEL to MaterialTheme.colorScheme.error
        is PluginRuntime.Status.Overridden -> ZenStrings.OVERRIDDEN_LABEL to MaterialTheme.colorScheme.tertiary
        null -> ZenStrings.DISABLED_LABEL to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun PluginRow(
    item: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.manifest?.name ?: item.record.id,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${item.record.version} · ${item.record.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(item.status)
                    item.manifest?.contributionTypes?.take(3)?.forEach { TypeChip(it) }
                }
                if (item.status is PluginRuntime.Status.Invalid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = item.record.enabled, onCheckedChange = onToggle)
        }
    }
}

// ---------------------------------------------------------------- contribution previews

@Composable
fun ThemePalettePreview(colors: Map<String, String>) {
    val background = parsePreviewColor(colors["background"] ?: "") ?: Color(0xFF101820)
    val foreground = parsePreviewColor(colors["foreground"] ?: "") ?: Color(0xFFE6E6E6)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(12.dp)
    ) {
        Text(
            "$ whoami",
            color = foreground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "zen",
            color = parsePreviewColor(colors["color2"] ?: "") ?: foreground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0..7) {
                val color = parsePreviewColor(colors["color$i"] ?: "") ?: foreground
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 8..15) {
                val color = parsePreviewColor(colors["color$i"] ?: "") ?: foreground
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}

@Composable
fun ExtraKeysPreview(rows: List<List<KeyCell>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (cell in row) {
                    val label = cell.display ?: cell.key ?: cell.macro?.take(8) ?: "?"
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings form

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsForm(
    settings: List<SettingSpec>,
    values: Map<String, String>,
    onChange: (String, String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (spec in settings) {
            val value = values[spec.id] ?: spec.defaultValue
            when (val type = spec.type) {
                is SettingType.Toggle -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.label, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            spec.description?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = value == "true", onCheckedChange = { onChange(spec.id, it.toString()) })
                    }
                }
                is SettingType.Number -> {
                    Column {
                        Text("${spec.label}: $value", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Slider(
                            value = (value.toIntOrNull() ?: type.min).toFloat(),
                            onValueChange = { onChange(spec.id, it.roundToInt().toString()) },
                            valueRange = type.min.toFloat()..type.max.toFloat()
                        )
                    }
                }
                is SettingType.Text -> {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { if (it.length <= type.maxLength) onChange(spec.id, it) },
                        label = { Text(spec.label) },
                        supportingText = spec.description?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is SettingType.Choice -> {
                    Column {
                        Text(spec.label, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (option in type.options) {
                                FilterChip(
                                    selected = value == option.value,
                                    onClick = { onChange(spec.id, option.value) },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                    }
                }
                is SettingType.Color -> {
                    var pickerOpen by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spec.label, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(value, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(parsePreviewColor(value) ?: Color.Gray)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { pickerOpen = true }
                        )
                    }
                    if (pickerOpen) {
                        ColorPickerDialog(
                            initial = parsePreviewColor(value) ?: Color.White,
                            onPick = { onChange(spec.id, colorToHex(it)); pickerOpen = false },
                            onDismiss = { pickerOpen = false }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- color picker

fun colorToHex(color: Color): String {
    val r = (color.red * 255).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}

@Composable
fun ColorPickerDialog(initial: Color, onPick: (Color) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember {
        val array = FloatArray(3)
        android.graphics.Color.colorToHSV(
            android.graphics.Color.rgb(
                (initial.red * 255).roundToInt(),
                (initial.green * 255).roundToInt(),
                (initial.blue * 255).roundToInt()
            ), array
        )
        array
    }
    var hue by remember { mutableStateOf(hsv[0]) }
    var saturation by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    var hexText by remember { mutableStateOf(colorToHex(initial)) }
    val current = Color.hsv(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ZenStrings.EDITOR_PICK_COLOR) },
        text = {
            Column {
                // Saturation/value box for the current hue.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.White, Color.hsv(hue, 1f, 1f))
                            )
                        )
                        .pointerInput(hue) {
                            fun update(offset: androidx.compose.ui.geometry.Offset) {
                                saturation = (offset.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                                hexText = colorToHex(Color.hsv(hue, saturation, value))
                            }
                            detectTapGestures { update(it) }
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                                hexText = colorToHex(Color.hsv(hue, saturation, value))
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Hue slider with a rainbow track.
                Slider(
                    value = hue,
                    onValueChange = {
                        hue = it
                        hexText = colorToHex(Color.hsv(hue, saturation, value))
                    },
                    valueRange = 0f..360f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Color.hsv(hue, 1f, 1f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
                            )
                        )
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text
                            val parsed = parsePreviewColor(text)
                            if (parsed != null && text.length == 7) {
                                val array = FloatArray(3)
                                android.graphics.Color.colorToHSV(
                                    android.graphics.Color.rgb(
                                        (parsed.red * 255).roundToInt(),
                                        (parsed.green * 255).roundToInt(),
                                        (parsed.blue * 255).roundToInt()
                                    ), array
                                )
                                hue = array[0]; saturation = array[1]; value = array[2]
                            }
                        },
                        label = { Text(ZenStrings.EDITOR_HEX) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) { Text(ZenStrings.CONFIRM) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ZenStrings.CANCEL) }
        }
    )
}
