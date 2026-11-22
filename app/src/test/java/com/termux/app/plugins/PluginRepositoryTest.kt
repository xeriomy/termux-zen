package com.termux.app.plugins

import com.google.common.io.BaseEncoding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class PluginRepositoryTest {

    private fun catalogJson(vararg entries: JSONObject): String = JSONObject()
        .put("schemaVersion", 2)
        .put("repository", "Test repo")
        .put("plugins", JSONArray().apply { entries.forEach { put(it) } })
        .toString()

    private fun entry(id: String = "com.example.repo"): JSONObject = JSONObject()
        .put("id", id)
        .put("name", "Repo Plugin")
        .put("version", "1.0.0")
        .put("types", JSONArray().put("theme"))
        .put("url", "https://plugins.example/repo-1.0.0.zip")
        .put("sha256", "0".repeat(64))
        .put("size", 1234)

    private fun repo(key: String = ""): PluginRepository = PluginRepository("https://plugins.example", key)

    private fun expectReject(text: String, fragment: String) {
        try {
            repo().parseCatalog(text)
            fail("expected rejection")
        } catch (e: PluginException) {
            assertTrue("message was: ${e.message}", e.message?.contains(fragment) == true)
        }
    }

    @Test
    fun parsesValidCatalog() {
        val catalog = repo().parseCatalog(catalogJson(entry()))
        assertEquals("Test repo", catalog.repositoryName)
        assertEquals(1, catalog.entries.size)
        assertEquals("com.example.repo", catalog.entries[0].id)
        assertEquals(listOf("theme"), catalog.entries[0].types)
        assertEquals(1234L, catalog.entries[0].size)
    }

    @Test
    fun rejectsBadCatalogs() {
        expectReject(catalogJson(entry().put("surprise", 1)), "unknown field")
        expectReject(catalogJson(entry().put("url", "http://insecure.example/x.zip")), "https")
        expectReject(catalogJson(entry().put("sha256", "xyz")), "sha256")
        expectReject(catalogJson(entry().put("size", 0)), "size")
        expectReject(catalogJson(entry().put("size", ZenApi.MAX_ARCHIVE_BYTES + 1)), "size")
        expectReject(catalogJson(entry(), entry()), "duplicate")
        expectReject(catalogJson(entry().put("types", JSONArray().put("shell"))), "unknown type")
        expectReject(JSONObject().put("schemaVersion", 1).put("repository", "x")
            .put("plugins", JSONArray()).toString(), "schemaVersion")
    }

    @Test
    fun verifiesRsaSignatureAgainstPinnedKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pinned = BaseEncoding.base64().encode(keyPair.public.encoded)
        val indexBytes = catalogJson(entry()).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(indexBytes)
        }.sign()
        val sigBase64 = BaseEncoding.base64().encode(signature).toByteArray()
        repo(pinned).verifySignature(indexBytes, sigBase64) // must not throw

        // Tampered index fails.
        try {
            repo(pinned).verifySignature(catalogJson(entry().put("name", "Evil")).toByteArray(), sigBase64)
            fail("expected signature failure")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("does not match"))
        }
    }

    @Test
    fun rejectsWeakPinnedKey() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pinned = BaseEncoding.base64().encode(keyPair.public.encoded)
        val indexBytes = catalogJson(entry()).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(indexBytes)
        }.sign()
        try {
            repo(pinned).verifySignature(indexBytes, BaseEncoding.base64().encode(signature).toByteArray())
            fail("expected weak key rejection")
        } catch (e: PluginException) {
            assertTrue(e.message!!.contains("2048"))
        }
    }
}
