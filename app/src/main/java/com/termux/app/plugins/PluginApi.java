package com.termux.app.plugins;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned, declarative plugin API. Adding a type or capability requires a new API version.
 * The host never loads executable code from a plugin.
 */
public final class PluginApi {

    public static final int VERSION = 1;
    public static final int SCHEMA_VERSION = 1;

    public static final String TYPE_THEME = "theme";
    public static final String TYPE_EXTRA_KEYS = "extra_keys";
    public static final String TYPE_SESSION_PRESET = "session_preset";

    public static final String CAPABILITY_THEME = "terminal.theme";
    public static final String CAPABILITY_EXTRA_KEYS = "terminal.extra_keys";
    public static final String CAPABILITY_SESSION_PRESET = "sessions.preset";

    public static final Set<String> TYPES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(TYPE_THEME, TYPE_EXTRA_KEYS, TYPE_SESSION_PRESET)));

    public static final Set<String> CAPABILITIES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(CAPABILITY_THEME, CAPABILITY_EXTRA_KEYS, CAPABILITY_SESSION_PRESET)));

    // Semver 2.0.0, same expression the app build validates versionName with.
    private static final Pattern SEMVER = Pattern.compile(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_-]*)+$");

    private PluginApi() {
    }

    public static boolean isValidId(String id) {
        return id != null && id.length() >= 3 && id.length() <= 128 && ID.matcher(id).matches();
    }

    public static boolean isValidVersion(String version) {
        return version != null && version.length() <= 64 && SEMVER.matcher(version).matches();
    }

    public static boolean isSupportedType(String type) {
        return TYPES.contains(type);
    }

    public static String capabilityForType(String type) {
        if (TYPE_THEME.equals(type)) return CAPABILITY_THEME;
        if (TYPE_EXTRA_KEYS.equals(type)) return CAPABILITY_EXTRA_KEYS;
        if (TYPE_SESSION_PRESET.equals(type)) return CAPABILITY_SESSION_PRESET;
        return null;
    }

    /** Compares two validated Semver 2.0.0 strings. Returns negative, zero, or positive. */
    public static int compareVersions(String left, String right) {
        String[] leftParts = splitVersion(left);
        String[] rightParts = splitVersion(right);
        String[] leftCore = leftParts[0].split("\\.");
        String[] rightCore = rightParts[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = new BigInteger(leftCore[i]).compareTo(new BigInteger(rightCore[i]));
            if (comparison != 0) return comparison;
        }
        String leftPre = leftParts[1];
        String rightPre = rightParts[1];
        if (leftPre == null && rightPre == null) return 0;
        if (leftPre == null) return 1;
        if (rightPre == null) return -1;
        String[] leftIds = leftPre.split("\\.");
        String[] rightIds = rightPre.split("\\.");
        for (int i = 0; i < Math.min(leftIds.length, rightIds.length); i++) {
            String a = leftIds[i];
            String b = rightIds[i];
            boolean aNumeric = isNumeric(a);
            boolean bNumeric = isNumeric(b);
            int comparison;
            if (aNumeric && bNumeric) {
                comparison = new BigInteger(a).compareTo(new BigInteger(b));
            } else if (aNumeric) {
                comparison = -1;
            } else if (bNumeric) {
                comparison = 1;
            } else {
                comparison = a.compareTo(b);
            }
            if (comparison != 0) return comparison;
        }
        return Integer.compare(leftIds.length, rightIds.length);
    }

    /** Returns true if the running core is new enough for the plugin. */
    public static boolean isCoreVersionSupported(String minCoreVersion, String coreVersion) {
        return compareVersions(coreVersion, minCoreVersion) >= 0;
    }

    public static void validateVersion(String version, String what) throws PluginException {
        if (!isValidVersion(version))
            throw new PluginException(what + " must be a Semver 2.0.0 version, got: " + version);
    }

    private static String[] splitVersion(String version) {
        if (!isValidVersion(version)) throw new IllegalArgumentException("Invalid version: " + version);
        String[] buildSplit = version.split("\\+", 2);
        String[] preSplit = buildSplit[0].split("-", 2);
        return new String[]{preSplit[0], preSplit.length > 1 ? preSplit[1] : null};
    }

    private static boolean isNumeric(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return value.length() > 0;
    }

}
