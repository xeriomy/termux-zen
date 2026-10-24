package com.termux.app.plugins;

import android.content.Context;

import com.termux.BuildConfig;

import java.io.File;

/**
 * Process-wide plugin state. Initialized once in {@link com.termux.app.TermuxApplication}, before
 * any activity. All plugin state lives under {@code getFilesDir()/plugins}; nothing is written to
 * {@code $HOME}, {@code $PREFIX}, or {@code .termux}.
 */
public final class PluginManager {

    private static volatile PluginManager sInstance;

    private final PluginRegistry mRegistry;
    private final PluginInstaller mInstaller;
    private final PluginRuntime mRuntime;

    private PluginManager(PluginRegistry registry, PluginInstaller installer, PluginRuntime runtime) {
        mRegistry = registry;
        mInstaller = installer;
        mRuntime = runtime;
    }

    public static synchronized void init(Context context) {
        if (sInstance != null) return;
        File pluginsDir = new File(context.getFilesDir(), "plugins");
        if (!pluginsDir.isDirectory()) pluginsDir.mkdirs();

        PluginRegistry registry = new PluginRegistry(pluginsDir);
        registry.load();
        PluginInstaller installer = new PluginInstaller(pluginsDir, registry,
            BuildConfig.VERSION_NAME);
        PluginRuntime runtime = new PluginRuntime(installer);
        runtime.reload();
        sInstance = new PluginManager(registry, installer, runtime);
    }

    public static PluginManager getInstance() {
        return sInstance;
    }

    public static PluginRuntime getRuntime() {
        PluginManager manager = sInstance;
        return manager == null ? null : manager.mRuntime;
    }

    /** Re-reads registry and installed files after an install, enable, disable, or uninstall. */
    public void refresh() {
        mRuntime.reload();
    }

    public PluginRegistry getRegistry() {
        return mRegistry;
    }

    public PluginInstaller getInstaller() {
        return mInstaller;
    }

}
