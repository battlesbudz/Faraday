package com.battlesbudz.jarvis.v2.privilege

import android.net.ssl.SSLSockets
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

internal class PairingTransportException(val blockedReason: String, cause: Throwable? = null) : IOException(blockedReason, cause)

/** Socket boundary keeps the protocol deterministic in JVM tests; the production implementation is platform TLS. */
internal interface AdbPairingChannel : java.io.Closeable {
    val input: DataInputStream
    val output: java.io.OutputStream
    fun export(label: String, length: Int): ByteArray
    override fun close()
}

internal fun interface AdbPairingChannelFactory {
    fun connect(endpoint: AdbEndpoint): AdbPairingChannel
}

/** AOSP pairing protocol: TLS 1.3, TLS exporter, Curve25519 SPAKE2, then encrypted PeerInfo. */
internal class AndroidAdbPairingTransport(
    private val channels: AdbPairingChannelFactory = PlatformAdbPairingChannelFactory,
) : WirelessDebuggingPairingTransport {
    override fun pair(endpoint: AdbEndpoint, pairingCode: CharArray): String {
        if (pairingCode.isEmpty()) throw PairingTransportException("Wireless-debugging pairing code is empty.")
        val channel = try { channels.connect(endpoint) }
        catch (e: PairingTransportException) { throw e }
        catch (e: java.net.ConnectException) { throw PairingTransportException("Wireless-debugging endpoint is unreachable.", e) }
        catch (e: javax.net.ssl.SSLException) { throw PairingTransportException("Wireless-debugging TLS handshake failed.", e) }
        catch (e: IOException) { throw PairingTransportException("Wireless-debugging endpoint is unreachable: ${e.message ?: "connection failed"}.", e) }
        channel.use {
            val exported = try { it.export(EXPORT_LABEL, EXPORT_SIZE) }
            catch (e: Exception) { throw PairingTransportException("Wireless-debugging TLS exporter failed.", e) }
            if (exported.size != EXPORT_SIZE) {
                val size = exported.size
                exported.fill(0)
                throw PairingTransportException("Wireless-debugging TLS exporter returned $size bytes; expected 64.")
            }
            val codeBytes = String(pairingCode).toByteArray(Charsets.UTF_8)
            val password = codeBytes + exported
            val auth = try { PairingAuth.client(password) }
            finally { codeBytes.fill(0); exported.fill(0); password.fill(0) }
            try {
                writePacket(it.output, TYPE_SPAKE2, auth.message)
                val peerMessage = readPacket(it.input, TYPE_SPAKE2)
                    ?: throw PairingTransportException("Wireless-debugging endpoint closed before SPAKE2 completed.")
                try { auth.initCipher(peerMessage) }
                catch (e: IllegalArgumentException) { throw PairingTransportException("Wireless-debugging SPAKE2 peer message is invalid.", e) }

                val peerInfo = ByteArray(PEER_INFO_SIZE)
                peerInfo[0] = PEER_RSA_PUB_KEY.toByte()
                val publicKey = PairingIdentity.adbPublicKey.toByteArray(Charsets.UTF_8)
                System.arraycopy(publicKey, 0, peerInfo, 1, publicKey.size)
                writePacket(it.output, TYPE_PEER_INFO, auth.encrypt(peerInfo))
                val encrypted = try { readPacket(it.input, TYPE_PEER_INFO) }
                catch (e: PairingTransportException) { throw e }
                catch (e: IOException) { throw PairingTransportException("Wireless-debugging pairing code was rejected.", e) }
                    ?: throw PairingTransportException("Wireless-debugging pairing code was rejected.")
                val response = try { auth.decrypt(encrypted) }
                catch (e: AEADBadTagException) { throw PairingTransportException("Wireless-debugging pairing code was rejected.", e) }
                if (response.size != PEER_INFO_SIZE) throw PairingTransportException("Wireless-debugging peer info has invalid size ${response.size}.")
                if ((response[0].toInt() and 0xff) != PEER_DEVICE_GUID) throw PairingTransportException("Wireless-debugging peer info has unexpected type ${response[0].toInt() and 0xff}.")
                var end = 1
                while (end < response.size && response[end] != 0.toByte()) end++
                return String(response, 1, end - 1, Charsets.UTF_8).ifBlank {
                    throw PairingTransportException("Wireless-debugging peer returned an empty device GUID.")
                }
            } catch (e: PairingTransportException) { throw e }
            catch (e: IOException) { throw PairingTransportException("Wireless-debugging pairing protocol failed: ${e.message ?: "I/O failure"}.", e) }
            catch (e: Exception) { throw PairingTransportException("Wireless-debugging pairing protocol failed: ${e.message ?: e.javaClass.simpleName}.", e) }
        }
    }

    companion object {
        const val EXPORT_LABEL = "adb-label\u0000"
        const val EXPORT_SIZE = 64
        const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
        private const val VERSION = 1
        private const val TYPE_SPAKE2 = 0
        private const val TYPE_PEER_INFO = 1
        private const val PEER_RSA_PUB_KEY = 0
        private const val PEER_DEVICE_GUID = 1

        internal fun writePacket(out: java.io.OutputStream, type: Int, payload: ByteArray) {
            val header = byteArrayOf(VERSION.toByte(), type.toByte(), (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte())
            out.write(header + payload); out.flush()
        }

        internal fun readPacket(input: DataInputStream, expectedType: Int): ByteArray? {
            val header = ByteArray(6)
            try { input.readFully(header) } catch (_: EOFException) { return null }
            val len = ((header[2].toInt() and 255) shl 24) or ((header[3].toInt() and 255) shl 16) or ((header[4].toInt() and 255) shl 8) or (header[5].toInt() and 255)
            if ((header[0].toInt() and 255) != VERSION || (header[1].toInt() and 255) != expectedType || len !in 1..MAX_PAYLOAD)
                throw PairingTransportException("Wireless-debugging pairing packet header is invalid.")
            return ByteArray(len).also { input.readFully(it) }
        }
    }
}

private object PlatformAdbPairingChannelFactory : AdbPairingChannelFactory {
    override fun connect(endpoint: AdbEndpoint): AdbPairingChannel {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), 15_000)
            plain.soTimeout = 15_000
            val context = SSLContext.getInstance("TLS")
            context.init(arrayOf(PairingIdentity.keyManager), arrayOf(TrustAllAdbCertificates), SecureRandom())
            val ssl = context.socketFactory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.useClientMode = true
            ssl.startHandshake()
            return object : AdbPairingChannel {
                override val input = DataInputStream(ssl.inputStream)
                override val output = ssl.outputStream
                override fun export(label: String, length: Int): ByteArray =
                    SSLSockets.exportKeyingMaterial(ssl, label, ByteArray(0), length)
                        ?: throw javax.net.ssl.SSLException("TLS exporter returned no key material")
                override fun close() = ssl.close()
            }
        } catch (e: javax.net.ssl.SSLException) { runCatching { plain.close() }; throw PairingTransportException("Wireless-debugging TLS handshake failed.", e) }
        catch (e: IOException) { runCatching { plain.close() }; throw e }
    }
}

/** App-private, versioned ADB identity shared by pairing and authenticated exec reconnects. */
internal object PairingIdentity {
    @Volatile private var identityFile: java.io.File? = null
    @Volatile private var identityPreviouslyInitialized = false
    @Volatile private var loadedPeerInfo: String? = null
    @Volatile private var appContext: android.content.Context? = null
    @Volatile private var loadedPair: KeyPair? = null
    /** Load the process-stable ADB key before any transport is used. */
    @Synchronized fun initialize(context: android.content.Context): String? {
        appContext = context.applicationContext
        identityFile = java.io.File(context.applicationContext.noBackupFilesDir, "adb-identity-v1.properties")
        val prefs = context.getSharedPreferences("app_control_privilege", android.content.Context.MODE_PRIVATE)
        identityPreviouslyInitialized = prefs.getBoolean("adb_identity_initialized", false)
        return try {
            pair
            if (prefs.getBoolean("adb_pairing_complete", false) && loadedPeerInfo.isNullOrBlank()) {
                return "stored ADB identity is missing paired peer info"
            }
            check(prefs.edit().putBoolean("adb_identity_initialized", true).commit()) { "could not persist ADB identity lifecycle marker" }
            null
        } catch (t: Throwable) {
            "stored ADB identity is missing or corrupt: ${t.message ?: t.javaClass.simpleName}"
        }
    }
    private val pair: KeyPair by lazy {
        synchronized(this) {
            loadedPair ?: run {
                val file = identityFile
                val factory = java.security.KeyFactory.getInstance("RSA")
                val existing = file?.let { AdbIdentityRecord.load(it, identityPreviouslyInitialized) }
                val restored = if (existing != null) {
                    loadedPeerInfo = existing.peerInfo
                    KeyPair(factory.generatePublic(java.security.spec.X509EncodedKeySpec(existing.publicKey)), factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(existing.privateKey)))
                } else KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also { generated ->
                    file?.let { AdbIdentityRecord.save(it, AdbIdentityRecord.Value(generated.public.encoded, generated.private.encoded, null)) }
                }
                restored.also { loadedPair = it }
            }
        }
    }
    @Synchronized fun storePeerInfo(peerInfo: String) {
        require(peerInfo.isNotBlank())
        val file = identityFile ?: return
        val keys = pair
        AdbIdentityRecord.save(file, AdbIdentityRecord.Value(keys.public.encoded, keys.private.encoded, peerInfo))
        check(appContext?.getSharedPreferences("app_control_privilege", android.content.Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("adb_pairing_complete", true)?.putBoolean("pairing_consent", true)?.commit() == true) {
            "could not persist ADB pairing state and recovery consent"
        }
        loadedPeerInfo = peerInfo
    }
    fun peerInfo(): String? = loadedPeerInfo
    private val certificate: X509Certificate by lazy { selfSigned(pair) }
    val adbPublicKey: String by lazy {
        val key = pair.public as java.security.interfaces.RSAPublicKey
        val n = key.modulus
        val radix = BigInteger.ONE.shiftLeft(32)
        val n0inv = radix - n.mod(radix).modInverse(radix)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val body = little(BigInteger.valueOf(64),4) + little(n0inv,4) + little(n,256) + little(rr,256) + little(key.publicExponent,4)
        Base64.getEncoder().encodeToString(body) + " jarvis"
    }
    val keyManager: X509ExtendedKeyManager by lazy { object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = pair.private
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = "adb"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = "adb"
    } }
    private fun little(value: BigInteger, size: Int): ByteArray = ByteArray(size) { i -> value.shiftRight(i * 8).toByte() }
    private fun selfSigned(keys: KeyPair): X509Certificate {
        fun tlv(tag: Int, data: ByteArray): ByteArray {
            val head = ByteArrayOutputStream().apply {
                write(tag); when { data.size < 128 -> write(data.size); data.size < 256 -> { write(0x81); write(data.size) }; else -> { write(0x82); write(data.size ushr 8); write(data.size) } }
            }.toByteArray(); return head + data
        }
        fun seq(vararg p: ByteArray) = tlv(0x30, p.fold(ByteArray(0)) { a,b -> a+b })
        val alg = seq(tlv(6, byteArrayOf(0x2a,0x86.toByte(),0x48,0x86.toByte(),0xf7.toByte(),0x0d,1,1,0x0b)), tlv(5, byteArrayOf()))
        val name = seq(tlv(0x31, seq(tlv(6, byteArrayOf(0x55,4,3)), tlv(0x0c, "Jarvis ADB".toByteArray()))))
        val dates = seq(tlv(0x17, "700101000000Z".toByteArray()), tlv(0x18, "20991231235959Z".toByteArray()))
        val tbs = seq(tlv(0xa0, tlv(2, byteArrayOf(2))), tlv(2, byteArrayOf(1)), alg, name, dates, name, keys.public.encoded)
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(tbs); sign() }
        val der = seq(tbs, alg, tlv(3, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }
}

private object TrustAllAdbCertificates : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
