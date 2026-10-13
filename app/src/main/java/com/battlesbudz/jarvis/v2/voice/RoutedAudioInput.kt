package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The hardware reader, not a turn consumer, owns the route's lifetime. */
internal class RoutedAudioInput(
    private val source: AudioInput,
    private val acquire: suspend () -> AutoCloseable
) : AudioInput by source {
    private var lease: AutoCloseable? = null
    private var started = false
    override suspend fun start() {
        if (started) return
        try {
            lease = acquire()
            source.start()
            started = true
        } catch (error: Throwable) {
            try { stop() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    override suspend fun stop() = withContext(NonCancellable) {
        try { source.stop() } finally {
            val owned = lease
            lease = null
            started = false
            owned?.close()
        }
    }
}
