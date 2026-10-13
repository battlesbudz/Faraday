package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.actions.ActionRequest

/**
 * Validates tool calls emitted by the model in direct-audio mode.
 *
 * In direct-audio mode there is no transcript, so [FinalVoiceToolGuard]'s
 * spoken-argument verification cannot run. This guard applies a stricter
 * allowlist policy instead: only low-risk tools whose arguments can be
 * sanity-checked without a transcript are permitted. Every argument must be
 * structurally valid. Risky actions still go through the D11 approval gates
 * downstream via the normal [phoneActions.runNative] path.
 */
object AudioToolGuard {
    /**
     * Tools permitted in direct-audio mode. These are read-only or
     * low-risk reversible tools where a misheard argument has limited blast
     * radius. Screen mutations, browser actions, navigation and other
     * D11-gated tools are intentionally excluded until audio-native approval
     * UX exists.
     */
    private val allowedTools = setOf(
        "read_battery",
        "show_schedule",
        "media_control",
        "set_volume",
        "post_notification",
        "open_app",
        "create_reminder"
    )

    fun allows(request: ActionRequest): Boolean {
        if (request.name !in allowedTools) return false
        return when (request.name) {
            "set_volume" -> request.arguments["level"]?.toIntOrNull() in 0..100
            "open_app" -> request.arguments["app"].orEmpty().isNotBlank()
            "post_notification" ->
                request.arguments["title"].orEmpty().isNotBlank() ||
                    request.arguments["text"].orEmpty().isNotBlank()
            "media_control" -> request.arguments["action"].orEmpty().isNotBlank()
            "create_reminder" ->
                request.arguments["message"].orEmpty().isNotBlank() &&
                    request.arguments["at_ms"]?.toLongOrNull() != null
            // read_battery, show_schedule: no arguments required.
            else -> true
        }
    }
}
