package com.termux.app.plugins.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import com.termux.app.plugins.PluginArchive
import com.termux.app.plugins.PluginExporter
import com.termux.app.plugins.PluginInstaller
import com.termux.app.plugins.PluginManager
import com.termux.app.plugins.PluginManifest
import com.termux.app.plugins.PluginParser
import com.termux.app.plugins.PluginRecord
import com.termux.app.plugins.PluginRepository
import com.termux.app.plugins.PluginRuntime
import com.termux.app.plugins.PluginStore
import com.termux.app.plugins.ZenApi

/** UI model for one installed plugin: record + parsed document + runtime status. */
data class InstalledPlugin(
    val record: PluginRecord,
    val manifest: PluginManifest?,
    val status: PluginRuntime.Status?,
    val loadError: String?
)

data class BundledItem(
    val assetName: String,
    val manifest: PluginManifest?,
    val bytes: ByteArray,
    val error: String?
)

sealed class CatalogState {
    object NotConfigured : CatalogState()
    object Loading : CatalogState()
    data class Loaded(val catalog: PluginRepository.Catalog) : CatalogState()
    data class Error(val message: String) : CatalogState()
}

sealed class Screen {
    object Installed : Screen()
    object Browse : Screen()
    data class Detail(val id: String) : Screen()
    data class Editor(val originalId: String?, val draft: EditorDraft) : Screen()
}

/**
 * Hub state holder. All plugin work goes through [PluginManager.async] (single background
 * executor); results land on the main thread and update Compose state. The controller is
 * remembered across recomposition and lives as long as the activity.
 */
class HubController(private val activity: ComponentActivity) {

    var screen by mutableStateOf<Screen>(Screen.Installed)
    private val backStack = ArrayList<Screen>()

    var installed by mutableStateOf<List<InstalledPlugin>>(emptyList())
    var bundled by mutableStateOf<List<BundledItem>>(emptyList())
    var catalogState by mutableStateOf<CatalogState>(
        if (PluginManager.repositoryConfigured()) CatalogState.Loading else CatalogState.NotConfigured
    )
    var search by mutableStateOf("")
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    fun navigate(next: Screen) {
        backStack.add(screen)
        screen = next
        if (next is Screen.Browse) loadBrowse()
    }

    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        screen = backStack.removeAt(backStack.size - 1)
        return true
    }

    fun findInstalled(id: String): InstalledPlugin? = installed.firstOrNull { it.record.id == id }

    // ---------------------------------------------------------------- loading

    fun refresh() {
        PluginManager.async({
            val store = PluginManager.getStore() ?: return@async emptyList()
            val records = (store.loadRegistry() as? PluginStore.RegistryLoad.Ok)?.records ?: emptyList()
            val statuses = PluginManager.getRuntime()?.getStatuses() ?: emptyMap()
            records.sortedBy { it.id }.map { record ->
                var manifest: PluginManifest? = null
                var error: String? = null
                try {
                    manifest = PluginParser.parse(store.readVerified(record))
                } catch (e: Exception) {
                    error = e.message
                }
                InstalledPlugin(record, manifest, statuses[record.id], error)
            }
        }, { result ->
            result.onSuccess { installed = it }
            result.onFailure { message = it.message ?: "Could not load plugins" }
        })
    }

    fun loadBrowse() {
        PluginManager.async({
            PluginManager.bundledPlugins().map { bundledPlugin ->
                try {
                    BundledItem(bundledPlugin.assetName,
                        PluginParser.parse(PluginArchive.readPluginJson(bundledPlugin.bytes)),
                        bundledPlugin.bytes, null)
                } catch (e: Exception) {
                    BundledItem(bundledPlugin.assetName, null, bundledPlugin.bytes, e.message)
                }
            }
        }, { result ->
            result.onSuccess { bundled = it }
            result.onFailure { message = it.message ?: "Could not read bundled plugins" }
        })
        if (PluginManager.repositoryConfigured() && catalogState !is CatalogState.Loaded) refreshCatalog()
    }

    fun refreshCatalog() {
        if (!PluginManager.repositoryConfigured()) {
            catalogState = CatalogState.NotConfigured
            return
        }
        catalogState = CatalogState.Loading
        PluginManager.async({ PluginManager.newRepositoryClient().fetchCatalog() }, { result ->
            catalogState = result.fold(
                onSuccess = { CatalogState.Loaded(it) },
                onFailure = { CatalogState.Error(it.message ?: "Catalog fetch failed") }
            )
        })
    }

    // ---------------------------------------------------------------- operations

    private fun op(successMessage: String?, after: (() -> Unit)? = null, work: () -> Unit) {
        busy = true
        PluginManager.async(work, { result ->
            busy = false
            result.onSuccess {
                successMessage?.let { message = it }
                refresh()
                if (screen is Screen.Browse) loadBrowse()
                after?.invoke()
            }
            result.onFailure {
                message = it.message ?: "Operation failed"
                refresh()
            }
        })
    }

    fun toggle(id: String, enabled: Boolean) =
        op(null) { PluginManager.getInstaller()?.setEnabled(id, enabled) }

    fun uninstall(id: String) =
        op(ZenStrings.PLUGIN_UNINSTALLED, after = {
            if (screen is Screen.Detail && (screen as Screen.Detail).id == id) goBack()
        }) { PluginManager.getInstaller()?.uninstall(id) }

    fun installBundled(item: BundledItem) =
        op(ZenStrings.PLUGIN_INSTALLED) {
            PluginManager.getInstaller()?.installArtifact(item.bytes, ZenApi.SOURCE_BUNDLED, enable = true)
        }

    fun installCatalogEntry(entry: PluginRepository.CatalogEntry) =
        op(ZenStrings.PLUGIN_INSTALLED) {
            val bytes = PluginManager.newRepositoryClient().downloadArtifact(entry)
            PluginManager.getInstaller()?.installArtifact(
                bytes, ZenApi.SOURCE_REPOSITORY, enable = false,
                expected = PluginInstaller.CatalogIdentity(entry.id, entry.version)
            )
        }

    fun setSetting(id: String, key: String, value: String) =
        op(null) { PluginManager.getInstaller()?.setSetting(id, key, value) }

    fun resetSettings(id: String) =
        op(null) { PluginManager.getInstaller()?.resetSettings(id) }

    fun duplicateAndEdit(id: String) {
        busy = true
        PluginManager.async({ PluginManager.getInstaller()?.duplicate(id) }, { result ->
            busy = false
            result.onSuccess { manifest ->
                if (manifest != null) {
                    refresh()
                    navigate(Screen.Editor(manifest.id, EditorDraft.from(manifest)))
                }
            }
            result.onFailure { message = it.message ?: "Could not duplicate plugin" }
        })
    }

    fun export(id: String) {
        busy = true
        PluginManager.async({
            val store = PluginManager.getStore() ?: throw IllegalStateException("plugins not initialized")
            val record = (store.loadRegistry() as? PluginStore.RegistryLoad.Ok)?.records
                ?.firstOrNull { it.id == id } ?: throw IllegalStateException("plugin not installed")
            val manifest = PluginParser.parse(store.readVerified(record))
            PluginExporter.exportToCache(activity, manifest)
        }, { result ->
            busy = false
            result.onSuccess { file ->
                try {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.zen.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(send, ZenStrings.SHARE_PLUGIN))
                    message = ZenStrings.PLUGIN_EXPORTED
                } catch (e: Exception) {
                    message = "Export saved to ${file.absolutePath}"
                }
            }
            result.onFailure { message = it.message ?: "Export failed" }
        })
    }

    fun importFromUri(uri: Uri) {
        busy = true
        PluginManager.async({
            val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("cannot read the selected file")
            PluginManager.getInstaller()?.installArtifact(bytes, ZenApi.SOURCE_IMPORT, enable = false)
        }, { result ->
            busy = false
            result.onSuccess {
                message = ZenStrings.PLUGIN_INSTALLED
                refresh()
            }
            result.onFailure { message = it.message ?: "Import failed" }
        })
    }

    fun saveDraft(draft: EditorDraft) {
        busy = true
        PluginManager.async({
            val manifest = draft.toManifest()
            val text = PluginParser.toJson(manifest)
            PluginParser.parse(text) // round-trip validation before committing
            PluginManager.getInstaller()?.installDocument(text, ZenApi.SOURCE_LOCAL, enable = false)
            manifest.id
        }, { result ->
            busy = false
            result.onSuccess { id ->
                message = ZenStrings.PLUGIN_SAVED
                refresh()
                goBack()
                if (id != null) navigate(Screen.Detail(id))
            }
            result.onFailure { message = it.message ?: "Save failed" }
        })
    }

    fun startCreate() {
        navigate(Screen.Editor(null, EditorDraft.blank()))
    }

    fun startEdit(item: InstalledPlugin) {
        val manifest = item.manifest ?: return
        navigate(Screen.Editor(item.record.id, EditorDraft.from(manifest)))
    }
}
