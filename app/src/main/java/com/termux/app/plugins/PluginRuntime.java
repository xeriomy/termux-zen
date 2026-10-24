package com.termux.app.plugins;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Resolves active plugins for the app. It fails closed: a plugin that does not validate on load is
 * ignored. User configuration always wins: a plugin theme is applied only when the user has no
 * {@code colors.properties}, and extra keys only when the user did not set {@code extra-keys}.
 */
public final class PluginRuntime {

    private final PluginInstaller mInstaller;

    private volatile int mGeneration;
    private volatile PluginInstaller.ValidatedPlugin mTheme;
    private volatile PluginInstaller.ValidatedPlugin mExtraKeys;
    private volatile List<SessionPreset> mSessionPresets = Collections.emptyList();

    PluginRuntime(PluginInstaller installer) {
        mInstaller = installer;
    }

    public synchronized void reload() {
        PluginInstaller.ValidatedPlugin theme = null;
        PluginInstaller.ValidatedPlugin extraKeys = null;
        List<SessionPreset> presets = new ArrayList<>();

        for (PluginInstaller.ValidatedPlugin plugin : mInstaller.getValidatedEnabled()) {
            try {
                PluginPermissionManager.verify(plugin.manifest.type, plugin.manifest.capabilities);
                if (PluginApi.TYPE_THEME.equals(plugin.manifest.type) && theme == null) {
                    theme = plugin;
                } else if (PluginApi.TYPE_EXTRA_KEYS.equals(plugin.manifest.type) && extraKeys == null) {
                    extraKeys = plugin;
                } else if (PluginApi.TYPE_SESSION_PRESET.equals(plugin.manifest.type)) {
                    SessionPreset preset = resolvePreset(plugin);
                    if (preset != null) presets.add(preset);
                }
            } catch (PluginException e) {
                // Fail closed per plugin.
            }
        }

        mTheme = theme;
        mExtraKeys = extraKeys;
        mSessionPresets = Collections.unmodifiableList(presets);
        mGeneration++;
    }

    public int getGeneration() {
        return mGeneration;
    }

    /**
     * Theme properties to apply, or null when the user has their own colors file or no theme is
     * enabled. The caller loads user colors first, so user configuration always wins.
     */
    public Properties getThemeProperties() {
        PluginInstaller.ValidatedPlugin theme = mTheme;
        if (theme == null) return null;
        if (TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE.isFile()) return null;
        Properties copy = new Properties();
        copy.putAll(theme.payload.themeProperties);
        return copy;
    }

    public String getExtraKeysJson() {
        PluginInstaller.ValidatedPlugin extraKeys = mExtraKeys;
        return extraKeys == null ? null : extraKeys.payload.extraKeysJson;
    }

    public String getExtraKeysStyle() {
        PluginInstaller.ValidatedPlugin extraKeys = mExtraKeys;
        return extraKeys == null ? null : extraKeys.payload.extraKeysStyle;
    }

    public List<SessionPreset> getSessionPresets() {
        return mSessionPresets;
    }

    private static SessionPreset resolvePreset(PluginInstaller.ValidatedPlugin plugin) {
        try {
            File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            String canonicalHome = home.getCanonicalPath();
            String relative = plugin.payload.presetWorkingDirectory;
            File directory = relative.isEmpty() || ".".equals(relative)
                ? home : new File(home, relative);
            String canonicalDirectory = directory.getCanonicalPath();
            boolean insideHome = canonicalDirectory.equals(canonicalHome)
                || canonicalDirectory.startsWith(canonicalHome + File.separator);
            if (!insideHome || !directory.isDirectory()) return null;
            return new SessionPreset(plugin.manifest.id, plugin.payload.presetName,
                canonicalDirectory, relative);
        } catch (IOException e) {
            return null;
        }
    }

    public static final class SessionPreset {
        public final String pluginId;
        public final String name;
        public final String workingDirectory;
        public final String relativeWorkingDirectory;

        SessionPreset(String pluginId, String name, String workingDirectory,
                      String relativeWorkingDirectory) {
            this.pluginId = pluginId;
            this.name = name;
            this.workingDirectory = workingDirectory;
            this.relativeWorkingDirectory = relativeWorkingDirectory;
        }
    }

}
