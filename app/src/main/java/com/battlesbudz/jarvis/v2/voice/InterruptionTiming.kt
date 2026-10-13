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
}
