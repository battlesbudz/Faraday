package com.battlesbudz.jarvis.v2.voice.smartturn

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SmartTurnModelStoreTest {
    @Test fun missingAndWrongSizeAreUnavailableWithoutNetwork() {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        try {
            val store = SmartTurnModelStore(directory) { _, _, _ -> error("readiness must not download") }
            assertNull(store.availableFile())
            File(directory, SmartTurnModelSpec.FILE_NAME).writeText("not a model")
            assertNull(store.availableFile())
            assertEquals(NativeSmartTurnBackend.MODEL_SHA256, SmartTurnModelSpec.SHA256)
            assertEquals(NativeSmartTurnBackend.MODEL_BYTES, SmartTurnModelSpec.BYTES)
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedIntegrityNeverInstallsAndRemovesUnverifiedTemporary() = runBlocking {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        try {
            val store = SmartTurnModelStore(directory) { file, progress, _ ->
                file.writeText("bad bytes"); progress(file.length(), SmartTurnModelSpec.BYTES)
            }
            try { store.ensureReady(); fail("Invalid model admitted") } catch (_: IllegalStateException) {}
            assertNull(store.availableFile()); assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun unexpectedDownloadSizeFailsBeforeInstallation() = runBlocking {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        try {
            val store = SmartTurnModelStore(directory) { _, progress, _ -> progress(0, SmartTurnModelSpec.BYTES + 1) }
            try { store.ensureReady(); fail("Unexpected size admitted") } catch (_: IllegalStateException) {}
            assertNull(store.availableFile())
        } finally { directory.deleteRecursively() }
    }
    @Test fun setupCancellationPropagatesAndDoesNotAdmitPartialModel() = runBlocking {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        try {
            val store = SmartTurnModelStore(directory) { file, _, _ ->
                file.writeText("partial"); throw CancellationException("fixture")
            }
            try { store.ensureReady(); fail("Cancellation swallowed") } catch (_: CancellationException) {}
            assertNull(store.availableFile()); assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun sameSizeCorruptFileStillFailsNativeIdentityBeforeLoadingLibrary() {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        try {
            val file = File(directory, SmartTurnModelSpec.FILE_NAME)
            java.io.RandomAccessFile(file, "rw").use { it.setLength(SmartTurnModelSpec.BYTES) }
            assertThrows(IllegalArgumentException::class.java) { NativeSmartTurnBackend(file) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun repeatedSetupAttemptsRemainSerializedAfterFailure() = runBlocking {
        val directory = Files.createTempDirectory("smart-turn-store").toFile()
        val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        try {
            val store = SmartTurnModelStore(directory) { file, _, _ ->
                val running = active.incrementAndGet(); peak.updateAndGet { maxOf(it, running) }; calls.incrementAndGet()
                try { file.writeText("partial"); delay(20); error("fixture transport failure") }
                finally { active.decrementAndGet() }
            }
            val attempts = List(2) { async { runCatching { store.ensureReady() } } }.awaitAll()
            assertTrue(attempts.all { it.isFailure }); assertEquals(2, calls.get()); assertEquals(1, peak.get())
            assertNull(store.availableFile()); assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

}
