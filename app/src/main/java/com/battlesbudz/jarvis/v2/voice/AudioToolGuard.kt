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
 *
 * run_in_sandbox is the one approval-gated tool admitted here (Sandbox
 * Phase 2): its argument is a free-form command that no transcript check
 * could verify anyway, and it can never dispatch from this path — the
 * model call only ever parks for Battles's exact approval of the command
 * text on the inline approval card in the conversation, which is the
 * verification step.
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
        "create_reminder",
        "run_in_sandbox"
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
            "run_in_sandbox" ->
                request.arguments["command"].orEmpty().let { command ->
                    command.isNotBlank() &&
                        command.length <= com.battlesbudz.jarvis.v2.actions.MAX_SANDBOX_COMMAND_CHARS &&
                        !command.contains('\u0000')
                }
            // read_battery, show_schedule: no arguments required.
            else -> true
        }
    }
}
