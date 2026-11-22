package com.termux.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginArchiveTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun expectReject(bytes: ByteArray, fragment: String) {
        try {
            PluginArchive.readPluginJson(bytes)
            fail("expected rejection")
        } catch (e: PluginException) {
            assertTrue("message was: ${e.message}", e.message?.contains(fragment) == true)
        }
    }

    @Test
    fun roundTripsDocument() {
        val text = PluginTestUtils.document()
        val bytes = PluginArchive.write(text)
        assertEquals(text, PluginArchive.readPluginJson(bytes))
    }

    @Test
    fun acceptsOptionalLicense() {
        val bytes = zipOf("plugin.json" to PluginTestUtils.document().toByteArray(), "LICENSE" to "MIT".toByteArray())
        assertEquals(PluginTestUtils.document(), PluginArchive.readPluginJson(bytes))
    }

    @Test
    fun rejectsMissingPluginJson() {
        expectReject(zipOf("LICENSE" to "MIT".toByteArray()), "missing plugin.json")
    }

    @Test
    fun rejectsUnknownEntries() {
        expectReject(zipOf(
            "plugin.json" to PluginTestUtils.document().toByteArray(),
            "evil.sh" to "rm -rf /".toByteArray()
        ), "unexpected entry")
    }

    @Test
    fun rejectsTraversalAndAbsoluteNames() {
        expectReject(zipOf("../plugin.json" to ByteArray(4)), "path-safe")
        expectReject(zipOf("/plugin.json" to ByteArray(4)), "path-safe")
        expectReject(zipOf("a\\plugin.json" to ByteArray(4)), "path-safe")
    }

    @Test
    fun rejectsDuplicateEntries() {
        // ZipOutputStream refuses duplicate names, so craft two raw stored local headers.
        val entry = rawStoredEntry("plugin.json", PluginTestUtils.document().toByteArray())
        expectReject(entry + entry, "duplicate")
    }

    /** One uncompressed ZIP local file header + data (no central directory needed by ZipInputStream). */
    private fun rawStoredEntry(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = java.util.zip.CRC32().apply { update(data) }.value
        val buffer = java.nio.ByteBuffer.allocate(30 + nameBytes.size + data.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x04034b50)          // local file header signature
        buffer.putShort(20)                // version needed
        buffer.putShort(0)                 // flags
        buffer.putShort(0)                 // method: stored
        buffer.putShort(0)                 // mod time
        buffer.putShort(0)                 // mod date
        buffer.putInt(crc.toInt())
        buffer.putInt(data.size)           // compressed size
        buffer.putInt(data.size)           // uncompressed size
        buffer.putShort(nameBytes.size.toShort())
        buffer.putShort(0)                 // extra length
        buffer.put(nameBytes)
        buffer.put(data)
        return buffer.array()
    }

    @Test
    fun rejectsOversizeArchive() {
        expectReject(ByteArray(ZenApi.MAX_ARCHIVE_BYTES + 1), "exceeds")
    }

    @Test
    fun rejectsOversizeDocument() {
        val big = "x".repeat(ZenApi.MAX_PLUGIN_JSON_BYTES + 1)
        expectReject(zipOf("plugin.json" to big.toByteArray()), "beyond")
    }

    @Test
    fun rejectsGarbage() {
        expectReject("not a zip".toByteArray(), "ZIP")
    }
}
