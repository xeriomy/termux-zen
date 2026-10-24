package com.termux.app.plugins;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parsed and strictly validated {@code manifest.json}. */
public final class PluginManifest {

    private static final Set<String> ALLOWED_FIELDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("schemaVersion", "id", "name", "version", "apiVersion", "minCoreVersion",
            "type", "capabilities", "description", "author", "dependencies")));

    public static final int MAX_BYTES = 32 * 1024;

    public final String id;
    public final String name;
    public final String version;
    public final int apiVersion;
    public final String minCoreVersion;
    public final String type;
    public final List<String> capabilities;
    public final String description;
    public final String author;
    public final List<Dependency> dependencies;

    private PluginManifest(String id, String name, String version, int apiVersion,
                           String minCoreVersion, String type, List<String> capabilities,
                           String description, String author, List<Dependency> dependencies) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.apiVersion = apiVersion;
        this.minCoreVersion = minCoreVersion;
        this.type = type;
        this.capabilities = Collections.unmodifiableList(capabilities);
        this.description = description;
        this.author = author;
        this.dependencies = Collections.unmodifiableList(dependencies);
    }

    /**
     * Parses a manifest. When {@code coreVersion} is non-null, plugins requiring a newer core are
     * rejected. A null core version is only used by structural tests.
     */
    public static PluginManifest parse(String json, String coreVersion) throws PluginException {
        if (json == null || json.getBytes().length > MAX_BYTES)
            throw new PluginException("Manifest is missing or larger than " + MAX_BYTES + " bytes");

        JSONObject object = PluginJson.parseObject(json, "Manifest");
        PluginJson.rejectUnknownFields(object, ALLOWED_FIELDS);

        if (PluginJson.requiredInt(object, "schemaVersion") != PluginApi.SCHEMA_VERSION)
            throw new PluginException("Unsupported manifest schema version");

        String id = PluginJson.requiredString(object, "id");
        if (!PluginApi.isValidId(id))
            throw new PluginException("Invalid plugin id: " + id);

        String name = PluginJson.requiredString(object, "name");
        if (name.isEmpty() || name.length() > 64)
            throw new PluginException("Plugin name must be 1 to 64 characters");
        PluginJson.rejectControlCharacters(name, "Plugin name");

        String version = PluginJson.requiredString(object, "version");
        PluginApi.validateVersion(version, "Plugin version");

        int apiVersion = PluginJson.requiredInt(object, "apiVersion");
        if (apiVersion != PluginApi.VERSION)
            throw new PluginException("Unsupported plugin API version " + apiVersion
                + "; this app supports " + PluginApi.VERSION);

        String minCoreVersion = PluginJson.optionalString(object, "minCoreVersion", "0.0.0");
        PluginApi.validateVersion(minCoreVersion, "minCoreVersion");
        if (coreVersion != null && !PluginApi.isCoreVersionSupported(minCoreVersion, coreVersion))
            throw new PluginException("Plugin requires app version " + minCoreVersion
                + " or newer; this app is " + coreVersion);

        String type = PluginJson.requiredString(object, "type");
        if (!PluginApi.isSupportedType(type))
            throw new PluginException("Unsupported plugin type: " + type);

        List<String> capabilities = PluginJson.optionalStringArray(object, "capabilities", 4);
        if (capabilities.isEmpty())
            throw new PluginException("Plugin must declare a capability");
        if (new HashSet<>(capabilities).size() != capabilities.size())
            throw new PluginException("Duplicate capability");
        PluginPermissionManager.verify(type, capabilities);

        String description = PluginJson.optionalString(object, "description", "");
        if (description.length() > 512)
            throw new PluginException("Description is too long");
        PluginJson.rejectControlCharacters(description, "Description");

        String author = PluginJson.optionalString(object, "author", "");
        if (author.length() > 128)
            throw new PluginException("Author is too long");
        PluginJson.rejectControlCharacters(author, "Author");

        List<String> rawDependencies = PluginJson.optionalStringArray(object, "dependencies", 16);
        List<Dependency> dependencies = new ArrayList<>();
        Set<String> dependencyIds = new HashSet<>();
        for (String raw : rawDependencies) {
            Dependency dependency = Dependency.parse(raw);
            if (dependency.id.equals(id))
                throw new PluginException("A plugin cannot depend on itself");
            if (!dependencyIds.add(dependency.id))
                throw new PluginException("Duplicate dependency: " + dependency.id);
            dependencies.add(dependency);
        }

        return new PluginManifest(id, name, version, apiVersion, minCoreVersion, type,
            capabilities, description, author, dependencies);
    }

    public static final class Dependency {
        public final String id;
        public final String minVersion;

        private Dependency(String id, String minVersion) {
            this.id = id;
            this.minVersion = minVersion;
        }

        public static Dependency parse(String value) throws PluginException {
            int separator = value.lastIndexOf('@');
            if (separator <= 0 || separator == value.length() - 1)
                throw new PluginException("Dependency must use the form id@minVersion: " + value);
            String id = value.substring(0, separator);
            String version = value.substring(separator + 1);
            if (!PluginApi.isValidId(id))
                throw new PluginException("Invalid dependency id: " + id);
            PluginApi.validateVersion(version, "Dependency version");
            return new Dependency(id, version);
        }

        @Override
        public String toString() {
            return id + "@" + minVersion;
        }
    }

}
