package com.battlesbudz.jarvis.v2.memory

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEmbeddingSetupTest {
    private val payload = ByteArray(4_096) { (it % 251).toByte() }
    private val spec = EmbeddingModelSpec(
        id = "test-embedder",
        fileName = "test-embedder.litertlm",
        downloadUrl = "https://example.invalid/test-embedder.litertlm",
        expectedSha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        downloadBytes = payload.size.toLong(),
        outputDimension = 768,
        storedDimension = 256,
    )

    private fun newStore(behavior: (File) -> Unit, calls: AtomicInteger = AtomicInteger()): Pair<File, EmbeddingModelStore> {
        val directory = Files.createTempDirectory("embedding-setup").toFile()
        val transfer = EmbeddingTransfer { _, temporary, _, onProgress ->
            calls.incrementAndGet()
            behavior(temporary)
            onProgress(payload.size.toLong(), payload.size.toLong())
        }
        return directory to EmbeddingModelStore(directory, transfer)
    }

    @Test fun installActionInvokesAcquisitionWithCatalogSpecAndTriggersSync() = runBlocking {
        var syncs = 0
        var seenUrl: String? = null
        var seenSha: String? = null
        val store = EmbeddingModelStore(Files.createTempDirectory("embedding-setup-probe").toFile(), EmbeddingTransfer { url, temporary, sha, _ ->
            seenUrl = url; seenSha = sha; temporary.writeBytes(payload)
        })
        val controller = MemoryEmbeddingSetupController(store, spec) { syncs++ }
        assertEquals(MemoryEmbeddingSetupState.NotInstalled(0L, spec.downloadBytes), controller.state())
        val result = controller.install()
        assertEquals(spec.downloadUrl, seenUrl)
        assertEquals(spec.expectedSha256, seenSha)
        assertTrue(result is MemoryEmbeddingSetupState.Installed)
        assertEquals(1, syncs)
        assertEquals(MemoryEmbeddingSetupState.Installed(spec.downloadBytes), controller.state())
    }

    @Test fun completedInstallIsReusedWithoutASecondTransfer() = runBlocking {
        val calls = AtomicInteger()
        val (_, store) = newStore({ it.writeBytes(payload) }, calls)
        var syncs = 0
        val controller = MemoryEmbeddingSetupController(store, spec) { syncs++ }
        controller.install()
        controller.install()
        assertEquals(1, calls.get())
        // Reuse still counts as a completed install: the follow-up sync is idempotent.
        assertEquals(2, syncs)
    }

    @Test fun failedInstallIsHonestAndTriggersNoSync() = runBlocking {
        var syncs = 0
        val (_, store) = newStore({ throw IOException("offline") })
        val controller = MemoryEmbeddingSetupController(store, spec) { syncs++ }
        val result = controller.install()
        assertTrue(result is MemoryEmbeddingSetupState.Failed)
        assertEquals("Embedding model download failed (IOException).", (result as MemoryEmbeddingSetupState.Failed).message)
        assertEquals(0, syncs)
        assertTrue(controller.state() is MemoryEmbeddingSetupState.NotInstalled)
    }

    @Test fun integrityMismatchIsReportedAndPartialDiscarded() = runBlocking {
        val (_, store) = newStore({ it.writeBytes(ByteArray(payload.size) { 7 }) })
        val controller = MemoryEmbeddingSetupController(store, spec) {}
        val result = controller.install()
        assertTrue(result is MemoryEmbeddingSetupState.Failed)
        assertEquals("Embedding model download did not match its integrity pin.", (result as MemoryEmbeddingSetupState.Failed).message)
        assertEquals(MemoryEmbeddingSetupState.NotInstalled(0L, spec.downloadBytes), controller.state())
    }

    @Test fun partialDownloadSurfacesAsSavedProgress() {
        val (directory, store) = newStore({})
        File(directory, "${spec.fileName}.part").writeBytes(ByteArray(123))
        val controller = MemoryEmbeddingSetupController(store, spec) {}
        assertEquals(MemoryEmbeddingSetupState.NotInstalled(123L, spec.downloadBytes), controller.state())
    }

    @Test fun schedulerCoalescesRequestsAndRunsOffTheCallerPath() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val runs = AtomicInteger()
        val scheduler = MemoryEmbeddingSyncScheduler(
            scope = CoroutineScope(Dispatchers.Default),
            pollIntervalMs = 5, maxPolls = 100,
            sync = { runs.incrementAndGet(); started.countDown(); release.await(10, TimeUnit.SECONDS); finished.countDown() },
        )
        scheduler.request(); scheduler.request(); scheduler.request()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(1, runs.get())
    }

    @Test fun schedulerDefersWhileBusyThenRunsOnALaterTrigger() {
        val idle = java.util.concurrent.atomic.AtomicBoolean(false)
        val ran = CountDownLatch(1)
        val scheduler = MemoryEmbeddingSyncScheduler(
            scope = CoroutineScope(Dispatchers.Default),
            idleProbe = { idle.get() },
            pollIntervalMs = 5, maxPolls = 4,
            sync = { ran.countDown() },
        )
        scheduler.request()
        Thread.sleep(150) // bounded window elapses while never idle: trigger dropped
        idle.set(true)
        scheduler.request()
        assertTrue(ran.await(10, TimeUnit.SECONDS))
    }

    @Test fun schedulerSyncNowRunsImmediatelyAndSwallowsFailures() = runBlocking {
        val attempts = AtomicInteger()
        val scheduler = MemoryEmbeddingSyncScheduler(
            scope = CoroutineScope(Dispatchers.Default),
            idleProbe = { false },
            pollIntervalMs = 5, maxPolls = 2,
            sync = { if (attempts.incrementAndGet() == 1) throw IllegalStateException("boom") },
        )
        scheduler.syncNow() // throws internally by design; must not propagate
        assertEquals(1, attempts.get())
        scheduler.syncNow()
        assertEquals(2, attempts.get())
        Unit
    }
}
