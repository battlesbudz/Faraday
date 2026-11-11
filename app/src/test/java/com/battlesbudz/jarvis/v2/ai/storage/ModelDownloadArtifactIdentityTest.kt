package com.battlesbudz.jarvis.v2.ai.storage

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The chunk checkpoint identity binds the catalog SHA-256: bytes saved for
 * one pinned artifact are never resumed into a different artifact that
 * happens to share its URL and length (an upstream re-pin), while a resume
 * for the same artifact keeps working.
 */
class ModelDownloadArtifactIdentityTest {
    /** Range-correct host whose served bytes can be swapped mid-test (a re-pin). */
    private class RepinHost(initial: ByteArray) : AutoCloseable {
        val content = AtomicReference(initial)
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/model"
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                thread(isDaemon = true) {
                    socket.use {
                        val bytes = content.get()
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val range = lines.firstOrNull { line -> line.startsWith("Range:", true) }
                            ?.substringAfter("bytes=")
                        val partial = range != null
                        val start = if (partial) range!!.substringBefore('-').toInt() else 0
                        val end = if (partial) range!!.substringAfter('-').toIntOrNull() ?: bytes.lastIndex
                        else bytes.lastIndex
                        val response = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                            append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                        }
                        runCatching {
                            it.getOutputStream().apply {
                                write(response.toByteArray())
                                write(bytes, start, end - start + 1)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() { server.close(); worker.join(2000) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Retries in tests back off in milliseconds, not seconds. */
    private fun fastRetry() = RetryPolicy(baseDelayMs = 5L, maxDelayMs = 20L, jitter = { it })

    private fun bytesA() = ByteArray(32005) { (it % 251).toByte() }
    private fun bytesB() = ByteArray(32005) { ((it * 31 + 7) % 251).toByte() }

    /** Starts a parallel download and cancels it from the progress callback,
     *  leaving real checkpoint state on disk exactly as an interrupted
     *  setup would. Returns the target file. */
    private fun cancelMidDownload(host: RepinHost, target: File, sha256: String) {
        val result = runCatching {
            runBlocking {
                ModelDownloader(1, fastRetry()).download(host.url, target,
                    { count, _ -> if (count > 0) throw CancellationException("Test interruption") },
                    {}, {}, sha256)
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        val chunks = target.parentFile.resolve("${target.name}.chunks")
        assertTrue("the interrupted download must retain chunk checkpoints",
            chunks.listFiles().orEmpty().any { it.extension == "part" && it.length() > 0 })
    }

    @Test fun repinnedArtifactNeverResumesStaleChunks() {
        val root = Files.createTempDirectory("model-repin").toFile()
        try {
            val a = bytesA()
            val b = bytesB()
            assertFalse("the re-pin fixture must differ from the original bytes", a.contentEquals(b))
            RepinHost(a).use { host ->
                val target = root.resolve("model.part")
                cancelMidDownload(host, target, sha256Hex(a))
                // Upstream re-pins the same URL and length to different bytes.
                host.content.set(b)
                runBlocking {
                    ModelDownloader(1, fastRetry()).download(host.url, target, { _, _ -> }, {}, {}, sha256Hex(b))
                }
                assertArrayEquals("a different pinned hash must restart, never splice stale chunks",
                    b, target.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun sameArtifactResumeStillUsesCheckpoints() {
        val root = Files.createTempDirectory("model-resume").toFile()
        try {
            val a = bytesA()
            RepinHost(a).use { host ->
                val target = root.resolve("model.part")
                cancelMidDownload(host, target, sha256Hex(a))
                runBlocking {
                    ModelDownloader(1, fastRetry()).download(host.url, target, { _, _ -> }, {}, {}, sha256Hex(a))
                }
                assertArrayEquals(a, target.readBytes())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
