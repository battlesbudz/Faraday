package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.voice.GemmaStreamingAudioCapture
import java.util.IdentityHashMap
import com.battlesbudz.jarvis.v2.work.ProcessConversationAdmission

/** Failed joins retain only native owners and their operation lease, never the
 * whole capture/turn containing raw PCM. The still-owned process model gate
 * prevents another turn from allocating replacement models. No automatic replay
 * or poisoned owner reuse; a process restart is the terminal recovery boundary. */
internal object NativeVoiceQuarantine {
    private data class Held(val capture: GemmaStreamingAudioCapture?, val engine: LiteRtLmEngine?)
    private val held = IdentityHashMap<VoiceTurnModelLease, Held>()
    private var admissionRetained = false
    @Synchronized fun retain(lease: VoiceTurnModelLease, capture: GemmaStreamingAudioCapture?, engine: LiteRtLmEngine?) {
        if (!admissionRetained) {
            ProcessConversationAdmission.activeJobs.incrementAndGet()
            admissionRetained = true
        }
        held[lease] = Held(capture, engine)
    }
}
