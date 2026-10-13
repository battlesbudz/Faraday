package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.VoicePrefillSession

/** Non-Gemma bundles own their chat template. Buffer text; never inject Gemma delimiters. */
internal class TemplateVoiceSession(
    private val generate: suspend (String, (String) -> Unit) -> GenerationResult
) : VoicePrefillSession {
    private val prompt = StringBuilder()
    private var closed = false
    private var decoded = false

    override fun append(text: String) {
        check(!closed && !decoded)
        prompt.append(text)
    }

    override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
        check(!closed && !decoded)
        decoded = true
        return generate(prompt.toString(), onToken)
    }

    override fun close() { closed = true; prompt.clear() }
}
