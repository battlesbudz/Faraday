package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.voice.VoicePrefillSession

/** Non-Gemma bundles own their chat template. Buffer text; never inject Gemma delimiters. */
internal class TemplateVoiceSession(
    private val generate: suspend (String, ByteArray?, (String) -> Unit) -> GenerationResult
) : VoicePrefillSession {
    private val prompt = StringBuilder()
    private var imageBytes: ByteArray? = null
    private var closed = false
    private var decoded = false

    override fun append(text: String) {
        check(!closed && !decoded)
        prompt.append(text)
    }

    /**
     * Snapshot synthesis: buffer the turn's one image for [decode]; the
     * generate lambda submits it with the buffered prompt through the
     * conversation route's image contents.
     */
    override fun appendImage(imageBytes: ByteArray) {
        check(!closed && !decoded)
        check(this.imageBytes == null) { "Only one snapshot image per turn" }
        this.imageBytes = imageBytes
    }

    override suspend fun decode(onToken: (String) -> Unit): GenerationResult {
        check(!closed && !decoded)
        decoded = true
        return generate(prompt.toString(), imageBytes, onToken)
    }

    override fun close() { closed = true; prompt.clear(); imageBytes = null }
}
