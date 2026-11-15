package com.battlesbudz.jarvis.v2.code

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The export signing key (code-and-export scope section 5.2, step 6):
 * an RSA-2048 key generated once, on first export, into the
 * AndroidKeyStore under a Faraday alias, certificate subject
 * `CN=Faraday Code export`, 30-year validity, and reused for every
 * export from that install. A stable key plus the store's stable
 * per-project package names is what makes a re-export install as an
 * update of the same app.
 *
 * The known edge, stated in the scope so nobody discovers it later:
 * the key lives in Faraday's AndroidKeyStore. Uninstalling Faraday or
 * clearing its data destroys it, and exports made afterward carry a
 * new signature, so they will not update-install over exports made
 * before. The projects themselves are unaffected.
 *
 * Android-only: the AndroidKeyStore provider does not exist on the
 * JVM, so unit tests inject their own keystore into [CodeApkExporter]
 * and this object is exercised on the phone in Unit 6.
 */
internal object CodeExportSigningKey {

    const val KEY_ALIAS = "faraday-code-export"
    const val CERT_SUBJECT = "CN=Faraday Code export"

    private const val KEY_SIZE_BITS = 2048
    private const val VALIDITY_MILLIS = 30L * 365 * 24 * 60 * 60 * 1000
    private const val NOT_BEFORE_SKEW_MILLIS = 24L * 60 * 60 * 1000

    /** The AndroidKeyStore holding the export key, generating it on first use. */
    fun loadOrCreateKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) return keyStore
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setCertificateSubject(X500Principal(CERT_SUBJECT))
            .setCertificateNotBefore(Date(now - NOT_BEFORE_SKEW_MILLIS))
            .setCertificateNotAfter(Date(now + VALIDITY_MILLIS))
            .setKeySize(KEY_SIZE_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run {
            initialize(spec)
            generateKeyPair()
        }
        return KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
}
