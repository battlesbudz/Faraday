package com.battlesbudz.jarvis.v2.ai.storage

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadRecoveryTest {
    private class Host(val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}/model"
        val ranges = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val interrupt = AtomicBoolean(false)
        val wrongRange = AtomicBoolean(false)
        val stall = AtomicBoolean(false)
        /** Fail this many non-probe requests by truncating the body, then serve normally. */
        val failFirst = AtomicInteger(0)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val ignoreRange = AtomicBoolean(false)
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
                thread(isDaemon = true) {
                    socket.use {
                        // Use one reader so its buffering cannot consume subsequent headers.
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val range = lines.firstOrNull { line -> line.startsWith("Range:", true) }
                            ?.substringAfter("bytes=")
                        val partial = range != null && !ignoreRange.get()
                        val start = if (partial) range!!.substringBefore('-').toInt() else 0
                        val end = if (partial) range!!.substringAfter('-').toIntOrNull() ?: bytes.lastIndex else bytes.lastIndex
                        ranges.add(start to end)
                        val probe = start == 0 && end == 0
                        val response = buildString {
                            append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                            if (partial) append("Content-Range: bytes ${if (wrongRange.get() && !probe) start + 1 else start}-$end/${bytes.size}\r\n")
                            append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
                        }
                        runCatching {
                            it.getOutputStream().apply {
                                write(response.toByteArray())
                                val flaky = !probe && (interrupt.get() || failFirst.getAndDecrement() > 0)
                                val size = if (flaky) minOf(123, end - start + 1) else end - start + 1
                                write(bytes, start, size)
                                flush()
                                if (!probe && stall.get()) {
                                    blocked.countDown()
                                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                                }
                            }
                        }
                    }
                }
            }
        }
        override fun close() { release.countDown(); server.close(); worker.join(2000) }
    }

    private fun fixture(block: suspend (Host, File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("model-recovery").toFile()
        try { Host(ByteArray(32005) { (it % 251).toByte() }).use { block(it, root.resolve("model.part")) } }
        finally { root.deleteRecursively() }
    }

    /** Retries in tests back off in milliseconds, not seconds. */
    private fun fastRetry() = RetryPolicy(baseDelayMs = 5L, maxDelayMs = 20L, jitter = { it })

    @Test fun flakyParallelTransferRetriesAutomaticallyAndResumes() = fixture { host, target ->
        host.failFirst.set(1)
        val stalls = mutableListOf<String?>()
        ModelDownloader(1, fastRetry()).download(host.url, target, { _, _ -> }, {}, stalls::add)
        assertArrayEquals(host.bytes, target.readBytes())
        val chunks = target.parentFile.resolve("${target.name}.chunks")
        assertFalse("chunks are cleaned up after a completed download", chunks.exists())
        assertTrue("a truncated chunk must be re-requested from its resume point",
            host.ranges.any { (start, _) -> start > 0 })
        assertTrue("stall status must be surfaced while backing off",
            stalls.any { it?.startsWith("Retrying") == true })
        assertNull("stall clears once bytes flow again", stalls.last())
        host.ranges.clear()
        ModelDownloader(1, fastRetry()).download(host.url, target, { _, _ -> }, {})
        assertEquals("Complete assembly only needs the size probe", listOf(0 to 0), host.ranges)
    }

    @Test fun wrongContentRangeIsRejectedBeforeAppending() = fixture { host, target ->
        target.writeBytes(host.bytes.copyOfRange(0, 3456))
        host.wrongRange.set(true)
        assertTrue(runCatching { ModelDownloader().download(host.url, target, { _, _ -> }, {}) }.isFailure)
        assertEquals(3456L, target.length())
    }

    @Test fun truncatedSingleStreamResumesAutomatically() = fixture { host, target ->
        host.failFirst.set(2)
        val stalls = mutableListOf<String?>()
        ModelDownloader(retryPolicy = fastRetry()).download(host.url, target, { _, _ -> }, {}, stalls::add)
        assertArrayEquals(host.bytes, target.readBytes())
        assertTrue("retries must resume with Range requests past the truncated bytes",
            host.ranges.contains(123 to host.bytes.lastIndex))
        assertEquals("two failures means two retry notices",
            2, stalls.count { it?.startsWith("Retrying") == true })
        assertNull("stall clears once the transfer completes", stalls.last())
    }

    @Test fun fullResponseFallbackReplacesPartialFileExactlyOnce() = fixture { host, target ->
        target.writeBytes(host.bytes.copyOfRange(0, 3456))
        host.ignoreRange.set(true)
        ModelDownloader().download(host.url, target, { _, _ -> }, {})
        assertArrayEquals(host.bytes, target.readBytes())
    }

    @Test fun staleChunkIdentityIsNotReused() = fixture { host, target ->
        val chunks = target.parentFile.resolve("${target.name}.chunks").apply { mkdirs() }
        chunks.resolve("source").writeText("another-model")
        chunks.resolve("0.part").writeBytes(ByteArray((host.bytes.size + 5) / 6))
        ModelDownloader(1).download(host.url, target, { _, _ -> }, {})
        assertArrayEquals(host.bytes, target.readBytes())
    }

    @Test fun cancelledParallelTransferRetainsBytesForRetry() = fixture { host, target ->
        val result = runCatching {
            ModelDownloader(1).download(host.url, target, { count, _ ->
                if (count > 0) throw kotlinx.coroutines.CancellationException("Test interruption")
            }, {})
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val chunks = target.parentFile.resolve("${target.name}.chunks")
        assertTrue(chunks.listFiles().orEmpty().any { it.extension == "part" && it.length() > 0 })
        ModelDownloader(1).download(host.url, target, { _, _ -> }, {})
        assertArrayEquals(host.bytes, target.readBytes())
    }
    @Test fun cancellingStalledNetworkReadClosesConnectionAndRetainsCheckpoint() = fixture { host, target ->
        host.interrupt.set(true)
        host.stall.set(true)
        val written = java.util.concurrent.CountDownLatch(1)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch(kotlinx.coroutines.Dispatchers.IO) {
            ModelDownloader().download(host.url, target, { bytes, _ -> if (bytes > 0) written.countDown() }, {})
        }
        assertTrue(host.blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Cancel only after the downloader has persisted resumable bytes",
            written.await(5, java.util.concurrent.TimeUnit.SECONDS))
        kotlinx.coroutines.withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(target.length() > 0)
        host.stall.set(false)
        host.interrupt.set(false)
        host.release.countDown()
        ModelDownloader().download(host.url, target, { _, _ -> }, {})
        assertArrayEquals(host.bytes, target.readBytes())
    }

}
