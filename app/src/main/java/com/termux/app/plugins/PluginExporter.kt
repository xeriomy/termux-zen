package com.termux.app.plugins

import android.content.Context
import java.io.File

/** Writes a shareable ZIP artifact for a plugin into the app cache (shared via FileProvider). */
object PluginExporter {

    fun exportToCache(context: Context, manifest: PluginManifest): File {
        val dir = File(context.cacheDir, "zen-exports")
        if (!dir.exists() && !dir.mkdirs()) throw PluginException("cannot create export directory")
        val file = File(dir, "${manifest.id}-${manifest.version.raw}.zip")
        file.writeBytes(PluginArchive.write(PluginParser.toJson(manifest)))
        return file
    }
}
