package com.termux.app.plugins.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.plugins.Contribution
import com.termux.app.plugins.PluginRuntime
import com.termux.app.plugins.TerminalSettingsSchema
import com.termux.app.plugins.ZenApi

@Composable
fun PluginHubApp(controller: HubController) {
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { controller.importFromUri(it) } }
    LaunchedEffect(controller.message) {
        controller.message?.let {
            snackbarHostState.showSnackbar(it)
            controller.message = null
        }
    }
    val screen = controller.screen
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when (screen) {
                is Screen.Installed -> ZenTopBar(ZenStrings.TITLE, actions = {
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = ZenStrings.IMPORT_PLUGIN)
                    }
                    IconButton(onClick = { controller.navigate(Screen.Browse) }) {
                        Icon(Icons.Filled.Storefront, contentDescription = ZenStrings.TAB_BROWSE)
                    }
                })
                is Screen.Browse -> ZenTopBar(ZenStrings.TAB_BROWSE, onBack = { controller.goBack() }, actions = {
                    if (com.termux.app.plugins.PluginManager.repositoryConfigured()) {
                        IconButton(onClick = { controller.refreshCatalog() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = ZenStrings.REPOSITORY_REFRESH)
                        }
                    }
                })
                is Screen.Detail -> ZenTopBar(ZenStrings.DETAILS_TITLE, onBack = { controller.goBack() })
                is Screen.Editor -> ZenTopBar(
                    if (screen.originalId == null) ZenStrings.EDITOR_TITLE_NEW else ZenStrings.EDITOR_TITLE_EDIT,
                    onBack = { controller.goBack() }
                )
            }
        },
        floatingActionButton = {
            if (screen is Screen.Installed) {
                FloatingActionButton(onClick = { controller.startCreate() }) {
                    Icon(Icons.Filled.Add, contentDescription = ZenStrings.CREATE_PLUGIN)
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                is Screen.Installed -> InstalledScreen(controller)
                is Screen.Browse -> BrowseScreen(controller)
                is Screen.Detail -> DetailScreen(controller, screen.id)
                is Screen.Editor -> EditorScreen(controller, screen.draft)
            }
            if (controller.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

// ---------------------------------------------------------------- installed

/** True when toggling needs a confirmation, with the reason body. */
fun toggleConfirmation(controller: HubController, item: InstalledPlugin, target: Boolean): String? {
    val manifest = item.manifest ?: return null
    if (!target) {
        val hasDependants = controller.installed.any { other ->
            other.record.id != item.record.id &&
                other.manifest?.dependencies?.any { it.id == item.record.id } == true
        }
        return if (hasDependants) ZenStrings.CONFIRM_DISABLE_DEPENDANTS else null
    }
    val exclusiveTypes = manifest.contributionTypes.filter { it in ZenApi.EXCLUSIVE_TYPES }
    if (exclusiveTypes.isEmpty()) return null
    val conflict = controller.installed.any { other ->
        other.record.id != item.record.id && other.record.enabled &&
            other.manifest?.contributionTypes?.any { it in exclusiveTypes } == true
    }
    return if (conflict) ZenStrings.CONFIRM_ENABLE_SWAP else null
}

@Composable
fun InstalledScreen(controller: HubController) {
    var toggleRequest by remember { mutableStateOf<Pair<InstalledPlugin, Boolean>?>(null) }
    val query = controller.search.trim().lowercase()
    val filtered = controller.installed.filter { item ->
        query.isEmpty() ||
            (item.manifest?.name ?: item.record.id).lowercase().contains(query) ||
            item.record.id.lowercase().contains(query) ||
            (item.manifest?.description ?: "").lowercase().contains(query)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = controller.search,
            onValueChange = { controller.search = it },
            placeholder = { Text(ZenStrings.SEARCH_HINT) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (filtered.isEmpty()) {
            EmptyState(
                if (controller.installed.isEmpty()) ZenStrings.EMPTY_INSTALLED_TITLE else ZenStrings.EMPTY_SEARCH,
                if (controller.installed.isEmpty()) ZenStrings.EMPTY_INSTALLED_BODY else ""
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.record.id }) { item ->
                    PluginRow(
                        item = item,
                        onToggle = { enabled ->
                            if (toggleConfirmation(controller, item, enabled) != null)
                                toggleRequest = item to enabled
                            else controller.toggle(item.record.id, enabled)
                        },
                        onClick = { controller.navigate(Screen.Detail(item.record.id)) }
                    )
                }
            }
        }
    }
    toggleRequest?.let { (item, target) ->
        ConfirmDialog(
            title = if (target) ZenStrings.ENABLE_PLUGIN else ZenStrings.DISABLE_PLUGIN,
            body = toggleConfirmation(controller, item, target) ?: "",
            confirmLabel = ZenStrings.CONFIRM,
            onConfirm = { controller.toggle(item.record.id, target) },
            onDismiss = { toggleRequest = null }
        )
    }
}

// ---------------------------------------------------------------- browse

@Composable
fun BrowseScreen(controller: HubController) {
    var confirmInstallBundled by remember { mutableStateOf<BundledItem?>(null) }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionHeader(ZenStrings.BUNDLED_SECTION) }
        if (controller.bundled.isEmpty()) {
            item {
                Text(
                    ZenStrings.REPOSITORY_LOADING,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(controller.bundled, key = { it.assetName }) { bundledItem ->
            val installedVersion = controller.installed
                .firstOrNull { it.record.id == bundledItem.manifest?.id }?.record?.version?.raw
            BrowseCard(
                title = bundledItem.manifest?.name ?: bundledItem.assetName,
                description = bundledItem.manifest?.description ?: bundledItem.error ?: "",
                version = bundledItem.manifest?.version?.raw ?: "?",
                types = bundledItem.manifest?.contributionTypes ?: emptyList(),
                installedVersion = installedVersion,
                buttonLabel = if (installedVersion != null) ZenStrings.INSTALLED_LABEL else ZenStrings.INSTALL_PLUGIN,
                enabled = installedVersion == null,
                onInstall = { confirmInstallBundled = bundledItem }
            )
        }
        item { SectionHeader(ZenStrings.REPOSITORY_SECTION) }
        item {
            when (val state = controller.catalogState) {
                is CatalogState.NotConfigured -> Text(
                    ZenStrings.REPOSITORY_NOT_CONFIGURED,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is CatalogState.Loading -> Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(ZenStrings.REPOSITORY_LOADING, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is CatalogState.Error -> Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(state.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { controller.refreshCatalog() }) { Text(ZenStrings.REPOSITORY_RETRY) }
                }
                is CatalogState.Loaded -> Text(
                    state.catalog.repositoryName,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val catalog = (controller.catalogState as? CatalogState.Loaded)?.catalog
        if (catalog != null) {
            items(catalog.entries, key = { it.id }) { entry ->
                val installedVersion = controller.installed.firstOrNull { it.record.id == entry.id }?.record?.version
                val isUpdate = installedVersion != null && entry.version > installedVersion
                BrowseCard(
                    title = entry.name,
                    description = entry.description ?: "",
                    version = entry.version.raw,
                    types = entry.types,
                    installedVersion = installedVersion?.raw,
                    buttonLabel = when {
                        isUpdate -> ZenStrings.UPDATE_PLUGIN
                        installedVersion != null -> ZenStrings.INSTALLED_LABEL
                        else -> ZenStrings.INSTALL_PLUGIN
                    },
                    enabled = installedVersion == null || isUpdate,
                    onInstall = { controller.installCatalogEntry(entry) }
                )
            }
        }
    }
    confirmInstallBundled?.let { item ->
        ConfirmDialog(
            title = ZenStrings.CONFIRM_INSTALL_TITLE,
            body = ZenStrings.CONFIRM_INSTALL_BODY,
            confirmLabel = ZenStrings.CONFIRM_INSTALL,
            onConfirm = { controller.installBundled(item) },
            onDismiss = { confirmInstallBundled = null }
        )
    }
}

@Composable
fun BrowseCard(
    title: String,
    description: String,
    version: String,
    types: List<String>,
    installedVersion: String?,
    buttonLabel: String,
    enabled: Boolean = true,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (installedVersion != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ZenStrings.INSTALLED_LABEL,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        if (installedVersion != null) "$version · ${ZenStrings.INSTALLED_LABEL} $installedVersion" else version,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onInstall, enabled = enabled) { Text(buttonLabel, maxLines = 1) }
            }
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (types.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.take(4).forEach { TypeChip(it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- detail

@Composable
fun DetailScreen(controller: HubController, id: String) {
    val item = controller.findInstalled(id)
    if (item == null) {
        EmptyState(ZenStrings.EMPTY_INSTALLED_TITLE, "")
        return
    }
    val manifest = item.manifest
    var confirmUninstall by remember { mutableStateOf(false) }
    var confirmToggle by remember { mutableStateOf<Boolean?>(null) }
    var confirmEdit by remember { mutableStateOf(false) }

    val dependants = controller.installed.filter { other ->
        other.record.id != id && other.manifest?.dependencies?.any { it.id == id } == true
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Header, Details and description share one card: the details belong to the
            // plugin's identity, not to a separate screen section.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(manifest?.name ?: id, style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Switch(
                            checked = item.record.enabled,
                            onCheckedChange = { target ->
                                if (toggleConfirmation(controller, item, target) != null) confirmToggle = target
                                else controller.toggle(id, target)
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(id, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(item.status)
                        manifest?.contributionTypes?.forEach { TypeChip(it) }
                    }
                    if (item.status is PluginRuntime.Status.Invalid) {
                        Spacer(Modifier.height(8.dp))
                        Text(item.status.reason, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.loadError != null && manifest == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(item.loadError, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (manifest != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        DetailLine(ZenStrings.VERSION_LABEL, item.record.version.raw)
                        DetailLine(ZenStrings.AUTHOR_LABEL, manifest.author ?: "—")
                        DetailLine(ZenStrings.SOURCE_LABEL, sourceLabel(item.record.source))
                        DetailLine(ZenStrings.ID_LABEL, item.record.id)
                        if (!manifest.description.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(manifest.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (manifest != null) {
            item { SectionHeader(ZenStrings.CONTRIBUTIONS_TITLE) }
            for (contribution in manifest.contributions) {
                item { ContributionPreview(contribution) }
            }
            if (manifest.dependencies.isNotEmpty()) {
                item { SectionHeader(ZenStrings.DEPENDENCIES_TITLE) }
                item {
                    Column {
                        for (dependency in manifest.dependencies) {
                            val installed = controller.installed.firstOrNull { it.record.id == dependency.id }
                            Text(
                                "${dependency.id} @ ${dependency.minVersion}" +
                                    if (installed == null) " · missing"
                                    else if (!installed.record.enabled) " · ${ZenStrings.DISABLED_LABEL}"
                                    else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (installed == null || !installed.record.enabled)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    // Any installed plugin can be customized in place; duplication and export
                    // are intentionally not offered from the detail screen.
                    Button(onClick = {
                        if (item.record.source in ZenApi.EDITABLE_SOURCES) controller.startEdit(item)
                        else confirmEdit = true
                    }, modifier = Modifier.weight(1f)) {
                        Text(ZenStrings.EDIT_PLUGIN)
                    }
                    OutlinedButton(
                        onClick = { confirmUninstall = true },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.height(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ZenStrings.UNINSTALL_PLUGIN)
                    }
                }
            }
        }
    }
    if (confirmEdit) {
        ConfirmDialog(
            title = ZenStrings.CONFIRM_EDIT_TITLE,
            body = ZenStrings.CONFIRM_EDIT_BODY,
            confirmLabel = ZenStrings.EDIT_PLUGIN,
            onConfirm = { confirmEdit = false; controller.startEdit(item) },
            onDismiss = { confirmEdit = false }
        )
    }
    if (confirmUninstall) {
        ConfirmDialog(
            title = ZenStrings.CONFIRM_UNINSTALL_TITLE,
            body = ZenStrings.CONFIRM_UNINSTALL_BODY +
                if (dependants.isNotEmpty()) "\n\n" + ZenStrings.CONFIRM_DISABLE_DEPENDANTS else "",
            confirmLabel = ZenStrings.UNINSTALL_PLUGIN,
            onConfirm = { controller.uninstall(id) },
            onDismiss = { confirmUninstall = false }
        )
    }
    confirmToggle?.let { target ->
        ConfirmDialog(
            title = if (target) ZenStrings.ENABLE_PLUGIN else ZenStrings.DISABLE_PLUGIN,
            body = if (target) ZenStrings.CONFIRM_ENABLE_SWAP else ZenStrings.CONFIRM_DISABLE_DEPENDANTS,
            confirmLabel = ZenStrings.CONFIRM,
            onConfirm = { controller.toggle(id, target) },
            onDismiss = { confirmToggle = null }
        )
    }
}

fun sourceLabel(source: String): String = when (source) {
    ZenApi.SOURCE_BUNDLED -> ZenStrings.SOURCE_BUNDLED
    ZenApi.SOURCE_REPOSITORY -> ZenStrings.SOURCE_REPOSITORY
    ZenApi.SOURCE_LOCAL -> ZenStrings.SOURCE_LOCAL
    ZenApi.SOURCE_IMPORT -> ZenStrings.SOURCE_IMPORT
    else -> source
}

@Composable
fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun ContributionPreview(contribution: Contribution) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            TypeChip(contribution.type)
            Spacer(Modifier.height(8.dp))
            when (contribution) {
                is Contribution.Theme -> ThemePalettePreview(contribution.colors)
                is Contribution.ExtraKeys -> ExtraKeysPreview(contribution.rows)
                is Contribution.SessionPreset -> Text(
                    "${contribution.name} → ${contribution.workingDirectory ?: "~"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is Contribution.TerminalSettings -> Column {
                    for ((key, value) in contribution.values) {
                        Text("$key = $value", style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
