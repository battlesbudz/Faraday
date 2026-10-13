package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Retires native work before replacing session state; duplicate taps cannot start two calls. */
class VoiceCallResumer(private val sessions: VoiceSessionController) {
    private val lock = Mutex()

    suspend fun resume(call: VoiceCallRecord, retireAudio: suspend () -> Unit): Result<VoiceCallRecord> {
        if (!lock.tryLock()) return Result.failure(IllegalStateException("A call is already being resumed."))
        return try {
            retireAudio()
            if (sessions.currentCallId() != null) sessions.end()
            Result.success(sessions.resumeCall(call))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            lock.unlock()
        }
    }
}
