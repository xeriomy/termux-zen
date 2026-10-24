package com.termux.app.plugins;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates, stages, and atomically installs declarative plugin artifacts. No archive content is
 * ever extracted: only the two validated JSON documents are written. An update keeps the previous
 * version until the registry commit succeeds, so a failure rolls back.
 */
public final class PluginInstaller {

    public static final String SOURCE_BUNDLED = "bundled";
    public static final String SOURCE_REPOSITORY = "repository";
    public static final String BUNDLED_ASSET_DIR = "plugins";

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_PLUGIN_JSON_BYTES = 128 * 1024;

    private final File mPluginsDir;
    private final PluginRegistry mRegistry;
    private final String mCoreVersion;

    public PluginInstaller(File pluginsDir, PluginRegistry registry, String coreVersion) {
        mPluginsDir = pluginsDir;
        mRegistry = registry;
        mCoreVersion = coreVersion;
        clearStaging();
    }

    /** Installs bundled samples that are not installed yet. Returns the number installed. */
    public int installBundledSamples(AssetManager assets) throws PluginException {
        String[] names;
        try {
            names = assets.list(BUNDLED_ASSET_DIR);
        } catch (IOException e) {
            throw new PluginException("Could not list bundled plugins", e);
        }
        if (names == null) return 0;

        int installed = 0;
        Arrays.sort(names);
        for (String name : names) {
            if (!name.endsWith(".zip")) continue;
            byte[] bytes = readAsset(assets, BUNDLED_ASSET_DIR + "/" + name);
            PluginArchive archive = PluginArchive.read(bytes);
            PluginManifest manifest = PluginManifest.parse(
                new String(archive.manifestJson, UTF_8), mCoreVersion);
            if (mRegistry.find(manifest.id) != null) continue;
            install(bytes, null, -1, SOURCE_BUNDLED, false);
            installed++;
        }
        return installed;
    }

    /**
     * Installs one artifact. Repository callers must verify the signed index first and pass the
     * index's expected SHA-256 and size. Bundled assets pass null/-1 and are trusted by the APK
     * signature, exactly like the rest of the APK.
     */
    public PluginRegistry.Entry install(byte[] bytes, String expectedSha256, long expectedSize,
                                        String source, boolean enable) throws PluginException {
        if (!SOURCE_BUNDLED.equals(source) && !SOURCE_REPOSITORY.equals(source))
            throw new PluginException("Unknown plugin source: " + source);
        if (expectedSize >= 0 && bytes != null && bytes.length != expectedSize)
            throw new PluginException("Plugin artifact size does not match the repository index");

        mRegistry.load();
        PluginArchive archive = PluginArchive.read(bytes);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(archive.sha256))
            throw new PluginException("Plugin artifact checksum does not match the repository index");

        PluginManifest manifest = PluginManifest.parse(
            new String(archive.manifestJson, UTF_8), mCoreVersion);
        PluginPayload payload = PluginPayload.parse(
            new String(archive.payloadJson, UTF_8), manifest);

        PluginRegistry.Entry existing = mRegistry.find(manifest.id);
        if (existing != null
            && PluginApi.compareVersions(manifest.version, existing.version) < 0)
            throw new PluginException("Downgrades are not allowed: installed " + existing.version
                + ", artifact " + manifest.version);

        boolean keepEnabled = existing != null && existing.enabled;
        boolean willBeEnabled = enable || keepEnabled;
        checkDependencies(manifest, willBeEnabled);
        checkForDependencyCycle(manifest);
        if (willBeEnabled) checkExclusiveDependencyConflict(manifest);

        String manifestSha256 = PluginArchive.sha256(archive.manifestJson);
        String payloadSha256 = PluginArchive.sha256(archive.payloadJson);

        File pluginDir = new File(mRegistry.getInstalledDir(), manifest.id);
        File finalDir = new File(pluginDir, manifest.version);
        File stagingDir = new File(mPluginsDir, ".staging/" + manifest.id + "-"
            + manifest.version + "-" + System.nanoTime());

        File previousDir = null;
        try {
            if (!stagingDir.mkdirs())
                throw new PluginException("Could not create plugin staging directory");
            writeFile(new File(stagingDir, "manifest.json"), archive.manifestJson);
            writeFile(new File(stagingDir, "payload.json"), archive.payloadJson);

            if (!pluginDir.isDirectory() && !pluginDir.mkdirs())
                throw new PluginException("Could not create plugin directory");
            if (finalDir.exists()) {
                previousDir = new File(pluginDir, manifest.version + ".old-" + System.nanoTime());
                if (!finalDir.renameTo(previousDir))
                    throw new PluginException("Could not stage plugin update");
            }
            if (!stagingDir.renameTo(finalDir)) {
                restoreDirectory(previousDir, finalDir);
                throw new PluginException("Could not commit plugin files");
            }

            mRegistry.put(new PluginRegistry.Entry(manifest.id, manifest.version, manifest.type,
                willBeEnabled, source, manifestSha256, payloadSha256));
            if (willBeEnabled) disableConflicting(manifest.type, manifest.id);

            try {
                mRegistry.save();
            } catch (PluginException e) {
                mRegistry.load();
                deleteRecursively(finalDir, mRegistry.getInstalledDir());
                restoreDirectory(previousDir, finalDir);
                throw e;
            }
        } finally {
            deleteRecursively(stagingDir, mPluginsDir);
        }

        if (previousDir != null) deleteRecursively(previousDir, mRegistry.getInstalledDir());
        deleteOldVersionDirectories(pluginDir, manifest.version);
        return mRegistry.find(manifest.id);
    }

    /** Enables or disables an installed plugin after re-validating it from disk. */
    public void setEnabled(String id, boolean enabled) throws PluginException {
        mRegistry.load();
        PluginRegistry.Entry entry = mRegistry.find(id);
        if (entry == null) throw new PluginException("Plugin is not installed: " + id);

        if (enabled) {
            ValidatedPlugin validated = validate(entry);
            checkDependencies(validated.manifest, true);
            checkForDependencyCycle(validated.manifest);
            checkExclusiveDependencyConflict(validated.manifest);
            entry.enabled = true;
            disableConflicting(entry.type, entry.id);
        } else {
            entry.enabled = false;
            disableDependents(entry.id);
        }

        try {
            mRegistry.save();
        } catch (PluginException e) {
            mRegistry.load();
            throw e;
        }
    }

    /** Removes the registration and plugin files only. Nothing outside the plugin directory is touched. */
    public void uninstall(String id) throws PluginException {
        mRegistry.load();
        if (mRegistry.find(id) == null) throw new PluginException("Plugin is not installed: " + id);
        disableDependents(id);
        mRegistry.remove(id);
        mRegistry.save();

        File pluginDir = new File(mRegistry.getInstalledDir(), id);
        if (pluginDir.exists() && !deleteRecursively(pluginDir, mRegistry.getInstalledDir())) {
            throw new PluginException("Plugin was unregistered, but some files could not be deleted");
        }
    }

    public ValidatedPlugin validate(PluginRegistry.Entry entry) throws PluginException {
        File dir = new File(mRegistry.getInstalledDir(), entry.id + "/" + entry.version);
        File manifestFile = new File(dir, "manifest.json");
        File payloadFile = new File(dir, "payload.json");
        byte[] manifestBytes = readFile(manifestFile, MAX_PLUGIN_JSON_BYTES);
        byte[] payloadBytes = readFile(payloadFile, MAX_PLUGIN_JSON_BYTES);

        if (!PluginArchive.sha256(manifestBytes).equals(entry.manifestSha256)
            || !PluginArchive.sha256(payloadBytes).equals(entry.payloadSha256))
            throw new PluginException("Installed plugin files were modified after installation");

        PluginManifest manifest = PluginManifest.parse(
            new String(manifestBytes, UTF_8), mCoreVersion);
        if (!manifest.id.equals(entry.id) || !manifest.version.equals(entry.version)
            || !manifest.type.equals(entry.type))
            throw new PluginException("Installed plugin does not match its registry record");
        PluginPayload payload = PluginPayload.parse(
            new String(payloadBytes, UTF_8), manifest);
        return new ValidatedPlugin(entry, manifest, payload);
    }

    /** Returns every enabled plugin that still validates and whose dependencies are enabled. */
    public List<ValidatedPlugin> getValidatedEnabled() {
        mRegistry.load();
        Map<String, ValidatedPlugin> enabled = new java.util.LinkedHashMap<>();
        for (PluginRegistry.Entry entry : mRegistry.entries()) {
            if (!entry.enabled) continue;
            try {
                enabled.put(entry.id, validate(entry));
            } catch (PluginException e) {
                // Fail closed: a plugin that no longer validates contributes nothing.
            }
        }

        List<ValidatedPlugin> result = new ArrayList<>();
        for (ValidatedPlugin plugin : enabled.values()) {
            boolean usable = true;
            for (PluginManifest.Dependency dependency : plugin.manifest.dependencies) {
                ValidatedPlugin installed = enabled.get(dependency.id);
                if (installed == null
                    || PluginApi.compareVersions(installed.manifest.version,
                        dependency.minVersion) < 0) {
                    usable = false;
                    break;
                }
            }
            if (usable) result.add(plugin);
        }
        return result;
    }

    public Map<String, String> getValidationErrors() {
        Map<String, String> errors = new HashMap<>();
        for (PluginRegistry.Entry entry : mRegistry.entries()) {
            try {
                validate(entry);
            } catch (PluginException e) {
                errors.put(entry.id, e.getMessage());
            }
        }
        return errors;
    }

    private void checkDependencies(PluginManifest manifest, boolean requireEnabled)
        throws PluginException {
        for (PluginManifest.Dependency dependency : manifest.dependencies) {
            PluginRegistry.Entry installed = mRegistry.find(dependency.id);
            if (installed == null)
                throw new PluginException("Missing dependency: " + dependency.id);
            if (PluginApi.compareVersions(installed.version, dependency.minVersion) < 0)
                throw new PluginException("Dependency " + dependency.id + " must be at least "
                    + dependency.minVersion + "; installed " + installed.version);
            if (requireEnabled && !installed.enabled)
                throw new PluginException("Dependency " + dependency.id + " is disabled");
            validate(installed);
        }
    }

    private void checkForDependencyCycle(PluginManifest candidate) throws PluginException {
        Map<String, PluginManifest> manifests = new HashMap<>();
        for (PluginRegistry.Entry entry : mRegistry.entries()) {
            try {
                manifests.put(entry.id, validate(entry).manifest);
            } catch (PluginException e) {
                // An invalid plugin is not loadable, so it cannot make the candidate usable.
            }
        }
        manifests.put(candidate.id, candidate);
        visitDependencies(candidate.id, manifests, new HashSet<String>(), new HashSet<String>());
    }

    private void visitDependencies(String id, Map<String, PluginManifest> manifests,
                                   Set<String> visiting, Set<String> visited) throws PluginException {
        if (visited.contains(id)) return;
        if (!visiting.add(id))
            throw new PluginException("Dependency cycle detected involving " + id);
        PluginManifest manifest = manifests.get(id);
        if (manifest != null) {
            for (PluginManifest.Dependency dependency : manifest.dependencies) {
                visitDependencies(dependency.id, manifests, visiting, visited);
            }
        }
        visiting.remove(id);
        visited.add(id);
    }

    private void checkExclusiveDependencyConflict(PluginManifest manifest) throws PluginException {
        if (!isExclusive(manifest.type)) return;
        for (PluginManifest.Dependency dependency : manifest.dependencies) {
            PluginRegistry.Entry installed = mRegistry.find(dependency.id);
            if (installed != null && installed.enabled && installed.type.equals(manifest.type))
                throw new PluginException("Cannot enable " + manifest.id + " because it depends on "
                    + dependency.id + ", which would be replaced as the active "
                    + manifest.type + " plugin");
        }
    }

    private void disableConflicting(String type, String keepId) {
        if (!isExclusive(type)) return;
        List<String> disabled = new ArrayList<>();
        for (PluginRegistry.Entry entry : mRegistry.entries()) {
            if (!entry.id.equals(keepId) && entry.type.equals(type) && entry.enabled) {
                entry.enabled = false;
                disabled.add(entry.id);
            }
        }
        for (String id : disabled) disableDependents(id);
    }

    /** Disables plugins whose enabled dependencies include {@code id}, transitively. */
    private void disableDependents(String id) {
        boolean changed;
        do {
            changed = false;
            for (PluginRegistry.Entry entry : mRegistry.entries()) {
                if (!entry.enabled || entry.id.equals(id)) continue;
                try {
                    PluginManifest manifest = validate(entry).manifest;
                    for (PluginManifest.Dependency dependency : manifest.dependencies) {
                        if (dependency.id.equals(id)) {
                            entry.enabled = false;
                            changed = true;
                            break;
                        }
                    }
                } catch (PluginException e) {
                    // Invalid plugins are not active anyway.
                }
            }
        } while (changed);
    }

    private static boolean isExclusive(String type) {
        return PluginApi.TYPE_THEME.equals(type) || PluginApi.TYPE_EXTRA_KEYS.equals(type);
    }

    private void deleteOldVersionDirectories(File pluginDir, String keepVersion) {
        File[] children = pluginDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().equals(keepVersion))
                deleteRecursively(child, mRegistry.getInstalledDir());
        }
    }

    private void restoreDirectory(File previousDir, File finalDir) {
        if (previousDir != null && previousDir.exists() && !finalDir.exists()) {
            previousDir.renameTo(finalDir);
        }
    }

    private void clearStaging() {
        File staging = new File(mPluginsDir, ".staging");
        deleteRecursively(staging, mPluginsDir);
        staging.mkdirs();
    }

    private static byte[] readAsset(AssetManager assets, String name) throws PluginException {
        try (java.io.InputStream in = assets.open(name)) {
            return readBounded(in, PluginArchive.MAX_ARTIFACT_BYTES, name);
        } catch (IOException e) {
            throw new PluginException("Could not read bundled plugin: " + name, e);
        }
    }

    private static byte[] readFile(File file, int maxBytes) throws PluginException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readBounded(in, maxBytes, file.getName());
        } catch (IOException e) {
            throw new PluginException("Could not read installed plugin file: " + file.getName(), e);
        }
    }

    private static byte[] readBounded(java.io.InputStream in, int maxBytes, String name)
        throws IOException, PluginException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes)
                throw new PluginException("Plugin file is too large: " + name);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void writeFile(File file, byte[] bytes) throws PluginException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            throw new PluginException("Could not write plugin file: " + file.getName(), e);
        }
    }

    /**
     * Deletes only inside {@code root}. Directory symlinks are not followed, so an entry created
     * by another app in the same UID cannot redirect deletion outside plugin storage.
     */
    private static boolean deleteRecursively(File file, File root) {
        if (!file.exists()) return true;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            boolean insideRoot = canonicalFile.equals(canonicalRoot)
                || canonicalFile.getPath().startsWith(canonicalRoot.getPath() + File.separator);
            if (file.isDirectory() && insideRoot) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) deleteRecursively(child, root);
                }
            }
        } catch (IOException e) {
            return false;
        }
        return file.delete();
    }

    public static final class ValidatedPlugin {
        public final PluginRegistry.Entry entry;
        public final PluginManifest manifest;
        public final PluginPayload payload;

        ValidatedPlugin(PluginRegistry.Entry entry, PluginManifest manifest, PluginPayload payload) {
            this.entry = entry;
            this.manifest = manifest;
            this.payload = payload;
        }
    }

}
