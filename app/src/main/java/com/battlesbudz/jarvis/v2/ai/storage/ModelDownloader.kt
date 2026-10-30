package com.battlesbudz.jarvis.v2.ai.storage

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/**
 * Automatic-retry policy for model transfers. Transient network failures
 * (timeouts, drops, resets, truncated streams) retry with exponential backoff
 * until the transfer finishes — the user never has to babysit a flaky
 * connection. Permanent failures (bad HTTP status, wrong byte ranges, corrupt
 * checkpoints) fail fast, and cancellation is never retried.
 */
internal class RetryPolicy(
    val baseDelayMs: Long = 2_000L,
    val maxDelayMs: Long = 300_000L,
    val multiplier: Double = 2.0,
    private val jitter: (Long) -> Long = { delay ->
        (delay * (0.75 + Math.random() * 0.5)).toLong().coerceIn(0L, maxOf(delay, 1L))
    }
) {
    /** Backoff before attempt N (1-based): base, base*multiplier, …, capped at max. */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        var delay = baseDelayMs.toDouble()
        repeat(attempt - 1) { delay = (delay * multiplier).coerceAtMost(maxDelayMs.toDouble()) }
        return jitter(delay.toLong()).coerceIn(0L, maxDelayMs)
    }

    fun isRetryable(error: Throwable): Boolean = when {
        error is CancellationException -> false
        error is java.io.IOException -> true
        else -> false
    }
}

/** Resumable HTTP transfer with automatic retry; the store owns identity, hashes and atomic installation. */
internal class ModelDownloader(
    private val parallelThreshold: Long = 128L * 1024L * 1024L,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    private companion object {
        const val PARALLEL_CHUNKS = 6
        const val CONNECT_TIMEOUT_MS = 10_000
        // Flaky mobile links stall for seconds at a time; a short read timeout
        // just churns retries, while resume makes a long timeout cheap.
        const val READ_TIMEOUT_MS = 30_000
    }
    /**
     * Downloads large model files using resumable HTTP ranges, retrying
     * transient failures automatically with exponential backoff until the
     * transfer finishes. Several ranges are fetched concurrently when the host
     * supports Range requests; each range has its own checkpoint file so an
     * interrupted setup resumes without discarding completed work.
     *
     * @param onStall carries a human-readable "Retrying in Xs… (attempt N)"
     * message while backing off, and null once bytes are flowing again, so
     * the UI and the notification can show the download is waiting, not dead.
     */
    suspend fun download(
        url: String,
        temporary: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStatus: (String) -> Unit,
        onStall: (String?) -> Unit = {},
        expectedSha256: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val totalBytes = retryTransfer("size probe", onStall) { discoverDownloadSize(url) }
        if (totalBytes > 0L && temporary.length() == totalBytes) {
            onProgress(totalBytes, totalBytes)
            return@withContext
        }
        if (totalBytes <= parallelThreshold) {
            retryTransfer("download", onStall) {
                downloadSingleStream(url, temporary, totalBytes, onProgress)
            }
            return@withContext
        }

        val chunkDirectory = File(temporary.parentFile, "${temporary.name}.chunks")
        val chunkSize = (totalBytes + PARALLEL_CHUNKS - 1L) / PARALLEL_CHUNKS
        val progressLock = Any()
        val completedBytes = AtomicLong(0L)
        val checkpoint = File(chunkDirectory, "source")
        val identity = downloadIdentity(url, totalBytes, expectedSha256)
        if (checkpoint.takeIf { it.isFile }?.readText() != identity) chunkDirectory.deleteRecursively()
        check(chunkDirectory.isDirectory || chunkDirectory.mkdirs()) { "Could not create model download storage." }
        checkpoint.writeText(identity)
        chunkDirectory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { file ->
            val index = file.name.removeSuffix(".part").toIntOrNull()
            val expected = if (index != null && index in 0 until PARALLEL_CHUNKS)
                minOf(chunkSize, (totalBytes - index * chunkSize).coerceAtLeast(0L)) else 0L
            if (expected == 0L || file.length() > expected) file.delete()
            else completedBytes.addAndGet(file.length())
        }
        // Keep a complete assembled file for verification after process death.
        // Do not discard saved chunks on failure/cancellation.
        onStatus("Downloading the AI model in $PARALLEL_CHUNKS resumable parts…")
        onProgress(completedBytes.get(), totalBytes)

        coroutineScope {
            (0 until PARALLEL_CHUNKS).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    if (start >= totalBytes) return@async
                    val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                    val part = File(chunkDirectory, "$index.part")
                    downloadRangeResilient(
                        url = url,
                        chunkStart = start,
                        end = end,
                        totalBytes = totalBytes,
                        part = part,
                        onBytes = { count ->
                            val current = completedBytes.addAndGet(count)
                            synchronized(progressLock) { onProgress(current, totalBytes) }
                        },
                        onStall = onStall
                    )
                }
            }.awaitAll()
        }
        onStatus("Assembling the downloaded AI model…")
        FileOutputStream(temporary).use { output ->
            for (index in 0 until PARALLEL_CHUNKS) {
                currentCoroutineContext().ensureActive()
                val start = index * chunkSize
                if (start >= totalBytes) break
                val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                val part = File(chunkDirectory, "$index.part")
                check(part.length() == end - start + 1L) { "Model download part $index is incomplete." }
                part.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
            output.fd.sync()
        }
        check(temporary.length() == totalBytes) { "Model download size is incorrect." }
        chunkDirectory.deleteRecursively()
        Unit
    }

    /**
     * Runs [block] until it succeeds. Transient network errors back off
     * exponentially and resume from persisted checkpoints; permanent failures
     * and cancellation propagate immediately.
     */
    private suspend fun <T> retryTransfer(
        what: String,
        onStall: (String?) -> Unit,
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                val result = block()
                if (attempt > 0) onStall(null)
                return result
            } catch (error: CancellationException) {
                onStall(null)
                throw error
            } catch (error: Exception) {
                // Never swallow cancellation: rethrow before classifying.
                currentCoroutineContext().ensureActive()
                if (!retryPolicy.isRetryable(error)) {
                    onStall(null)
                    throw error
                }
                attempt++
                val waitMs = retryPolicy.delayForAttempt(attempt)
                val reason = error.message?.trim()?.take(90)?.takeIf { it.isNotEmpty() }
                    ?: error.javaClass.simpleName
                onStall("Retrying $what in ${waitMs / 1000}s… (attempt $attempt — $reason)")
                delay(waitMs)
            }
        }
    }

    /** Retries one chunk range, re-reading the checkpoint so each attempt resumes. */
    private suspend fun downloadRangeResilient(
        url: String,
        chunkStart: Long,
        end: Long,
        totalBytes: Long,
        part: File,
        onBytes: (Long) -> Unit,
        onStall: (String?) -> Unit
    ) {
        val expected = end - chunkStart + 1L
        retryTransfer("part download", onStall) {
            // A previous attempt may have persisted more bytes; resume from there.
            check(part.length() <= expected) { "Model download part is larger than expected." }
            val resumeStart = chunkStart + part.length()
            if (resumeStart <= end) {
                downloadRange(url, resumeStart, end, totalBytes, part, onBytes)
            }
            if (part.length() != expected) throw EOFException("Model download part is incomplete.")
        }
    }

    private suspend fun discoverDownloadSize(url: String): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) return -1L
            val range = connection.getHeaderField("Content-Range") ?: return -1L
            range.substringAfterLast("/").toLongOrNull() ?: -1L
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        temporary: File,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val existingBytes = temporary.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            check(responseCode in 200..299) { "Model download failed with HTTP $responseCode." }
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val range = parseRange(connection.getHeaderField("Content-Range"))
                check(range.first == if (append) existingBytes else 0L) { "The model host returned the wrong byte range." }
                check(totalBytes <= 0L || range.third == totalBytes) { "The model size changed during download." }
            }
            val startingBytes = if (append) existingBytes else 0L
            if (!append && existingBytes > 0L) temporary.delete()
            val resolvedTotal = totalBytes.takeIf { it > 0L }
                ?: connection.contentLengthLong.takeIf { it > 0L }?.let { it + startingBytes }
                ?: -1L
            var downloadedBytes = startingBytes
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        check(resolvedTotal <= 0L || downloadedBytes <= resolvedTotal) { "Model download exceeds its expected size." }
                        onProgress(downloadedBytes, resolvedTotal)
                    }
                    output.fd.sync()
                }
            }
            if (resolvedTotal > 0L && downloadedBytes != resolvedTotal) {
                // Truncated stream: retryable, resumes from the persisted bytes.
                throw EOFException("The model download is incomplete. Retry to resume it.")
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private suspend fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        totalBytes: Long,
        part: File,
        onBytes: (Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=$start-$end")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "The model host does not support resumable range downloads."
            }
            val range = parseRange(connection.getHeaderField("Content-Range"))
            check(range.first == start && range.second == end && range.third == totalBytes) { "The model host returned the wrong byte range." }
            val expectedBytes = end - start + 1L
            var received = 0L
            part.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(part, true).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (count == 0) continue
                        received += count
                        check(received <= expectedBytes) { "The model host returned too many bytes." }
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                    output.fd.sync()
                }
            }
            if (received != expectedBytes) {
                // Truncated range: retryable, resumes from the checkpoint file.
                throw EOFException("The model download is incomplete. Retry to resume it.")
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    /**
     * Checkpoint identity for the resumable chunks. The saved bytes are only
     * trusted for the exact artifact they were fetched for: alongside the URL
     * and total size, the identity binds a short form of the catalog SHA-256
     * when the caller pins one. A re-pinned file at the same URL with the
     * same length but different bytes therefore wipes the stale chunks and
     * restarts, instead of resuming them into a spliced file that can only
     * fail verification after a full transfer. Callers without a pinned hash
     * keep the original identity unchanged.
     */
    private fun downloadIdentity(url: String, totalBytes: Long, expectedSha256: String?): String {
        val hash = expectedSha256?.trim()?.lowercase()?.take(16)?.takeIf { it.isNotEmpty() }
        return if (hash == null) "$url\n$totalBytes\n$PARALLEL_CHUNKS"
        else "$url\n$totalBytes\n$PARALLEL_CHUNKS\n$hash"
    }

    private suspend fun disconnectOnCancellation(connection: HttpURLConnection): Job =
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }

    private fun parseRange(value: String?): Triple<Long, Long, Long> {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty())
            ?: error("The model host returned an invalid byte range.")
        return Triple(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
    }

}
