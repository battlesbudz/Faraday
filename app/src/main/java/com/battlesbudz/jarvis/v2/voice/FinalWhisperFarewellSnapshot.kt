package com.battlesbudz.jarvis.v2.voice

/**
 * One observation of final Whisper evidence that already exists at the check point.
 *
 * The caller copies these values once, after its existing capture join. In particular,
 * AudioTurnCapture publishes finalAsrStatus before finalTranscript while finishing; its
 * individual volatile fields are not an atomic final result during capture. This helper
 * deliberately accepts only values, with no supplier, recognizer, clock, lease or callback
 * that could refresh evidence, create work or wait for finalization.
 *
 * A miss stays a miss. The separate final-caption pass remains the backstop. This is a
 * conservative lexical control check, not a Whisper confidence or acoustic-quality score.
 */
internal class FinalWhisperFarewellSnapshot private constructor(
    val owner: Owner,
    private val finalFarewell: String?
) {
    /** The input revision is an opaque identity belonging to the call/input owner. */
    data class Owner(val callId: String, val turnId: String, val inputRevision: Long)

    /** Revalidate ownership at publication; never reread the capture or retry a missed check. */
    fun farewellIfCurrent(currentOwner: Owner?): String? =
        finalFarewell.takeIf { currentOwner == owner }

    companion object {
        /**
         * [rejectedByPlaybackEcho] is the existing follow-up echo/onset guard's verdict
         * for this exact transcript. Source [recognitionIssue] must be copied before the
         * native-audio path discards display-only caption failures.
         */
        fun capture(
            owner: Owner,
            currentOwner: Owner?,
            isWhisper: Boolean,
            transcript: String,
            finalAsrStatus: String,
            captionFinalizationReason: String,
            recognitionIssue: String?,
            rejectedByPlaybackEcho: Boolean
        ): FinalWhisperFarewellSnapshot {
            val eligible = owner.callId.isNotBlank() && owner.turnId.isNotBlank() &&
                currentOwner == owner && isWhisper && finalAsrStatus == "finalized" &&
                captionFinalizationReason.isNotBlank() && captionFinalizationReason != "not_attempted" &&
                !captionFinalizationReason.startsWith("clean_native_idle") &&
                recognitionIssue == null && !rejectedByPlaybackEcho && transcript.isNotBlank()
            val farewell = transcript.takeIf { eligible && VoiceCallPolicy.isGoodbye(it) }
            return FinalWhisperFarewellSnapshot(owner, farewell)
        }
    }
}
