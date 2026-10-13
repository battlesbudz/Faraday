package com.battlesbudz.jarvis.v2.voice

import android.content.Context

/** Fixed call capture source/effects, independent of the duplex speaker/focus route.
 * Existing echoCancellation requests and route eligibility remain with the caller.
 * Legacy profile IDs migrate to this profile without resetting other user data. */
enum class SpeechCaptureProfile(
    val id: String,
    val label: String,
    val communicationInput: Boolean,
    val noiseSuppression: Boolean
) {
    COMMUNICATION_NOISE_FILTERED("communication_noise_filtered", "Call noise reduction", true, true);

    companion object {
        const val PREFERENCES = "voice_input"
        const val KEY = "capture_profile"
        fun fromId(id: String?): SpeechCaptureProfile = entries.firstOrNull { it.id == id } ?: COMMUNICATION_NOISE_FILTERED
        fun selected(context: Context): SpeechCaptureProfile {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val storedId = try { preferences.getString(KEY, null) } catch (_: ClassCastException) { null }
            val profile = fromId(storedId)
            if (storedId != profile.id) {
                // Only normalize this retired setting. Never clear voice preferences or app data.
                preferences.edit().putString(KEY, profile.id).apply()
            }
            return profile
        }
    }
}
