package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * M3 MCP transport (T17): secure stored auth and the HTTP layer.
 *
 * Secrets live only in the [McpCredentialStore], keyed by an opaque
 * reference held in the server config. They never enter model prompts,
 * logs, receipts, the journal or memory — the config's `toString` and the
 * settings projection expose only the reference.
 */
interface McpCredentialStore {
    fun putSecret(ref: String, secret: String)
    fun getSecret(ref: String): String?
    fun deleteSecret(ref: String)
    fun hasSecret(ref: String): Boolean
}

class InMemoryMcpCredentialStore : McpCredentialStore {
    private val secrets = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun putSecret(ref: String, secret: String) {
        requireRef(ref)
        secrets[ref] = secret
    }
    override fun getSecret(ref: String): String? = secrets[ref]
    override fun deleteSecret(ref: String) { secrets.remove(ref) }
    override fun hasSecret(ref: String): Boolean = secrets.containsKey(ref)

    companion object {
        fun requireRef(ref: String) {
            require(ref.matches(Regex("[a-z0-9._-]{1,64}"))) { "Credential reference is not usable." }
        }
    }
}

/**
 * Android credential store: AES-256-GCM via the Android Keystore, one key
 * for all MCP secrets, per-secret random IVs. Ciphertext (never plaintext)
 * sits in app-private SharedPreferences.
 */
class AndroidKeystoreMcpCredentialStore(context: Context) : McpCredentialStore {
    private val prefs = context.getSharedPreferences("mcp_credentials", Context.MODE_PRIVATE)

    override fun putSecret(ref: String, secret: String) {
        InMemoryMcpCredentialStore.requireRef(ref)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putString("$ref.iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("$ref.ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    override fun getSecret(ref: String): String? = try {
        val iv = prefs.getString("$ref.iv", null) ?: return null
        val ciphertext = prefs.getString("$ref.ct", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    override fun deleteSecret(ref: String) {
        prefs.edit().remove("$ref.iv").remove("$ref.ct").apply()
    }

    override fun hasSecret(ref: String): Boolean = prefs.contains("$ref.ct")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis-mcp-credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

data class McpHttpResponse(val status: Int, val body: String, val headers: Map<String, String>)

fun interface McpHttpClient {
    /** POST a JSON-RPC payload. Implementations must never log the Authorization header. */
    fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse
}

/** JVM-pure HTTP client over HttpURLConnection; works on Android as well. */
class UrlConnectionMcpHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) : McpHttpClient {
    override fun post(url: String, body: String, headers: Map<String, String>): McpHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = try {
                stream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: ""
            } catch (_: Exception) { "" }
            val responseHeaders = connection.headerFields.entries
                .mapNotNull { (name, values) -> name?.let { it to (values.firstOrNull() ?: "") } }
                .toMap()
            return McpHttpResponse(status, responseBody, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Custom-URL policy for MCP servers (D07): only http/https, no credentials
 * embedded in the URL, and plain http only for loopback (local test
 * servers). Anything else is rejected before any network use.
 */
object McpUrlPolicy {
    sealed interface Validation {
        data class Valid(val url: String) : Validation
        data class Invalid(val reason: String) : Validation
    }

    fun validate(rawUrl: String): Validation {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("The server URL is empty.")
        if (trimmed.length > 512) return Validation.Invalid("The server URL is too long.")
        val uri = try { URI(trimmed) } catch (_: Exception) {
            return Validation.Invalid("That is not a valid URL.")
        }
        if (uri.userInfo != null) {
            return Validation.Invalid("The URL must not contain credentials. The token goes in the auth field.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Validation.Invalid("Only http and https URLs are supported.")
        }
        val host = uri.host?.lowercase() ?: return Validation.Invalid("The URL needs a host.")
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host == "[::1]"
        if (scheme == "http" && !loopback) {
            return Validation.Invalid("Plain http is only allowed for local test servers; use https.")
        }
        return Validation.Valid(uri.toString())
    }
}
