package com.termux.app.plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.BuildConfig
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App-scoped facade for the Zen plugin system. [init] runs once from TermuxApplication;
 * host hooks use the static getters, the UI uses [async] + change listeners. All storage
 * and lifecycle work happens on one background executor; results are posted to the main thread.
 */
object PluginManager {

    @Volatile
    private var initialized = false

    private lateinit var appContext: Context
    private lateinit var store: PluginStore
    private lateinit var runtime: PluginRuntime
    private lateinit var installer: PluginInstaller

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zen-plugins").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = PluginStore(File(appContext.filesDir, "plugins"))
        store.ensureDirectories()
        store.clearStaging()
        val coreVersion = Semver.parse(BuildConfig.VERSION_NAME) ?: Semver.parse("0.0.0")!!
        try {
            PluginMigration.migrateIfNeeded(store)
        } catch (e: Exception) {
            // Migration is best-effort; the app must start even when it fails.
        }
        runtime = PluginRuntime(store, coreVersion, TermuxConstants.TERMUX_HOME_DIR_PATH)
        installer = PluginInstaller(store, runtime, coreVersion)
        runtime.reload()
        try {
            val records = (store.loadRegistry() as? PluginStore.RegistryLoad.Ok)?.records ?: emptyList()
            store.cleanupOrphans(records)
        } catch (e: Exception) {
        }
        for (listener in listeners) runtime.addChangeListener(listener)
        initialized = true
    }

    @JvmStatic
    fun isInitialized(): Boolean = initialized

    @JvmStatic
    fun getRuntime(): PluginRuntime? = if (initialized) runtime else null

    @JvmStatic
    fun getInstaller(): PluginInstaller? = if (initialized) installer else null

    @JvmStatic
    fun getStore(): PluginStore? = if (initialized) store else null

    /** Listeners fire on the main thread after every runtime reload. */
    fun addChangeListener(listener: Runnable) {
        listeners.add(listener)
        if (initialized) runtime.addChangeListener(listener)
    }

    fun removeChangeListener(listener: Runnable) {
        listeners.remove(listener)
        if (initialized) runtime.removeChangeListener(listener)
    }

    /** Runs [work] on the plugin executor and delivers its result on the main thread. */
    fun <T> async(work: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val result = try {
                Result.success(work())
            } catch (e: Exception) {
                Result.failure(e)
            }
            mainHandler.post { onResult(result) }
        }
    }

    data class BundledPlugin(val assetName: String, val bytes: ByteArray)

    /** Reads the plugin artifacts shipped in the APK (covered by the APK signature). */
    fun bundledPlugins(): List<BundledPlugin> {
        if (!initialized) return emptyList()
        val names = try {
            appContext.assets.list("plugins") ?: emptyArray()
        } catch (e: Exception) {
            emptyArray()
        }
        return names.filter { it.endsWith(".zip") }.sorted().mapNotNull { name ->
            try {
                val bytes = appContext.assets.open("plugins/$name").use { it.readBytes() }
                BundledPlugin(name, bytes)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun repositoryConfigured(): Boolean =
        BuildConfig.TERMUX_PLUGIN_REPOSITORY_URL.isNotEmpty() &&
            BuildConfig.TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY.isNotEmpty()

    fun newRepositoryClient(): PluginRepository =
        PluginRepository(BuildConfig.TERMUX_PLUGIN_REPOSITORY_URL, BuildConfig.TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY)
}
