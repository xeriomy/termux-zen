package com.termux.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One installed plugin as recorded in registry.json. */
data class PluginRecord(
    val id: String,
    val version: Semver,
    val enabled: Boolean,
    val source: String,
    val sha256: String,
    /** User overrides for the plugin's declared settings (string-encoded values). */
    val settings: Map<String, String>
)

/**
 * App-private plugin storage. Layout:
 *
 * ```
 * <root>/
 * ├── registry.json                     atomic writes; corrupt files are quarantined
 * ├── installed/<id>/<version>/plugin.json
 * └── .staging/                         cleared at startup
 * ```
 *
 * Pure java.io — usable from JVM unit tests without Android.
 */
class PluginStore(val root: File) {

    val installedDir = File(root, "installed")
    private val stagingDir = File(root, ".staging")
    private val registryFile = File(root, "registry.json")

    fun ensureDirectories() {
        if (!root.exists() && !root.mkdirs()) throw PluginException("cannot create plugin storage at $root")
        if (!installedDir.exists() && !installedDir.mkdirs())
            throw PluginException("cannot create plugin install directory")
    }

    fun clearStaging() {
        stagingDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- registry

    sealed class RegistryLoad {
        data class Ok(val records: List<PluginRecord>) : RegistryLoad()
        /** A v1 registry was found and should be migrated. */
        object LegacyV1 : RegistryLoad()
        object Empty : RegistryLoad()
    }

    fun loadRegistry(): RegistryLoad {
        if (!registryFile.exists()) return RegistryLoad.Empty
        val text = try {
            registryFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            quarantineRegistry("unreadable")
            return RegistryLoad.Empty
        }
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            quarantineRegistry("unparseable")
            return RegistryLoad.Empty
        }
        when (val schema = obj.opt("schemaVersion")) {
            1 -> return RegistryLoad.LegacyV1
            2 -> {}
            else -> {
                quarantineRegistry("schema-$schema")
                return RegistryLoad.Empty
            }
        }
        return try {
            val array = obj.getJSONArray("plugins")
            val records = ArrayList<PluginRecord>(array.length())
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val recordObj = array.getJSONObject(i)
                val id = recordObj.getString("id")
                val version = Semver.parseOrThrow(recordObj.getString("version"), "registry version")
                if (!ZenApi.isValidId(id)) throw PluginException("registry id \"$id\" is invalid")
                if (!seen.add(id)) throw PluginException("duplicate registry id \"$id\"")
                val enabled = recordObj.getBoolean("enabled")
                val source = recordObj.getString("source")
                if (source !in ZenApi.SOURCES) throw PluginException("registry source \"$source\" is invalid")
                val sha256 = recordObj.getString("sha256")
                if (!sha256.matches(Regex("^[0-9a-f]{64}$")))
                    throw PluginException("registry sha256 for \"$id\" is invalid")
                val settings = LinkedHashMap<String, String>()
                val settingsObj = recordObj.optJSONObject("settings")
                if (settingsObj != null) {
                    val keys = settingsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next() as String
                        val value = settingsObj.get(key)
                        if (value !is String || !ZenApi.SETTING_ID_REGEX.matches(key) || value.length > 512)
                            throw PluginException("registry settings for \"$id\" are invalid")
                        settings[key] = value
                    }
                }
                records.add(PluginRecord(id, version, enabled, source, sha256, settings))
            }
            RegistryLoad.Ok(records)
        } catch (e: Exception) {
            quarantineRegistry("invalid")
            RegistryLoad.Empty
        }
    }

    private fun quarantineRegistry(reason: String) {
        val target = File(root, "registry.json.corrupt-${System.currentTimeMillis()}-$reason")
        registryFile.renameTo(target)
    }

    /** Atomically persists records: tmp file, fsync, rename over the registry. */
    fun saveRegistry(records: List<PluginRecord>) {
        ensureDirectories()
        val obj = JSONObject()
        obj.put("schemaVersion", ZenApi.SCHEMA_VERSION)
        val array = JSONArray()
        for (record in records) {
            val recordObj = JSONObject()
            recordObj.put("id", record.id)
            recordObj.put("version", record.version.raw)
            recordObj.put("enabled", record.enabled)
            recordObj.put("source", record.source)
            recordObj.put("sha256", record.sha256)
            val settingsObj = JSONObject()
            for ((key, value) in record.settings) settingsObj.put(key, value)
            recordObj.put("settings", settingsObj)
            array.put(recordObj)
        }
        obj.put("plugins", array)
        val tmp = File(root, "registry.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(obj.toString(2).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(registryFile)) {
                // Some filesystems refuse rename-over-existing; fall back to delete+rename.
                registryFile.delete()
                if (!tmp.renameTo(registryFile)) throw PluginException("cannot commit registry.json")
            }
        } catch (e: PluginException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw PluginException("cannot write registry.json: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------- installed documents

    fun pluginDir(id: String, version: Semver): File {
        requirePathSafe(id)
        requirePathSafe(version.raw)
        return File(File(installedDir, id), version.raw)
    }

    fun pluginFile(id: String, version: Semver): File = File(pluginDir(id, version), "plugin.json")

    /** Reads the installed document and verifies its hash. Throws when missing or tampered. */
    fun readVerified(record: PluginRecord): String {
        val file = pluginFile(record.id, record.version)
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            throw PluginException("plugin \"${record.id}\" files are missing or unreadable", e)
        }
        if (sha256Hex(text.toByteArray(Charsets.UTF_8)) != record.sha256)
            throw PluginException("plugin \"${record.id}\" failed integrity verification")
        return text
    }

    /**
     * Writes a validated document through staging, then commits the version directory.
     * Returns the previous version directory moved aside (if any); the caller deletes it
     * after the registry commit succeeds, or restores it when the commit fails.
     */
    fun writeInstalled(id: String, version: Semver, text: String): File? {
        ensureDirectories()
        clearStaging()
        if (!stagingDir.mkdirs() && !stagingDir.exists())
            throw PluginException("cannot create staging directory")
        val staged = File(stagingDir, "commit")
        if (!staged.mkdirs()) throw PluginException("cannot create staging commit directory")
        val stagedFile = File(staged, "plugin.json")
        FileOutputStream(stagedFile).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        val targetDir = pluginDir(id, version)
        targetDir.parentFile?.mkdirs()
        var movedAside: File? = null
        if (targetDir.exists()) {
            movedAside = File(targetDir.parentFile, "${version.raw}.old-${System.currentTimeMillis()}")
            if (!targetDir.renameTo(movedAside))
                throw PluginException("cannot move existing version directory aside")
        }
        if (!staged.renameTo(targetDir)) {
            movedAside?.renameTo(targetDir)
            throw PluginException("cannot commit plugin directory")
        }
        clearStaging()
        return movedAside
    }

    /** Deletes every version directory of a plugin, with canonical-path containment. */
    fun deletePluginFiles(id: String) {
        requirePathSafe(id)
        val dir = File(installedDir, id)
        if (!dir.exists()) return
        val canonicalRoot = installedDir.canonicalFile
        val canonicalDir = dir.canonicalFile
        if (!canonicalDir.path.startsWith(canonicalRoot.path + File.separator))
            throw PluginException("refusing to delete outside plugin storage")
        if (!dir.deleteRecursively())
            throw PluginException("cannot delete plugin files for \"$id\"")
    }

    /** Removes version directories not referenced by the registry (orphans, `.old-*`). */
    fun cleanupOrphans(records: List<PluginRecord>) {
        val keep = records.map { it.id to it.version.raw }.toSet()
        val pluginDirs = installedDir.listFiles() ?: return
        for (dir in pluginDirs) {
            if (!dir.isDirectory) continue
            val versions = dir.listFiles() ?: continue
            for (versionDir in versions) {
                if ((dir.name to versionDir.name) !in keep) versionDir.deleteRecursively()
            }
            if (dir.listFiles()?.isEmpty() == true) dir.delete()
        }
    }

    private fun requirePathSafe(segment: String) {
        if (segment.isEmpty() || segment.contains('/') || segment.contains('\\') ||
            segment.contains("..") || segment == "." || segment != File(segment).name
        ) throw PluginException("unsafe path segment \"$segment\"")
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val builder = StringBuilder(digest.size * 2)
            for (b in digest) {
                val value = b.toInt() and 0xff
                if (value < 16) builder.append('0')
                builder.append(Integer.toHexString(value))
            }
            return builder.toString()
        }
    }
}
