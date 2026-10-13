package com.battlesbudz.jarvis.v2.actions

/** Recognize directed requests, not verbs embedded in descriptions or quoted examples. */
internal object ActionRequestText {
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”")
    private val lead = Regex("""(?i)^(?:(?:hey\s+)?jarvis\s*[,,:]?\s+)?(?:(?:um|uh|okay|ok|well|so|alright|please)\s*[,,:]?\s+)*(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?|i\s+(?:want|need)\s+you\s+to\s+)?(?:please\s+)?""")
    private val trailing = Regex("""(?i)(?:\s+(?:for me|please|right now|now|sir))+$""")

    fun normalizedRequest(text: String): String = lead.replaceFirst(quoted.replace(text, " ").trim(), "").trim()

    fun clauses(text: String): List<String> = quoted.replace(text, " ")
        .split(Regex("[.!?;\\n]+"))
        .map { lead.replaceFirst(it.trim(), "").trim() }.filter { it.isNotEmpty() }

    /** Split only an explicit sequence of final, directed action clauses. */
    fun actionClauses(text: String): List<String> {
        val unquoted = quoted.replace(text, " ").trim()
        if (unquoted.isBlank() || Regex("""(?i)\b(?:don't|do not|never|how do i)\b""").containsMatchIn(unquoted)) return emptyList()
        val actionLead = """(?:can|could|would|will)\s+you\b|(?:open|launch|start|set|make|turn|adjust|change|raise|lower|increase|decrease|read|check|show|tell)\b"""
        val discourse = unquoted.replaceFirst(Regex("""(?i)^(?:(?:but\s+)?actually|and\s+then|then)\s+(?=$actionLead)"""), "")
        val retried = discourse.replaceFirst(Regex("""(?i)^(?:i\s+(?:said|asked)(?:\s+you)?[, ]+)(?=(?:can|could|would|will)\s+you\b|please\s+(?:open|launch|start|set|read|check|show|tell)\b)"""), "")
        val directed = lead.replaceFirst(retried, "").trim()
        return directed.split(Regex("""(?i)(?:[!?;\n]|\.(?=\s|$))+|,\s*(?=$actionLead|please\s+)|\s*(?:,?\s+and\s+then\s+|,?\s+then\s+|,?\s+and\s+)"""))
            .map {
                val normalized = trailing.replace(lead.replaceFirst(it.trim(), "").trim(), "").trim().trimEnd('.', '!', '?')
                Regex("""(?i)\s+after that$""").replace(normalized, "").trim()
            }
            .filter { it.isNotBlank() }
    }

    /** Bounded natural-language forms for the current phone battery reading. */
    fun batteryRequest(clause: String): Boolean {
        val text = clause.lowercase().trim()
        val label = "(?:battery(?: (?:level|status|percentage|percent|remaining))?)"
        return listOf(
            "(?:what(?:'s| is)|check|read|show) (?:my |the |phone |device )?$label(?: is)?",
            "tell me (?:what )?(?:my |the |phone |device )?$label(?: is)?",
            "tell me how much battery (?:i have(?: left)?|is left|does my phone have)",
            "how much (?:my )?battery(?: (?:do i have|is left|does my phone have))?",
            "(?:my |phone |device )?$label"
        ).any { Regex("^(?:$it)$").matches(text) }
    }

    /** Bounded natural-language forms for media playback control. Returns the strict verb. */
    fun mediaAction(clause: String): String? {
        val text = clause.lowercase().trim()
        // A media noun is required: bare verbs like "stop" or "next" are too
        // ambiguous to become phone actions on their own.
        val noun = """(?:music|media|songs?|tracks?|playback|tunes?)"""
        if (!Regex("""\b$noun\b""").containsMatchIn(text)) return null
        // Final correction: the speaker can correct themselves mid-utterance
        // ("play music actually pause music"). The first anchored verb must
        // not silently win over the correction, so when a correction marker
        // introduces a new directed clause, the verb is read from that
        // clause — mirroring the open_app final-correction discipline in
        // FinalVoiceToolGuard. Without this the correction is neither
        // honored nor rejected: it parses as the first verb.
        val correction = Regex("""\b(?:actually|instead|make that)\b""").findAll(text).lastOrNull()
        val effective = correction
            ?.takeIf { text.substring(it.range.last + 1).isNotBlank() }
            ?.let { text.substring(it.range.last + 1).trim().trimStart(',', ';', ':').trim() }
            ?: text
        return when {
            Regex("""^(?:pause|stop)\b""").containsMatchIn(effective) -> "pause"
            Regex("""^(?:play|resume)\b""").containsMatchIn(effective) -> "play"
            Regex("""^toggle\b""").containsMatchIn(effective) -> "toggle"
            Regex("""^(?:next|skip)\b""").containsMatchIn(effective) -> "next"
            Regex("""^(?:previous|last|go\s+back)\b""").containsMatchIn(effective) -> "previous"
            else -> null
        }
    }

    /** Bounded natural-language forms for opening a website. Returns the raw URL-ish target. */
    fun websiteTarget(clause: String): String? {
        val text = clause.trim()
        // A dot (or explicit scheme) distinguishes a website from an app name,
        // so "open Chrome" still routes to open_app.
        return Regex("""(?i)^(?:open|launch|visit|go\s+to)\s+(https?://\S+|\S*\.\S+.*)$""")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * M4: "browse to example.com" / "look up example.com" — internal browser.
     * Deliberately disjoint from websiteTarget's verbs so "open X" still
     * hands off to the external browser app.
     */
    fun browseTarget(clause: String): String? {
        val text = clause.trim()
        return Regex("""(?i)^(?:browse\s+to|look\s+up)\s+(https?://\S+|\S*\.\S+.*)$""")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** M4: "read this page" / "what's on this page" / "summarize this page". */
    fun browseReadRequest(clause: String): Boolean {
        val text = clause.trim().lowercase()
        return Regex("""^(?:read|what(?:'s| is) on|summari[sz]e)(?: this)? page$""").matches(text)
    }

    /** Bounded natural-language forms for Android settings screens. Returns the screen key. */
    fun settingsScreen(clause: String): String? {
        val text = clause.lowercase().trim()
        val name = Regex("""^(?:open|show|go\s+to)\s+(?:the\s+)?(wi-?fi|bluetooth|display|sound|apps?|battery|location|storage|network)\s+settings?$""")
            .matchEntire(text)?.groupValues?.get(1) ?: return null
        return when (name.replace("-", "").removeSuffix("s")) {
            "wifi" -> "wifi"
            "bluetooth" -> "bluetooth"
            "display" -> "display"
            "sound" -> "sound"
            "app" -> "apps"
            "battery" -> "battery"
            "location" -> "location"
            "storage" -> "storage"
            "network" -> "network"
            else -> null
        }
    }

    /** Bounded natural-language forms for map directions. Returns the destination. */
    fun navigationTarget(clause: String): String? {
        val text = clause.trim()
        return Regex("""(?i)^(?:navigate|drive)\s+to\s+(.+)$""")
            .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""(?i)^(?:get|show(?: me)?)\s+directions\s+to\s+(.+)$""")
                .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: Regex("""(?i)^take\s+me\s+to\s+(.+)$""")
                .matchEntire(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Bounded natural-language forms for observing the phone screen. Mutations stay model-path only: targets must come from a fresh observation. */
    fun screenObserveRequest(clause: String): Boolean {
        val text = clause.lowercase().trim()
        return listOf(
            "what(?:'s| is) on (?:my |the )?screen",
            "look at (?:my |the )?screen",
            "read (?:my |the )?screen",
            "describe (?:my |the )?screen",
            "show me (?:my |the )?screen",
            "tell me what(?:'s| is) on (?:my |the )?screen"
        ).any { Regex("^$it$").matches(text) }
    }

    fun appTarget(clause: String): String? {
        val target = Regex("""(?i)^(?:open|launch|start)\s+(?:up\s+)?(?:the\s+)?(.+)$""")
            .matchEntire(clause)?.groupValues?.get(1) ?: return null
        return cleanTarget(target)
    }

    fun offeredApp(text: String): String? {
        val unquoted = quoted.replace(text, " ").trim()
        val target = Regex("""(?i)^(?:(?:sir|yes|certainly)[,.]?\s+)?(?:shall i|would you like me to|i can|i could)\s+(?:open|launch|start)\s+(?:the\s+)?([^?.!]+)[?.!]*$""")
            .matchEntire(unquoted)?.groupValues?.get(1) ?: return null
        return cleanTarget(target)
    }

    /**
     * Bounded natural-language forms for one-shot reminders. Follows the
     * media_control parser-fix precedent: a strict verb phrase plus an
     * explicit time, resolved deterministically against [nowMs]. Returns the
     * message and absolute trigger time, or null when the clause is not a
     * recognizable reminder request (notably when no time is given: the
     * parser never invents one).
     */
    fun reminderRequest(clause: String, nowMs: Long): ReminderSpec? =
        parseReminderRequest(clause, nowMs)

    /**
     * Bounded natural-language forms for viewing the schedule. Read-only:
     * these become `show_schedule`, which lists what the ledger actually
     * holds and says honestly when nothing is scheduled.
     */
    fun scheduleRequest(clause: String): Boolean {
        val text = clause.lowercase().trim().trimEnd('.', '!', '?').trim()
        return SCHEDULE_VIEWS.any { it.matches(text) }
    }

    private val SCHEDULE_VIEWS = listOf(
        "show (?:me )?(?:my |the )?schedule",
        "(?:what(?:'s| is)|show) (?:my |the )?schedule",
        "what schedule",
        "(?:list|show)(?: me)? (?:my )?reminders?",
        "what reminders? do i have",
        "do i have any reminders?",
        "any reminders?",
        "where did you set that reminder",
        "where is that reminder",
        "how do i see (?:it|my reminders?|the schedule)",
        "how can i see (?:it|my reminders?|the schedule)"
    ).map { Regex("^${it.trimEnd('?')}$") }

    private fun cleanTarget(text: String): String? {
        val target = trailing.replace(text.trim(), "").trim()
        val words = target.lowercase(java.util.Locale.ROOT).split(Regex("\\s+"))
        if (words.isEmpty() || words.size > 8 || words.any { it in setOf("would", "could", "should", "because", "instead", "and", "or") }) return null
        if (target.lowercase(java.util.Locale.ROOT) in setOf("it", "that", "apps", "applications", "an app", "any app", "an application", "everything", "anything", "source")) return null
        return target.takeIf { it.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N} .&'_-]*")) }
    }
}
