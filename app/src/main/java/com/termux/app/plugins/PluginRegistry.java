package com.termux.app.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Durable install state, stored in app-private storage. The file is written with a temporary file
 * and an atomic rename, and is treated as untrusted input every time it is read.
 */
public final class PluginRegistry {

    private static final int MAX_REGISTRY_BYTES = 256 * 1024;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Set<String> FIELDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("schemaVersion", "plugins")));
    private static final Set<String> ENTRY_FIELDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("id", "version", "type", "enabled", "source", "manifestSha256", "payloadSha256")));

    private final File mPluginsDir;
    private final File mRegistryFile;
    private final List<Entry> mEntries = new ArrayList<>();
    private String mLastError;

    public PluginRegistry(File pluginsDir) {
        mPluginsDir = pluginsDir;
        mRegistryFile = new File(pluginsDir, "registry.json");
    }

    public File getPluginsDir() {
        return mPluginsDir;
    }

    public File getInstalledDir() {
        return new File(mPluginsDir, "installed");
    }

    public String getLastError() {
        return mLastError;
    }

    public synchronized void load() {
        mEntries.clear();
        mLastError = null;
        if (!mRegistryFile.isFile()) return;
        try {
            byte[] bytes = readBounded(mRegistryFile, MAX_REGISTRY_BYTES);
            JSONObject root = PluginJson.parseObject(new String(bytes, UTF_8), "Registry");
            PluginJson.rejectUnknownFields(root, FIELDS);
            if (PluginJson.requiredInt(root, "schemaVersion") != PluginApi.SCHEMA_VERSION)
                throw new PluginException("Unsupported registry schema version");
            Object pluginsValue = root.opt("plugins");
            if (!(pluginsValue instanceof JSONArray))
                throw new PluginException("Registry plugins must be an array");
            JSONArray plugins = (JSONArray) pluginsValue;
            for (int i = 0; i < plugins.length(); i++) {
                Object value = plugins.opt(i);
                if (!(value instanceof JSONObject)) continue;
                try {
                    Entry entry = parseEntry((JSONObject) value);
                    if (find(entry.id) == null) mEntries.add(entry);
                } catch (PluginException e) {
                    // A single corrupt record does not make valid records unusable. It is ignored,
                    // and its plugin directory is never loaded.
                }
            }
        } catch (Exception e) {
            mEntries.clear();
            mLastError = "Could not read plugin registry: " + e.getMessage();
            quarantineCorruptRegistry();
        }
    }

    /** Keeps the unreadable file for recovery instead of overwriting it on the next save. */
    private void quarantineCorruptRegistry() {
        if (!mRegistryFile.isFile()) return;
        File backup = new File(mPluginsDir, "registry.json.corrupt-" + System.currentTimeMillis());
        mRegistryFile.renameTo(backup);
    }

    private static Entry parseEntry(JSONObject object) throws PluginException {
        PluginJson.rejectUnknownFields(object, ENTRY_FIELDS);
        String id = PluginJson.requiredString(object, "id");
        if (!PluginApi.isValidId(id)) throw new PluginException("Invalid registry id");
        String version = PluginJson.requiredString(object, "version");
        if (!PluginApi.isValidVersion(version)) throw new PluginException("Invalid registry version");
        String type = PluginJson.requiredString(object, "type");
        if (!PluginApi.isSupportedType(type)) throw new PluginException("Invalid registry type");
        Object enabledValue = object.opt("enabled");
        if (!(enabledValue instanceof Boolean)) throw new PluginException("Invalid registry enabled flag");
        String source = PluginJson.requiredString(object, "source");
        if (!PluginInstaller.SOURCE_BUNDLED.equals(source)
            && !PluginInstaller.SOURCE_REPOSITORY.equals(source))
            throw new PluginException("Invalid registry source");
        String manifestSha256 = PluginJson.requiredString(object, "manifestSha256");
        String payloadSha256 = PluginJson.requiredString(object, "payloadSha256");
        if (!isSha256(manifestSha256) || !isSha256(payloadSha256))
            throw new PluginException("Invalid registry hash");
        return new Entry(id, version, type, (Boolean) enabledValue, source, manifestSha256, payloadSha256);
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    public synchronized Entry find(String id) {
        for (Entry entry : mEntries) {
            if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    public synchronized List<Entry> entries() {
        List<Entry> copy = new ArrayList<>(mEntries);
        Collections.sort(copy, new Comparator<Entry>() {
            @Override
            public int compare(Entry left, Entry right) {
                return left.id.compareTo(right.id);
            }
        });
        return copy;
    }

    synchronized void put(Entry entry) {
        for (int i = 0; i < mEntries.size(); i++) {
            if (mEntries.get(i).id.equals(entry.id)) {
                mEntries.set(i, entry);
                return;
            }
        }
        mEntries.add(entry);
    }

    synchronized void remove(String id) {
        Iterator<Entry> iterator = mEntries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id.equals(id)) iterator.remove();
        }
    }

    public synchronized void save() throws PluginException {
        JSONArray plugins = new JSONArray();
        for (Entry entry : entries()) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", entry.id);
                object.put("version", entry.version);
                object.put("type", entry.type);
                object.put("enabled", entry.enabled);
                object.put("source", entry.source);
                object.put("manifestSha256", entry.manifestSha256);
                object.put("payloadSha256", entry.payloadSha256);
                plugins.put(object);
            } catch (Exception e) {
                throw new PluginException("Could not serialize plugin registry", e);
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("schemaVersion", PluginApi.SCHEMA_VERSION);
            root.put("plugins", plugins);
        } catch (Exception e) {
            throw new PluginException("Could not serialize plugin registry", e);
        }

        File parent = mRegistryFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new PluginException("Could not create plugin storage directory");
        File temporary = new File(parent, "registry.json.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(root.toString().getBytes(UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (!temporary.renameTo(mRegistryFile)) {
                // POSIX rename replaces an existing file. This fallback only helps on filesystems
                // where it does not.
                if (mRegistryFile.exists() && !mRegistryFile.delete())
                    throw new IOException("Could not replace registry file");
                if (!temporary.renameTo(mRegistryFile))
                    throw new IOException("Could not commit registry file");
            }
        } catch (IOException e) {
            temporary.delete();
            throw new PluginException("Could not write plugin registry: " + e.getMessage(), e);
        }
    }

    private static byte[] readBounded(File file, int maxBytes) throws IOException, PluginException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > maxBytes)
                    throw new PluginException("File is too large: " + file.getName());
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    public static final class Entry {
        public final String id;
        public final String version;
        public final String type;
        public boolean enabled;
        public final String source;
        public final String manifestSha256;
        public final String payloadSha256;

        Entry(String id, String version, String type, boolean enabled, String source,
              String manifestSha256, String payloadSha256) {
            this.id = id;
            this.version = version;
            this.type = type;
            this.enabled = enabled;
            this.source = source;
            this.manifestSha256 = manifestSha256;
            this.payloadSha256 = payloadSha256;
        }
    }

}
