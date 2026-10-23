package com.battlesbudz.jarvis.v2.voice

/** Audio age includes capture backlog, native work and the word confirmation interval. */
object InterruptionTiming {
    const val LOAD_MS = 1200L
    const val CANDIDATE_INPUT_MS = 3400L
    const val DECODE_MS = 1600L
    const val START_AGE_MS = 300L
    const val RESULT_AGE_MS = LOAD_MS + DECODE_MS + START_AGE_MS + 200L
    const val CONFIRM_AGE_MS = RESULT_AGE_MS + 400L
    const val CANDIDATE_RETAIN_MS = CANDIDATE_INPUT_MS + CONFIRM_AGE_MS + 400L
    const val RECOVERY_QUIET_MS = 500L
    /** One probe submission is bounded by the recognizer's own clip limit:
     *  BoundedInterruptionRecognizer rejects audio over 128_000 bytes (4000 ms)
     *  and prepares the probe engine for at most 4000 ms. A longer candidate is
     *  probed through its newest window; the candidate retains the whole episode. */
    const val PROBE_WINDOW_MS = 4000L
    /** Silence that ends a speech episode for retention purposes. Matches the
     *  existing dead-candidate scale (~800 ms) with margin for brief pauses. */
    const val EPISODE_GAP_MS = 1000L
}
