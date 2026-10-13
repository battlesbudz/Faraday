package com.battlesbudz.jarvis.v2.runtime.turn

import com.battlesbudz.jarvis.v2.ChatEntry
import com.battlesbudz.jarvis.v2.actions.ActionTurnPlan
import com.battlesbudz.jarvis.v2.ai.LiteRtLmEngine
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.AudioInput
import com.battlesbudz.jarvis.v2.voice.AudioTurnCapture
import com.battlesbudz.jarvis.v2.voice.CallFinalInput
import com.battlesbudz.jarvis.v2.voice.CapturedVoiceTurn
import com.battlesbudz.jarvis.v2.voice.IncrementalVoiceInput
import com.battlesbudz.jarvis.v2.voice.PiperVoiceOutput
import com.battlesbudz.jarvis.v2.voice.TtsEngine
import com.battlesbudz.jarvis.v2.voice.VoiceModelSession
import com.battlesbudz.jarvis.v2.voice.comparison.LiveComparison
import java.io.File

/** Frozen selection and final typed input at turn admission; live drafts have no authority. */
internal data class VoiceTurnRequest(
    val queuedTypedInput: CallFinalInput?,
    val comparison: LiveComparison.Trial?,
    val directAudioTurn: Boolean,
    val captionAsrEnabled: Boolean,
    val asrEngine: AsrEngine,
    val ttsEngine: TtsEngine,
    val asrTurnId: String
)

/** Fully prepared resources. Their release owner is [VoiceTurnLifetime], not this value. */
internal data class PreparedVoiceTurn(
    val engine: LiteRtLmEngine,
    val selectedSpec: LocalModelSpec,
    val asrDirectory: File?,
    val ttsDirectory: File,
    val input: AudioInput,
    val expectedCallId: String,
    val models: VoiceModelSession,
    val voiceHistory: List<ChatEntry>,
    val output: PiperVoiceOutput,
    val incremental: IncrementalVoiceInput,
    val activeCapture: AudioTurnCapture,
    val correction: CapturedVoiceTurn?,
    val directAudioTurn: Boolean,
    val replyAsrEnabled: Boolean
)

/** Final recognition evidence. Only this value may advance to phone or ordinary answer stages. */
internal data class FinalizedVoiceTurn(
    val transcript: String,
    val asrTranscript: String,
    val audioBytes: ByteArray,
    val audioIsComplete: Boolean,
    val recognitionIssue: String?,
    val preparedText: IncrementalVoiceInput?,
    val endpointAt: Long,
    val initialActionPlan: ActionTurnPlan,
    val sealedVoiceAudio: com.google.ai.edge.litertlm.Content.SealedAudioEmbeddings? = null,
    val nativeAudioTiming: com.battlesbudz.jarvis.v2.voice.NativeAudioCaptureTiming? = null,
    val nativeSpeculation: com.battlesbudz.jarvis.v2.voice.NativeVoiceSpeculation? = null
)

/** Intentional quiet/echo/control exits are distinct from stage exceptions/cancellation. */
internal sealed interface VoiceStageResult<out T> {
    data class Ready<T>(val value: T) : VoiceStageResult<T>
    data class Finished(val message: String) : VoiceStageResult<Nothing>
}

internal sealed interface AcceptedVoiceStageResult {
    data object NotApplicable : AcceptedVoiceStageResult
    data class Handled(val message: String) : AcceptedVoiceStageResult
}
