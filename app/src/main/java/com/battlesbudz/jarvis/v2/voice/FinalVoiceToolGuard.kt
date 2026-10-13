package com.battlesbudz.jarvis.v2.voice

/** Checks final dictated arguments, in addition to the existing action-intent and native validators. */
object FinalVoiceToolGuard {
    fun allows(transcript: String, name: String, arguments: Map<String, String>): Boolean {
        val text = transcript.lowercase().replace('’', '\'')
        if (Regex("\\b(?:never mind|nevermind|cancel|do not|don't|dont|stop listening)\\b").containsMatchIn(text)) return false
        return when (name) {
            "set_volume" -> {
                val finalNumber = SpokenVolumeLevel.fromTranscript(text)
                finalNumber != null && finalNumber == arguments["level"]?.toIntOrNull()
            }
            "open_app" -> {
                val packageName = arguments["package"].orEmpty().lowercase()
                val target = arguments["app"].orEmpty().lowercase().trim()
                // Do not let an unspoken package override the named app.
                if (packageName.isNotBlank() && !text.contains(packageName)) return false
                if (target.isBlank()) return packageName.isNotBlank() && text.contains(packageName)
                val correction = Regex("\\b(?:actually|instead|make that|no)\\b").findAll(text).lastOrNull()
                val correctionOffset = correction?.takeIf { text.substring(it.range.last + 1).isNotBlank() }?.range?.last?.plus(1) ?: 0
                val verbOffset = Regex("\\b(?:open|launch|start)\\b").findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0
                val relevant = text.substring(maxOf(correctionOffset, verbOffset))
                Regex("(?<![a-z0-9])" + target.filterNot { it.isWhitespace() }.map { Regex.escape(it.toString()) }.joinToString("\\s*") + "(?![a-z0-9])").containsMatchIn(relevant)
            }
            "read_battery" -> true
            "show_schedule" -> true
            "media_control" -> {
                // Media verbs are voice-triggerable; re-parse the final spoken
                // clause deterministically and require the spoken verb to match
                // the planned argument, mirroring the set_volume final-number
                // discipline. Without this branch every voice media request was
                // rejected before dispatch ("Failed; unattempted: media_control").
                val verb = arguments["action"]?.trim().orEmpty()
                if (com.battlesbudz.jarvis.v2.actions.MediaControlAction.fromVerb(verb) == null) return false
                com.battlesbudz.jarvis.v2.actions.ActionRequestText.mediaAction(text) == verb
            }
            "create_reminder" -> {
                // Reminders are voice-triggerable per D32 (triggers by
                // voice/text) and need no separate confirmation (not a D11
                // category). The guard re-parses the final spoken clause
                // deterministically and requires the spoken message and time
                // to match the planned arguments, mirroring the set_volume
                // final-number discipline.
                val spec = com.battlesbudz.jarvis.v2.actions.ActionRequestText
                    .reminderRequest(text, System.currentTimeMillis()) ?: return false
                val atMs = arguments["at_ms"]?.toLongOrNull() ?: return false
                spec.message.equals(arguments["message"], ignoreCase = true) &&
                    kotlin.math.abs(spec.atMs - atMs) <= 60_000
            }
            else -> false
        }
    }
}
