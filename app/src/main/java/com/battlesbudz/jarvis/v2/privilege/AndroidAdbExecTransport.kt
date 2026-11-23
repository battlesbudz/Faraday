package com.battlesbudz.jarvis.v2.privilege

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import java.security.cert.X509Certificate

/** Authenticated ADB TLS connection and `exec:` stream, adapted from Mika's Apache-2.0 core/adb. */
internal class AndroidAdbExecTransport(private val connectTimeoutMs: Int = 10_000) {
    fun launch(endpoint: AdbEndpoint, command: String, nonce: String, onAttached: () -> Boolean): Boolean {
        AdbConnection.open(endpoint.host, endpoint.port, connectTimeoutMs).use { connection ->
            connection.exec(command).use { stream ->
                stream.write(HelperHandoff.nonceStdin(nonce))
                // The shell waits on its final `read _` until the provider confirms attachment.
                // A short bounded wait keeps the exec channel open long enough for app_process.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (System.nanoTime() < deadline) {
                    if (onAttached()) return true
                    Thread.sleep(100)
                }
                return false
            }
        }
    }

    private class AdbConnection private constructor(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: OutputStream,
        private val version: Int,
        private val maxData: Int,
        private val timeoutMs: Int,
    ) : Closeable {
        private val streams = ConcurrentHashMap<Int, AdbStream>()
        private val nextId = AtomicInteger(1)
        @Volatile private var closed = false
        init { Thread({ readLoop() }, "faraday-adb-reader").apply { isDaemon = true; start() } }

        fun exec(command: String): AdbStream = open("exec:$command")
        private fun open(destination: String): AdbStream {
            check(!closed) { "ADB connection is closed" }
            val stream = AdbStream(nextId.getAndIncrement())
            streams[stream.localId] = stream
            send(Message(OPEN, stream.localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8)))
            if (!stream.opened.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                streams.remove(stream.localId)
                throw IOException("adbd refused $destination")
            }
            return stream
        }
        private fun send(message: Message) = synchronized(output) { output.write(message.encode()); output.flush() }
        private fun readLoop() {
            try {
                while (!closed) {
                    val m = Message.read(input, maxData, version < VERSION_SKIP_CHECKSUM) ?: break
                    val stream = streams[m.arg1]
                    when (m.command) {
                        OKAY -> if (stream != null) {
                            if (stream.remoteId == 0) { stream.remoteId = m.arg0; stream.opened.complete(true) }
                            else stream.acks.release()
                        }
                        WRTE -> if (stream != null) { stream.incoming.put(m.data); send(Message(OKAY, stream.localId, stream.remoteId)) }
                        CLSE -> if (stream != null) { streams.remove(stream.localId); stream.opened.complete(false); stream.remoteClosed() }
                    }
                }
            } catch (_: Exception) { } finally { close() }
        }
        override fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            streams.values.forEach { it.remoteClosed() }
            streams.clear()
        }

        inner class AdbStream internal constructor(val localId: Int) : Closeable {
            @Volatile var remoteId = 0
            val opened = CompletableFuture<Boolean>()
            val acks = Semaphore(0)
            val incoming = LinkedBlockingQueue<ByteArray>()
            @Volatile private var remoteGone = false
            @Volatile private var localClosed = false
            fun write(bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size) {
                    check(!remoteGone && !localClosed) { "ADB stream closed" }
                    val n = minOf(maxData, bytes.size - offset)
                    send(Message(WRTE, localId, remoteId, bytes.copyOfRange(offset, offset + n)))
                    if (!acks.tryAcquire(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) throw IOException("ADB stream write was not acknowledged")
                    offset += n
                }
            }
            fun remoteClosed() { remoteGone = true; opened.complete(false); acks.release(); incoming.offer(EOF) }
            override fun close() {
                if (localClosed) return
                localClosed = true
                if (!remoteGone && !closed) runCatching { send(Message(CLSE, localId, remoteId)) }
                streams.remove(localId)
                remoteClosed()
            }
        }

        companion object {
            private const val CNXN = 0x4e584e43; private const val AUTH = 0x48545541
            private const val OPEN = 0x4e45504f; private const val OKAY = 0x59414b4f
            private const val CLSE = 0x45534c43; private const val WRTE = 0x45545257
            private const val STLS = 0x534c5453; private const val VERSION = 0x01000001
            private const val VERSION_SKIP_CHECKSUM = 0x01000001; private const val STLS_VERSION = 0x01000000
            private const val MAX_DATA = 1024 * 1024
            private val EOF = ByteArray(0)
            private val HOST = "host::\u0000".toByteArray(Charsets.US_ASCII)

            fun open(host: String, port: Int, timeout: Int): AdbConnection {
                val plain = Socket()
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(host, port), timeout)
                plain.soTimeout = timeout
                try {
                    val out = plain.getOutputStream()
                    out.write(Message(CNXN, VERSION, MAX_DATA, HOST).encode()); out.flush()
                    val first = Message.read(DataInputStream(plain.getInputStream()), MAX_DATA, false)
                        ?: throw IOException("adbd closed before ADB handshake")
                    if (first.command == AUTH) throw IOException("wireless ADB requested legacy RSA auth")
                    if (first.command != STLS) throw IOException("expected adbd STLS, received ${Message.name(first.command)}")
                    out.write(Message(STLS, STLS_VERSION, 0).encode()); out.flush()
                    val context = SSLContext.getInstance("TLS")
                    context.init(arrayOf(PairingIdentity.keyManager), arrayOf(TrustAll), SecureRandom())
                    val ssl = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                    ssl.enabledProtocols = arrayOf("TLSv1.3"); ssl.useClientMode = true; ssl.startHandshake()
                    val input = DataInputStream(ssl.inputStream)
                    val cnxn = Message.read(input, MAX_DATA, false) ?: throw IOException("adbd rejected the paired TLS identity")
                    if (cnxn.command != CNXN) throw IOException("expected post-TLS CNXN, received ${Message.name(cnxn.command)}")
                    ssl.soTimeout = 0
                    return AdbConnection(ssl, input, ssl.outputStream, cnxn.arg0,
                        cnxn.arg1.takeIf { it in 1..MAX_DATA } ?: 4096, timeout)
                } catch (t: Throwable) { runCatching { plain.close() }; throw t }
            }
        }
    }

    private data class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {
        fun encode(): ByteArray = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(data.sumOf { it.toInt() and 255 })
            .putInt(command.inv()).put(data).array()
        companion object {
            fun read(input: DataInputStream, max: Int, verifyChecksum: Boolean): Message? {
                val h = ByteArray(24)
                try { input.readFully(h) } catch (_: EOFException) { return null }
                val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                val command = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; val sum = b.int; val magic = b.int
                if (magic != command.inv() || len !in 0..max) throw IOException("invalid ADB packet")
                val data = ByteArray(len); input.readFully(data)
                if (verifyChecksum && data.sumOf { it.toInt() and 255 } != sum) throw IOException("invalid ADB checksum")
                return Message(command, a0, a1, data)
            }
            fun name(command: Int) = ByteArray(4) { (command ushr (it * 8)).toByte() }.toString(Charsets.US_ASCII)
        }
    }

    private object TrustAll : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
