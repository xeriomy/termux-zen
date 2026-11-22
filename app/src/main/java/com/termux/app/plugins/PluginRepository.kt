package com.termux.app.plugins

import com.google.common.io.BaseEncoding
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.HttpsURLConnection

/**
 * Client for a signed, static plugin catalog. TLS is required but is not treated as
 * publisher authenticity: only an index whose SHA256withRSA signature verifies against
 * the pinned key is accepted, and artifacts must match the signed size and SHA-256.
 * Redirects are refused. Pure JVM (Guava Base64 + HttpsURLConnection) for testability.
 */
class PluginRepository(private val baseUrl: String, private val pinnedKeyBase64: String) {

    data class CatalogEntry(
        val id: String,
        val name: String,
        val version: Semver,
        val description: String?,
        val minCoreVersion: Semver,
        val dependencies: List<String>,
        val types: List<String>,
        val url: String,
        val sha256: String,
        val size: Long
    )

    data class Catalog(val repositoryName: String, val entries: List<CatalogEntry>)

    fun fetchCatalog(): Catalog {
        val base = baseUrl.trimEnd('/')
        requireHttps(base)
        val indexBytes = download("$base/index.json", ZenApi.MAX_INDEX_BYTES)
        val signatureBytes = download("$base/index.json.sig", ZenApi.MAX_SIGNATURE_BYTES)
        verifySignature(indexBytes, signatureBytes)
        return parseCatalog(String(indexBytes, Charsets.UTF_8))
    }

    fun downloadArtifact(entry: CatalogEntry): ByteArray {
        requireHttps(entry.url)
        val bytes = download(entry.url, ZenApi.MAX_ARCHIVE_BYTES)
        if (bytes.size.toLong() != entry.size)
            throw PluginException("artifact size does not match the signed catalog")
        if (PluginStore.sha256Hex(bytes) != entry.sha256)
            throw PluginException("artifact checksum does not match the signed catalog")
        return bytes
    }

    // ---------------------------------------------------------------- signature

    internal fun verifySignature(indexBytes: ByteArray, signatureBytes: ByteArray) {
        val keyText = String(signatureBytes, Charsets.UTF_8).filter { !it.isWhitespace() }
        val signature = try {
            BaseEncoding.base64().decode(keyText)
        } catch (e: Exception) {
            throw PluginException("catalog signature is not valid Base64", e)
        }
        val publicKey = try {
            val keyBytes = BaseEncoding.base64().decode(pinnedKeyBase64.filter { !it.isWhitespace() })
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        } catch (e: Exception) {
            throw PluginException("pinned repository key is invalid", e)
        }
        if (publicKey !is RSAPublicKey || publicKey.modulus.bitLength() < 2048)
            throw PluginException("pinned repository key must be RSA with at least 2048 bits")
        val verified = try {
            Signature.getInstance("SHA256withRSA").apply {
                initVerify(publicKey)
                update(indexBytes)
            }.verify(signature)
        } catch (e: Exception) {
            throw PluginException("catalog signature verification failed: ${e.message}", e)
        }
        if (!verified) throw PluginException("catalog signature does not match the pinned key")
    }

    // ---------------------------------------------------------------- catalog parsing

    private val entryKeys = setOf(
        "id", "name", "version", "description", "minCoreVersion", "dependencies", "types", "url", "sha256", "size"
    )

    internal fun parseCatalog(text: String): Catalog {
        val obj = PluginJson.parseObject(text, "catalog index")
        PluginJson.rejectUnknownKeys(obj, setOf("schemaVersion", "repository", "plugins"), "catalog index")
        val schema = PluginJson.requireInt(obj, "schemaVersion", "catalog index")
        if (schema != ZenApi.SCHEMA_VERSION)
            throw PluginException("unsupported catalog schemaVersion $schema")
        val repositoryName = PluginJson.requireString(obj, "repository", "catalog index", 128)
        val array = PluginJson.requireArray(obj, "plugins", "catalog index")
        if (array.length() > ZenApi.MAX_CATALOG_ENTRIES)
            throw PluginException("catalog exceeds ${ZenApi.MAX_CATALOG_ENTRIES} entries")
        val entries = ArrayList<CatalogEntry>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val entryObj = PluginJson.objectAt(array, i, "catalog plugins")
            PluginJson.rejectUnknownKeys(entryObj, entryKeys, "catalog plugins[$i]")
            val id = PluginJson.requireString(entryObj, "id", "catalog plugins[$i]", ZenApi.ID_MAX_LENGTH)
            if (!ZenApi.isValidId(id)) throw PluginException("catalog plugins[$i] has an invalid id")
            if (!seen.add(id)) throw PluginException("catalog has duplicate id \"$id\"")
            val name = PluginJson.requireString(entryObj, "name", "catalog plugins[$i]", ZenApi.NAME_MAX_LENGTH)
            val version = Semver.parseOrThrow(
                PluginJson.requireString(entryObj, "version", "catalog plugins[$i]", 64), "catalog version")
            val description = PluginJson.optionalString(entryObj, "description", "catalog plugins[$i]", ZenApi.DESCRIPTION_MAX_LENGTH)
            val minCoreVersion = Semver.parseOrThrow(
                PluginJson.optionalString(entryObj, "minCoreVersion", "catalog plugins[$i]", 64) ?: "0.0.0",
                "catalog minCoreVersion")
            val dependencies = ArrayList<String>()
            PluginJson.optionalArray(entryObj, "dependencies", "catalog plugins[$i]")?.let { deps ->
                if (deps.length() > ZenApi.MAX_DEPENDENCIES)
                    throw PluginException("catalog plugins[$i] has too many dependencies")
                for (j in 0 until deps.length())
                    dependencies.add(PluginJson.stringAt(deps, j, "catalog plugins[$i].dependencies", 200))
            }
            val types = ArrayList<String>()
            PluginJson.optionalArray(entryObj, "types", "catalog plugins[$i]")?.let { typeArray ->
                for (j in 0 until typeArray.length()) {
                    val type = PluginJson.stringAt(typeArray, j, "catalog plugins[$i].types", 32)
                    if (type !in ZenApi.CONTRIBUTION_TYPES)
                        throw PluginException("catalog plugins[$i] has unknown type \"$type\"")
                    types.add(type)
                }
            }
            val url = PluginJson.requireString(entryObj, "url", "catalog plugins[$i]", 2048)
            requireHttps(url)
            val sha256 = PluginJson.requireString(entryObj, "sha256", "catalog plugins[$i]", 64)
            if (!sha256.matches(Regex("^[0-9a-f]{64}$")))
                throw PluginException("catalog plugins[$i] sha256 is invalid")
            val sizeValue = entryObj.opt("size")
            val size = when (sizeValue) {
                is Int -> sizeValue.toLong()
                is Long -> sizeValue
                else -> throw PluginException("catalog plugins[$i] size must be an integer")
            }
            if (size < 1 || size > ZenApi.MAX_ARCHIVE_BYTES)
                throw PluginException("catalog plugins[$i] size is out of range")
            entries.add(CatalogEntry(id, name, version, description, minCoreVersion, dependencies, types, url, sha256, size))
        }
        return Catalog(repositoryName, entries)
    }

    // ---------------------------------------------------------------- transport

    private fun requireHttps(url: String) {
        if (!url.startsWith("https://")) throw PluginException("plugin repository URLs must use https")
    }

    private fun download(url: String, maxBytes: Int): ByteArray {
        requireHttps(url)
        val connection = java.net.URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code != 200) throw PluginException("repository request failed with HTTP $code")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > maxBytes) throw PluginException("repository response exceeds $maxBytes bytes")
                }
            }
            return out.toByteArray()
        } catch (e: PluginException) {
            throw e
        } catch (e: Exception) {
            throw PluginException("repository request failed: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }
}
