package com.battlesbudz.jarvis.v2.voice

/** A playback keyword hit alone cannot distinguish the user's stop from speaker leakage. */
object StopKeywordEvidence {
    fun confirms(transcript: String, spokenReference: String): Boolean {
        val heard = TranscriptContent.speech(transcript)
        val words = Regex("[\\p{L}\\p{N}']+").findAll(heard.lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        val echoedStop = Regex("\\bstop\\b", RegexOption.IGNORE_CASE).containsMatchIn(spokenReference)
        return !echoedStop && "stop" in words &&
            (VoiceStopRequest.matches(heard) || VoiceCallPolicy.isGoodbye(heard))
    }
}
