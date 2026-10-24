package com.termux.app.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a plugin artifact entirely in memory. Archives are never extracted to the filesystem, so
 * traversal, symlink, and device-file entries cannot escape the plugin directory. Every bound is
 * enforced while reading.
 */
public final class PluginArchive {

    public static final int MAX_ARTIFACT_BYTES = 512 * 1024;
    public static final int MAX_ENTRY_BYTES = 128 * 1024;
    public static final int MAX_TOTAL_UNCOMPRESSED_BYTES = 256 * 1024;

    private static final Set<String> ALLOWED_ENTRIES = new HashSet<>();

    static {
        ALLOWED_ENTRIES.add("manifest.json");
        ALLOWED_ENTRIES.add("payload.json");
        ALLOWED_ENTRIES.add("LICENSE");
    }

    public final byte[] artifactBytes;
    public final String sha256;
    public final byte[] manifestJson;
    public final byte[] payloadJson;

    private PluginArchive(byte[] artifactBytes, String sha256, byte[] manifestJson, byte[] payloadJson) {
        this.artifactBytes = artifactBytes;
        this.sha256 = sha256;
        this.manifestJson = manifestJson;
        this.payloadJson = payloadJson;
    }

    public static PluginArchive read(byte[] bytes) throws PluginException {
        if (bytes == null || bytes.length == 0)
            throw new PluginException("Plugin artifact is empty");
        if (bytes.length > MAX_ARTIFACT_BYTES)
            throw new PluginException("Plugin artifact exceeds " + MAX_ARTIFACT_BYTES + " bytes");

        byte[] manifest = null;
        byte[] payload = null;
        Set<String> seen = new HashSet<>();
        int total = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory())
                    throw new PluginException("Plugin artifact must not contain directories: " + name);
                if (!ALLOWED_ENTRIES.contains(name))
                    throw new PluginException("Unexpected file in plugin artifact: " + name);
                if (!seen.add(name))
                    throw new PluginException("Duplicate file in plugin artifact: " + name);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (out.size() + read > MAX_ENTRY_BYTES)
                        throw new PluginException("Archive entry is too large: " + name);
                    if (total > MAX_TOTAL_UNCOMPRESSED_BYTES)
                        throw new PluginException("Plugin artifact expands beyond "
                            + MAX_TOTAL_UNCOMPRESSED_BYTES + " bytes");
                    out.write(buffer, 0, read);
                }
                if ("manifest.json".equals(name)) manifest = out.toByteArray();
                if ("payload.json".equals(name)) payload = out.toByteArray();
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new PluginException("Plugin artifact is not a valid ZIP file", e);
        }

        if (manifest == null) throw new PluginException("Plugin artifact is missing manifest.json");
        if (payload == null) throw new PluginException("Plugin artifact is missing payload.json");
        return new PluginArchive(bytes, sha256(bytes), manifest, payload);
    }

    public static String sha256(byte[] bytes) throws PluginException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new PluginException("SHA-256 is not available", e);
        }
    }

}
