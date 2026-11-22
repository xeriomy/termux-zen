package com.termux.app.plugins.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.termux.app.plugins.PluginManager

/**
 * Zen plugin hub: a single Compose activity hosting the installed list, catalog browsing,
 * per-plugin detail/settings and the plugin editor. Replaces the v1 XML tab manager.
 */
class PluginHubActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZenTheme {
                val controller = remember { HubController(this) }
                DisposableEffect(controller) {
                    val listener = Runnable { controller.refresh() }
                    PluginManager.addChangeListener(listener)
                    controller.refresh()
                    onDispose { PluginManager.removeChangeListener(listener) }
                }
                BackHandler {
                    if (!controller.goBack()) finish()
                }
                PluginHubApp(controller)
            }
        }
    }
}
