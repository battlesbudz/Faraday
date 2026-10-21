package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.harness.HarnessConfig

/** Shared by speculative and final voice generation; no additional inference pass. */
object VoiceResponsePolicy {
    /**
     * Editable harness content (self-improving harness project, Phase 1):
     * the voice instruction wording lives in the harness files now. The
     * runtime points this provider at its HarnessStore; everywhere else
     * (tests, previews) the compiled-in default applies, which is
     * byte-identical to the previously hardcoded text.
     */
    @Volatile
    var configProvider: () -> HarnessConfig = { HarnessConfig.DEFAULT }

    val instructions: String get() = configProvider().voiceInstructions
}
