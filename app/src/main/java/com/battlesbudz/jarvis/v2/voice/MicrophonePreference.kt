package com.battlesbudz.jarvis.v2.voice

/** Best-effort Android input hints; they do not select a physical microphone. */
internal data class MicrophonePreference(val requestTowardsUser: Boolean, val fieldDimension: Float?, val reason: String) {
    companion object {
        fun forRoute(apiLevel: Int, builtInMic: Boolean): MicrophonePreference = when {
            apiLevel < 29 -> MicrophonePreference(false, null, "api_below_29")
            !builtInMic -> MicrophonePreference(false, null, "external_or_unknown_route_preserved")
            else -> MicrophonePreference(true, 1f, "built_in_towards_user_hint")
        }
    }
}
