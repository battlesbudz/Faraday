package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.chat.AttachmentPolicy
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import java.io.InputStream

/** Typed inference input selection, including the one permitted incremental-to-text fallback. */
internal class ConversationInput(
    private val voiceAudio: ByteArray?,
    private val directAudio: Boolean,
    private val textInput: IncrementalVoiceInput?,
    val imageBytes: ByteArray?,
    val attachedAudio: ByteArray?,
    private val sealedVoiceAudio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings? = null,
    private val nativeSpeculation: com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation? = null
) {
    init {
        require(sealedVoiceAudio == null || directAudio && voiceAudio != null && textInput == null) {
            "Sealed native audio requires one complete direct-audio request"
        }
    }
    var incrementalFallbackUsed = false
        private set
    private var speculativeResultUsed = false
    val nativeConversationContainsTurn: Boolean get() = !speculativeResultUsed && (textInput == null || incrementalFallbackUsed)

    suspend fun generate(engine: ConversationBackend, prompt: String, onToken: (String) -> Unit,
                         onIncrementalFallback: (Throwable) -> Unit): GenerationResult = when {
        directAudio -> {
            val prepared = nativeSpeculation?.promote(prompt, ordinaryReplyAccepted = true, onToken = onToken)
            if (prepared != null) { speculativeResultUsed = true; prepared }
            else if (sealedVoiceAudio != null) engine.generateSealedAudio(prompt, sealedVoiceAudio, onToken)
            else engine.generateAudio(prompt, requireNotNull(voiceAudio), onToken)
        }
        textInput != null -> {
            engine.onPromptSubmitted(prompt, 0)
            textInput.answerWithTextFallback(prompt, onToken) { error ->
                onIncrementalFallback(error)
                incrementalFallbackUsed = true
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
                engine.generate(prompt, onToken)
            }
        }
        // Retained voice audio accompanies ASR text but must not override that mode.
        voiceAudio != null -> engine.generate(prompt, onToken)
        else -> retry(engine, prompt, onToken)
    }

    /** Retry keeps the authoritative direct-audio/image/file attachment, never a spent prefill. */
    suspend fun retry(engine: ConversationBackend, prompt: String, onToken: (String) -> Unit): GenerationResult {
        speculativeResultUsed = false
        return when {
        directAudio -> if (sealedVoiceAudio != null) engine.generateSealedAudio(prompt, sealedVoiceAudio, onToken)
            else engine.generateAudio(prompt, requireNotNull(voiceAudio), onToken)
        attachedAudio != null -> engine.generateAudio(prompt, attachedAudio, onToken)
        imageBytes != null -> engine.generate(prompt, imageBytes, onToken)
        else -> engine.generate(prompt, onToken)
        }
    }
}

internal data class ConversationAttachments(val image: ByteArray?, val audio: ByteArray?)

internal fun readConversationAttachments(imageUri: Uri?, audioUri: Uri?,
                                        benchmark: PipelineBenchmarkCapture,
                                        open: (Uri) -> InputStream?): ConversationAttachments {
    val started = System.nanoTime()
    if (imageUri != null || audioUri != null) benchmark.mark("attachment_preparation_started")
    val image = imageUri?.let { uri ->
        open(uri)?.use(AttachmentPolicy::readBounded)
            ?: error("The selected image could not be read.")
    }
    val audio = audioUri?.let { uri ->
        open(uri)?.use(AttachmentPolicy::readBounded)
            ?.also(AttachmentPolicy::validateAudio)
            ?: error("The selected audio could not be read.")
    }
    if (imageUri != null || audioUri != null) {
        benchmark.mark("attachment_preparation_finished")
        benchmark.metric("attachment_preparation_ms", (System.nanoTime() - started) / 1_000_000)
        benchmark.metric("submitted_image_bytes", image?.size)
        benchmark.metric("submitted_audio_file_bytes", audio?.size)
        benchmark.configuration("native_multimodal_encoder_timing", "unavailable_in_sdk")
    }
    return ConversationAttachments(image, audio)
}

/** Content providers may offer only an asset descriptor; preserve both original resolver paths. */
internal fun openConversationAttachment(primary: () -> InputStream?, fallback: () -> InputStream?): InputStream? =
    runCatching { primary() }.getOrNull() ?: runCatching { fallback() }.getOrNull()
