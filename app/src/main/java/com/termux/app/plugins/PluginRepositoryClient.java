package com.termux.app.plugins;

import org.json.JSONArray;
import org.json.JSONObject;

import com.termux.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HTTPS catalog client. The catalog and its size/checksums are authenticated by a signature over
 * the exact index bytes, verified against a public key compiled into the APK. TLS alone is not
 * treated as publisher authenticity.
 */
public final class PluginRepositoryClient {

    public static final int MAX_INDEX_BYTES = 256 * 1024;
    public static final int MAX_SIGNATURE_BYTES = 8 * 1024;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final Set<String> ROOT_FIELDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("schemaVersion", "repository", "plugins")));
    private static final Set<String> ENTRY_FIELDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("id", "name", "version", "type", "description", "minCoreVersion",
            "dependencies", "url", "sha256", "size")));

    private PluginRepositoryClient() {
    }

    public static boolean isConfigured() {
        return !BuildConfig.TERMUX_PLUGIN_REPOSITORY_URL.trim().isEmpty()
            && !BuildConfig.TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY.trim().isEmpty();
    }

    /** Fetches, parses, and verifies the signed index. */
    public static List<CatalogEntry> fetchCatalog() throws PluginException {
        if (!isConfigured())
            throw new PluginException("Plugin repository is not configured in this build");

        String base = BuildConfig.TERMUX_PLUGIN_REPOSITORY_URL;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        byte[] index = fetch(base + "/index.json", MAX_INDEX_BYTES);
        byte[] signatureFile = fetch(base + "/index.json.sig", MAX_SIGNATURE_BYTES);
        byte[] signature;
        try {
            signature = PluginSignatureVerifier.decodeBase64(new String(signatureFile, UTF_8));
        } catch (PluginException e) {
            throw new PluginException("Repository signature file is not valid Base64", e);
        }
        if (!PluginSignatureVerifier.verify(index, signature,
            BuildConfig.TERMUX_PLUGIN_REPOSITORY_PUBLIC_KEY))
            throw new PluginException("Repository signature verification failed");

        return parseIndex(index);
    }

    static List<CatalogEntry> parseIndex(byte[] bytes) throws PluginException {
        JSONObject root = PluginJson.parseObject(new String(bytes, UTF_8), "Repository index");
        PluginJson.rejectUnknownFields(root, ROOT_FIELDS);
        if (PluginJson.requiredInt(root, "schemaVersion") != PluginApi.SCHEMA_VERSION)
            throw new PluginException("Unsupported repository index schema version");
        PluginJson.requiredString(root, "repository");

        Object pluginsValue = root.opt("plugins");
        if (!(pluginsValue instanceof JSONArray))
            throw new PluginException("Repository plugins must be an array");
        JSONArray plugins = (JSONArray) pluginsValue;
        if (plugins.length() > 500)
            throw new PluginException("Repository index has too many plugins");

        List<CatalogEntry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < plugins.length(); i++) {
            Object value = plugins.opt(i);
            if (!(value instanceof JSONObject))
                throw new PluginException("Repository entry must be an object");
            CatalogEntry entry = parseEntry((JSONObject) value);
            if (!ids.add(entry.id))
                throw new PluginException("Duplicate repository plugin id: " + entry.id);
            entries.add(entry);
        }
        return entries;
    }

    private static CatalogEntry parseEntry(JSONObject object) throws PluginException {
        PluginJson.rejectUnknownFields(object, ENTRY_FIELDS);
        String id = PluginJson.requiredString(object, "id");
        if (!PluginApi.isValidId(id)) throw new PluginException("Invalid repository plugin id");
        String name = PluginJson.requiredString(object, "name");
        if (name.isEmpty() || name.length() > 64) throw new PluginException("Invalid repository plugin name");
        PluginJson.rejectControlCharacters(name, "Repository plugin name");
        String version = PluginJson.requiredString(object, "version");
        PluginApi.validateVersion(version, "Repository plugin version");
        String type = PluginJson.requiredString(object, "type");
        if (!PluginApi.isSupportedType(type)) throw new PluginException("Unsupported repository plugin type");
        String description = PluginJson.optionalString(object, "description", "");
        if (description.length() > 512) throw new PluginException("Repository description is too long");
        String minCoreVersion = PluginJson.optionalString(object, "minCoreVersion", "0.0.0");
        PluginApi.validateVersion(minCoreVersion, "Repository minCoreVersion");
        List<String> dependencies = PluginJson.optionalStringArray(object, "dependencies", 16);
        for (String dependency : dependencies) PluginManifest.Dependency.parse(dependency);
        String url = PluginJson.requiredString(object, "url");
        if (!url.startsWith("https://")) throw new PluginException("Plugin URL must use HTTPS");
        String sha256 = PluginJson.requiredString(object, "sha256");
        if (sha256.length() != 64 || !isHex(sha256))
            throw new PluginException("Repository checksum must be a SHA-256 hex string");
        int size = PluginJson.requiredInt(object, "size");
        if (size <= 0 || size > PluginArchive.MAX_ARTIFACT_BYTES)
            throw new PluginException("Repository artifact size is out of bounds");
        boolean compatible = PluginApi.isCoreVersionSupported(minCoreVersion, BuildConfig.VERSION_NAME);
        return new CatalogEntry(id, name, version, type, description, minCoreVersion,
            dependencies, compatible, url, sha256, size);
    }

    /** Downloads an artifact and verifies the size and SHA-256 from the signed index. */
    public static byte[] download(CatalogEntry entry) throws PluginException {
        byte[] bytes = fetch(entry.url, PluginArchive.MAX_ARTIFACT_BYTES);
        if (bytes.length != entry.size)
            throw new PluginException("Downloaded plugin size does not match the repository index");
        if (!PluginArchive.sha256(bytes).equalsIgnoreCase(entry.sha256))
            throw new PluginException("Downloaded plugin checksum does not match the repository index");
        return bytes;
    }

    private static byte[] fetch(String url, int maxBytes) throws PluginException {
        if (url == null || !url.startsWith("https://"))
            throw new PluginException("Plugin repository URLs must use HTTPS");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json, application/zip, */*");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK)
                throw new PluginException("Repository request failed with HTTP " + code);
            int declaredLength = connection.getContentLength();
            if (declaredLength > maxBytes)
                throw new PluginException("Repository response is too large");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > maxBytes)
                        throw new PluginException("Repository response is too large");
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new PluginException("Could not contact the plugin repository", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean lower = c >= '0' && c <= '9' || c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!lower && !upper) return false;
        }
        return true;
    }

    public static final class CatalogEntry {
        public final String id;
        public final String name;
        public final String version;
        public final String type;
        public final String description;
        public final String minCoreVersion;
        public final List<String> dependencies;
        public final boolean compatible;
        public final String url;
        public final String sha256;
        public final int size;

        CatalogEntry(String id, String name, String version, String type, String description,
                     String minCoreVersion, List<String> dependencies, boolean compatible,
                     String url, String sha256, int size) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.type = type;
            this.description = description;
            this.minCoreVersion = minCoreVersion;
            this.dependencies = dependencies;
            this.compatible = compatible;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

}
