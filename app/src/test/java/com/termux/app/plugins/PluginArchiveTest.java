package com.termux.app.plugins;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginArchiveTest {

    @Test
    public void readsValidArchiveInMemory() throws Exception {
        byte[] archive = PluginTestUtils.zip(
            PluginTestUtils.themeManifest("com.termux.sample.theme", "1.0.0"),
            PluginTestUtils.themePayload());
        PluginArchive parsed = PluginArchive.read(archive);
        assertTrue(parsed.manifestJson.length > 0);
        assertTrue(parsed.payloadJson.length > 0);
        assertEquals(64, parsed.sha256.length());
    }

    @Test
    public void rejectsUnexpectedTraversalAndDuplicateEntries() throws Exception {
        Map<String, byte[]> unexpected = new LinkedHashMap<>();
        unexpected.put("manifest.json", PluginTestUtils.themeManifest(
            "com.termux.sample.theme", "1.0.0").getBytes(PluginTestUtils.UTF_8));
        unexpected.put("payload.json", PluginTestUtils.themePayload().getBytes(PluginTestUtils.UTF_8));
        unexpected.put("../escape.sh", new byte[]{1});
        assertThrows(PluginException.class, () -> PluginArchive.read(PluginTestUtils.zip(unexpected)));

        Map<String, byte[]> duplicate = new LinkedHashMap<>();
        duplicate.put("manifest.json", PluginTestUtils.themeManifest(
            "com.termux.sample.theme", "1.0.0").getBytes(PluginTestUtils.UTF_8));
        duplicate.put("payload.json", PluginTestUtils.themePayload().getBytes(PluginTestUtils.UTF_8));
        byte[] bytes = duplicateEntries(duplicate);
        assertThrows(PluginException.class, () -> PluginArchive.read(bytes));
    }

    @Test
    public void rejectsMissingFilesAndDirectoryEntries() throws Exception {
        Map<String, byte[]> missing = new LinkedHashMap<>();
        missing.put("manifest.json", PluginTestUtils.themeManifest(
            "com.termux.sample.theme", "1.0.0").getBytes(PluginTestUtils.UTF_8));
        assertThrows(PluginException.class, () -> PluginArchive.read(PluginTestUtils.zip(missing)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json/"));
            zip.closeEntry();
        }
        byte[] bytes = out.toByteArray();
        assertThrows(PluginException.class, () -> PluginArchive.read(bytes));
    }

    @Test
    public void enforcesEntryAndArtifactSizeLimits() {
        byte[] oversizedEntry = new byte[PluginArchive.MAX_ENTRY_BYTES + 1];
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", oversizedEntry);
        entries.put("payload.json", new byte[]{1});
        assertThrows(PluginException.class, () -> PluginArchive.read(PluginTestUtils.zip(entries)));
        assertThrows(PluginException.class,
            () -> PluginArchive.read(new byte[PluginArchive.MAX_ARTIFACT_BYTES + 1]));
    }

    /**
     * ZipOutputStream rejects duplicate names itself, so build two local entries by hand.
     * ZipInputStream reads local entries and does not need a central directory.
     */
    private static byte[] duplicateEntries(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> entry : entries.entrySet())
            writeStoredEntry(out, entry.getKey(), entry.getValue());
        writeStoredEntry(out, "manifest.json", entries.get("manifest.json"));
        return out.toByteArray();
    }

    private static void writeStoredEntry(ByteArrayOutputStream out, String name, byte[] data)
        throws IOException {
        byte[] nameBytes = name.getBytes(PluginTestUtils.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        writeIntLE(out, 0x04034b50);
        writeShortLE(out, 20);
        writeShortLE(out, 0x0800);
        writeShortLE(out, 0);
        writeShortLE(out, 0);
        writeShortLE(out, 0);
        writeIntLE(out, (int) crc.getValue());
        writeIntLE(out, data.length);
        writeIntLE(out, data.length);
        writeShortLE(out, nameBytes.length);
        writeShortLE(out, 0);
        out.write(nameBytes);
        out.write(data);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

}
