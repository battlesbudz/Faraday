package com.battlesbudz.jarvis.v2.voice

/**
 * Observes exactly the PCM16 mono bytes retained for one candidate request.
 *
 * Calls are ordered on the capture collector. [onPcm] transfers a private copy:
 * it includes the accepted pre-roll once, then each retained chunk once. It is
 * not a hardware callback and must not run native inference or wait for it.
 * Implementations may enqueue into a bounded worker queue; admission failure
 * must throw rather than dropping audio or silently submitting a prefix.
 *
 * Discarding a candidate invalidates all its provisional state. A replacement
 * must have a fresh encoder/identity, never reset a possibly consumed native
 * session. Invalidated captures cannot produce a sealed input. Cancellation and
 * checked worker drain remain the owning turn's responsibility.
 *
 * There is deliberately no seal callback. The turn must join capture via stop,
 * verify the complete retained PCM accounting, then seal on its encoder worker.
 * Neither these bytes nor provisional model output authorize a phone effect.
 */
interface RetainedPcmObserver {
    fun onPcm(retainedPcm16: ByteArray)
    fun onCandidateDiscarded()
    fun onCaptureInvalidated(reason: Invalidation)

    enum class Invalidation { WINDOW_ROLLED, AUDIO_LIMIT, CAPTURE_FAILED, CANCELLED }
}
