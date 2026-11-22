package com.termux.app.plugins

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Bounded, in-memory ZIP handling for plugin artifacts. A v2 artifact contains exactly
 * `plugin.json` and optionally `LICENSE`. Nothing is ever extracted to disk from an
 * untrusted archive: the reader returns the validated document text only.
 */
object PluginArchive {

    /** Reads and bounds-checks an artifact, returning the plugin.json text. */
    fun readPluginJson(bytes: ByteArray): String {
        if (bytes.size > ZenApi.MAX_ARCHIVE_BYTES)
            throw PluginException("plugin artifact exceeds ${ZenApi.MAX_ARCHIVE_BYTES} bytes")
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte() ||
            bytes[2] != 0x03.toByte() || bytes[3] != 0x04.toByte()
        ) throw PluginException("artifact is not a readable ZIP")
        var pluginJson: String? = null
        var totalExpanded = 0
        val seen = HashSet<String>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry: ZipEntry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) throw PluginException("archive contains a directory entry")
                    if (name.contains('\\') || name.contains("..") || name.startsWith("/"))
                        throw PluginException("archive entry name \"$name\" is not path-safe")
                    if (name != "plugin.json" && name != "LICENSE")
                        throw PluginException("archive contains unexpected entry \"$name\"")
                    if (!seen.add(name)) throw PluginException("archive contains duplicate entry \"$name\"")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        totalExpanded += read
                        if (totalExpanded > ZenApi.MAX_PLUGIN_JSON_BYTES)
                            throw PluginException("archive expands beyond ${ZenApi.MAX_PLUGIN_JSON_BYTES} bytes")
                        out.write(buffer, 0, read)
                    }
                    if (name == "plugin.json") {
                        if (out.size() > ZenApi.MAX_PLUGIN_JSON_BYTES)
                            throw PluginException("plugin.json exceeds ${ZenApi.MAX_PLUGIN_JSON_BYTES} bytes")
                        pluginJson = out.toString("UTF-8")
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: PluginException) {
            throw e
        } catch (e: Exception) {
            throw PluginException("artifact is not a readable ZIP: ${e.message}", e)
        }
        return pluginJson ?: throw PluginException("artifact is missing plugin.json")
    }

    /** Builds a shareable artifact for a plugin document. */
    fun write(pluginJsonText: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write(pluginJsonText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
