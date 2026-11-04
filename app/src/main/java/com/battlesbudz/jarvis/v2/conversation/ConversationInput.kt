package com.battlesbudz.jarvis.v2.conversation

import android.net.Uri
import com.battlesbudz.jarvis.v2.ai.GenerationResult
import com.battlesbudz.jarvis.v2.chat.AttachmentPolicy
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkPurpose
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import java.io.InputStream

/**
 * A snapshot turn's image could not be delivered on any native route. The
 * generation owner substitutes the honest disclosure answer: a scene
 * description generated from the detector labels alone is never spoken for
 * an image the model did not receive.
 */
internal class SnapshotImageUndeliverableException(cause: Throwable) :
    Exception("Snapshot image undeliverable: ${cause.message}", cause)

/** Typed inference input selection, including the one permitted incremental-to-text fallback. */
internal class ConversationInput(
    private val voiceAudio: ByteArray?,
    private val directAudio: Boolean,
    private val textInput: IncrementalVoiceInput?,
    val imageBytes: ByteArray?,
    val attachedAudio: ByteArray?,
    private val sealedVoiceAudio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings? = null,
    private val nativeSpeculation: com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation? = null,
    private val primedAudio: com.battlesbudz.jarvis.v2.voice.PrimedAudioInput? = null
) {
    init {
        require(sealedVoiceAudio == null || directAudio && voiceAudio != null && textInput == null) {
            "Sealed native audio requires one complete direct-audio request"
        }
        require(primedAudio == null || sealedVoiceAudio != null) {
            "Primed audio requires the turn's sealed native audio"
        }
    }
    /** True when this turn carries audio the model is expected to hear:
     * direct voice audio (raw or sealed) or an attached audio file. */
    val carriesAudioInput: Boolean get() = directAudio || attachedAudio != null
    var incrementalFallbackUsed = false
        private set
    /**
     * Snapshot turns only: true when an image-bearing generation route
     * accepted this turn's image for the answer — the incremental session's
     * preprocessed submission, or the conversation-route regeneration. A
     * submission no completed generation used does not count: diagnostics
     * must prove the model actually received the image, not merely that an
     * append was attempted.
     */
    var snapshotImageSubmitted: Boolean = false
        private set
    /** Snapshot turns only: short reason the final image submission failed, when none succeeded. */
    var snapshotImageSubmitError: String? = null
        private set
    private var speculativeResultUsed = false
    /** Route B: true when the primed Session produced this turn's answer. */
    var primedUsed: Boolean = false
        private set
    /** Route B outcome for diagnostics: "used", or "fallback:<reason>" when the sealed route answered. */
    var routeBPrimingOutcome: String? = null
        private set
    val nativeConversationContainsTurn: Boolean get() = !speculativeResultUsed && !primedUsed && (textInput == null || incrementalFallbackUsed)

    /**
     * Attempts the speculative promotion on its own, without any ordinary
     * generation. Promote drains the speculative owner (join + rollback) and
     * is the only promotion boundary, so callers that must apply a native
     * mutation afterwards (e.g. a tool-policy transition) run it first and
     * mutate only once it returns. Returns null when there is no promotable
     * speculation; the caller then runs [generateFallback] with the full
     * ordinary input. Never call [generate] after a null return here: promote
     * may be consumed only once.
     *
     * Speculative drafts are tool-free by construction (the coordinator
     * rejects any draft that proposes tool calls), so [ordinaryReplyAccepted]
     * must be true only when this turn will NOT run a tools-enabled
     * authoritative pass afterwards. A direct-audio action turn must never
     * promote: promotion would publish prose and skip the one pass that can
     * emit the tool calls the action needs. When false, promote drains the
     * speculative owner and returns null so the caller runs that pass.
     */
    suspend fun tryPromote(prompt: String, onToken: (String) -> Unit,
                           ordinaryReplyAccepted: Boolean = true): GenerationResult? {
        if (!directAudio) return null
        val prepared = nativeSpeculation?.promote(prompt, ordinaryReplyAccepted = ordinaryReplyAccepted, onToken = onToken)
        if (prepared != null) speculativeResultUsed = true
        return prepared
    }

    suspend fun generate(engine: ConversationBackend, prompt: String, onToken: (String) -> Unit,
                         onIncrementalFallback: (Throwable) -> Unit,
                         ordinaryReplyAccepted: Boolean = true): GenerationResult =
        tryPromote(prompt, onToken, ordinaryReplyAccepted) ?: generateFallback(engine, prompt, onToken, onIncrementalFallback)

    /**
     * Ordinary generation without a promotion attempt. Use after [tryPromote]
     * returned null (speculation already drained) or when no speculation
     * exists. The direct-audio branch uses the full ordinary audio input,
     * never a spent prefill.
     */
    /** Releases a primed session this generation will not use (e.g. a promoted speculative answer). */
    suspend fun closePrimedAudio() { primedAudio?.close() }

    suspend fun generateFallback(engine: ConversationBackend, prompt: String, onToken: (String) -> Unit,
                         onIncrementalFallback: (Throwable) -> Unit): GenerationResult = when {
        directAudio && primedAudio != null && sealedVoiceAudio != null -> {
            // Route B: the primed Session answers when it provably holds
            // this exact turn; its own fallback is the ordinary sealed
            // submission below, so a priming failure degrades silently to
            // the sealed route instead of failing the turn. Retry never
            // re-enters here — a spent prefill is not authoritative input.
            val result = primedAudio.answerWithSealedFallback(prompt, sealedVoiceAudio, onToken) {
                engine.generateSealedAudio(prompt, sealedVoiceAudio, onToken)
            }
            primedUsed = primedAudio.usedPriming
            routeBPrimingOutcome = if (primedUsed) "used"
                else "fallback:${primedAudio.fallbackReason ?: "unknown"}"
            // Option A evidence rides the same diagnostics line: rows fed
            // provisionally during capture and the seal-verification result.
            if (primedAudio.provisionalRowsOffered > 0 || primedAudio.provisionalRowsFed > 0) {
                routeBPrimingOutcome += " provisionalOffered=${primedAudio.provisionalRowsOffered}" +
                    " provisionalFed=${primedAudio.provisionalRowsFed}" +
                    " sealMaxAbsDiff=${primedAudio.provisionalSealMaxAbsDiff ?: "not_verified"}"
            }
            result
        }
        directAudio -> {
            if (sealedVoiceAudio != null) engine.generateSealedAudio(prompt, sealedVoiceAudio, onToken)
            else engine.generateAudio(prompt, requireNotNull(voiceAudio), onToken)
        }
        textInput != null -> {
            engine.onPromptSubmitted(prompt, 0)
            val result = textInput.answerWithTextFallback(prompt, onToken, imageBytes) { error ->
                onIncrementalFallback(error)
                incrementalFallbackUsed = true
                engine.benchmarkPurpose = PipelineBenchmarkPurpose.RETRY
                // Snapshot synthesis: when this turn carries an image (a
                // chat photo, or a call turn's retained snapshot), the
                // fallback regenerates WITH it on the conversation route —
                // never a silent text-only downgrade of an image turn. If
                // that submission also fails, the image turn is
                // undeliverable and the generation owner substitutes the
                // honest disclosure instead of a labels-only answer.
                if (imageBytes != null) generateWithImage(engine, prompt, imageBytes, onToken)
                else engine.generate(prompt, onToken)
            }
            if (imageBytes != null && textInput.imageSubmissionAccepted) snapshotImageSubmitted = true
            result
        }
        // Retained voice audio accompanies ASR text but must not override that mode.
        voiceAudio != null -> if (imageBytes != null) generateWithImage(engine, prompt, imageBytes, onToken)
            else engine.generate(prompt, onToken)
        else -> retry(engine, prompt, onToken)
    }

    /**
     * One image-bearing generation attempt with submission bookkeeping: a
     * success proves the model received the image on this route; a failure
     * is recorded and rethrown as [SnapshotImageUndeliverableException] so
     * the generation owner can keep the turn honest.
     */
    private suspend fun generateWithImage(engine: ConversationBackend, prompt: String,
                                          image: ByteArray, onToken: (String) -> Unit): GenerationResult {
        try {
            return engine.generate(prompt, image, onToken).also { snapshotImageSubmitted = true }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Throwable) {
            snapshotImageSubmitError = shortSubmitError(error)
            throw SnapshotImageUndeliverableException(error)
        }
    }

    private fun shortSubmitError(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message?.replace('\n', ' ')?.take(80) ?: "no message"}"

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
