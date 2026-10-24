package com.termux.app.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-side capability enforcement. A manifest is not trusted to grant itself permissions: the
 * host checks that its declared capability is exactly the one its plugin type is allowed to use.
 */
public final class PluginPermissionManager {

    private static final Map<String, String> TYPE_LABELS;
    private static final Map<String, String> PERMISSION_LABELS;

    static {
        Map<String, String> types = new LinkedHashMap<>();
        types.put(PluginApi.TYPE_THEME, "Terminal theme");
        types.put(PluginApi.TYPE_EXTRA_KEYS, "Extra keys layout");
        types.put(PluginApi.TYPE_SESSION_PRESET, "Session preset");
        TYPE_LABELS = Collections.unmodifiableMap(types);

        Map<String, String> permissions = new LinkedHashMap<>();
        permissions.put(PluginApi.CAPABILITY_THEME, "Apply terminal colors when you have not set your own theme");
        permissions.put(PluginApi.CAPABILITY_EXTRA_KEYS, "Replace the extra keys bar when you have not configured one");
        permissions.put(PluginApi.CAPABILITY_SESSION_PRESET, "Offer a new-session shortcut for a directory in your home");
        PERMISSION_LABELS = Collections.unmodifiableMap(permissions);
    }

    private PluginPermissionManager() {
    }

    public static void verify(String type, List<String> capabilities) throws PluginException {
        if (!PluginApi.isSupportedType(type))
            throw new PluginException("Unsupported plugin type: " + type);
        String required = PluginApi.capabilityForType(type);
        if (capabilities.size() != 1 || !required.equals(capabilities.get(0)))
            throw new PluginException("Plugin type " + type + " requires exactly the capability " + required);
    }

    public static String typeLabel(String type) {
        String label = TYPE_LABELS.get(type);
        return label != null ? label : type;
    }

    public static List<String> describe(String type) {
        String capability = PluginApi.capabilityForType(type);
        String label = PERMISSION_LABELS.get(capability);
        return label == null ? Collections.<String>emptyList()
            : Collections.singletonList(label);
    }

    public static List<String> describeAll(List<String> capabilities) {
        List<String> descriptions = new ArrayList<>();
        for (String capability : capabilities) {
            String label = PERMISSION_LABELS.get(capability);
            if (label != null) descriptions.add(label);
        }
        return descriptions;
    }

}
