package com.battlesbudz.jarvis.v2.chat

import com.battlesbudz.jarvis.v2.voice.VoiceRepetitionGuard

/** Conservative deterministic draft checks shared by text and speech. Never executes a tool. */
object AnswerQualityPolicy {
    fun requestsDetails(prompt: String): Boolean = Regex("(?i)\\b(?:recipe|ingredients|ratios|how (?:do|to|is|are)|how.*made)\\b").containsMatchIn(prompt)
    fun rejection(prompt: String, candidate: String, previous: String?): String? {
        if (candidate.isBlank()) return "empty"
        val lower = candidate.lowercase()
        if (TurnContinuity.isCorrection(prompt) && Regex("(?i)\\b(?:as i (?:said|mentioned)|as previously|since .*individualized|what (?:specific )?topic .*referring|provide more context)\\b").containsMatchIn(candidate)) return "defends_or_loses_correction"
        if (requestsDetails(prompt) && Regex("(?i)\\b(?:there (?:is|isn't|is not) no single|there isn't one|cannot give.*fixed recipe|highly individualized|philosophy rather than|core principles revolve)\\b").containsMatchIn(candidate)) return "generic_instead_of_requested_details"
        if (previous != null && candidate.length > 40 && VoiceRepetitionGuard.duplicates(candidate, previous)) return "repeated_previous_answer"
        if (TurnContinuity.isCorrection(prompt) && lower.trim() in setOf("i see.", "understood.", "i apologize.")) return "empty_correction"
        return null
    }
    fun repairInstruction(reason: String): String =
        "Draft rejected ($reason). Answer the CURRENT request using the quoted conversation and relevant evidence. " +
            "Do not restate the request, repeat a generic explanation, defend a disputed claim, or invent details. " +
            "For a requested recipe give an actual named input, ingredients and method supported by evidence. " +
            "If evidence is missing, say precisely what could not be verified. Do not call tools."
}
