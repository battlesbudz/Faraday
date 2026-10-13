package com.battlesbudz.jarvis.v2.voice

/** Admission for input prefill during listening; final recognition/replies remain committed work. */
class VoiceWorkScheduler {
    var reason = "ready"
        private set
    /** Incremental prefill preserves state; it does not need restart/draft cooldowns. */
    @Synchronized fun admitPrefill(backlogMs: Long, thermalStatus: Int): Boolean {
        reason = when {
            thermalStatus >= 5 -> "thermal_emergency"
            backlogMs > 200 -> "asr_backlog"
            else -> "ready"
        }
        return reason == "ready"
    }
}
