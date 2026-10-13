package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ChatEntry

/** An all-or-nothing interpretation of the user's final action request. */
sealed interface ActionTurnPlan {
    data object NotAction : ActionTurnPlan
    data class Ready(val steps: List<Step>, val batteryCondition: BatteryCondition? = null) : ActionTurnPlan {
        init { require(steps.size in 1..3) }
    }
    data class Rejected(val reason: String) : ActionTurnPlan
    data class Step(val request: ActionRequest, val sourceClause: String)

    companion object {
        fun parse(text: String, history: List<ChatEntry> = emptyList(),
            nowMs: Long = System.currentTimeMillis()
        ): ActionTurnPlan {
            confirmation(text, history)?.let { request ->
                return Ready(listOf(Step(request, "Open " + request.arguments.getValue("app"))))
            }
            val normalized = ActionRequestText.normalizedRequest(text)
            // Quoted/hypothetical/negated speech must never become unconditional actions.
            if (Regex("""(?i)\b(?:don't|do not|never)\b""").containsMatchIn(normalized)) return NotAction
            // Read-only schedule views are matched on the whole normalized
            // turn: "how do I see it" cannot survive clause splitting, so it
            // never reaches the clause router below.
            if (ActionRequestText.scheduleRequest(normalized))
                return Ready(listOf(Step(ActionRequest("show_schedule"), normalized)))
            val conditional = BatteryCondition.split(normalized)
            if (conditional == null && Regex("""(?i)^(?:if|unless|when|after)\b.*\b(?:open|launch|start|set|read|check|tell)\b""")
                    .containsMatchIn(normalized))
                return Rejected("I can check a battery-percentage condition now; other conditions need a separate request.")
            if (conditional != null && Regex("""(?i)\b(?:if|unless|when|after|else|otherwise)\b""").containsMatchIn(conditional.actions))
                return Rejected("Please use one battery condition for this phone-action request.")
            val clauses = ActionRequestText.actionClauses(conditional?.actions ?: text)
            if (clauses.isEmpty()) return NotAction
            // Do not apply action limits or condition rules to ordinary speech.
            if (clauses.none { looksDirected(it, nowMs) }) return NotAction
            if (clauses.any { Regex("""(?i)\b(?:if|unless|after|when)\b""").containsMatchIn(it) })
                return Rejected("Conditional phone actions need a separate request.")
            if (clauses.size > 3) return Rejected("I can complete up to three phone actions at a time.")
            val parsed = clauses.map { it to requestFor(it, nowMs) }
            val steps = parsed.map { (clause, request) ->
                request ?: return Rejected("I couldn't safely understand: $clause")
                Step(request, clause)
            }
            return Ready(steps, conditional?.condition)
        }

        private fun looksDirected(clause: String, nowMs: Long): Boolean =
            ActionRequestText.appTarget(clause) != null ||
                Regex("""(?i)^(?:set|make|turn|adjust|change|raise|lower|increase|decrease)\b.*\bvolume\b""").containsMatchIn(clause) ||
                ActionRequestText.batteryRequest(clause) ||
                ActionRequestText.mediaAction(clause) != null ||
                ActionRequestText.settingsScreen(clause) != null ||
                ActionRequestText.websiteTarget(clause) != null ||
                ActionRequestText.navigationTarget(clause) != null ||
                // M4: browse routing is gated with the tools themselves — an
                // unwired "browse to X" is ordinary speech, not an action.
                (MobileToolCatalog.BrowserRuntimeGate.wired &&
                    (ActionRequestText.browseTarget(clause) != null ||
                        ActionRequestText.browseReadRequest(clause))) ||
                ActionRequestText.screenObserveRequest(clause) ||
                ActionRequestText.reminderRequest(clause, nowMs) != null ||
                ActionRequestText.scheduleRequest(clause)

        private fun confirmation(text: String, history: List<ChatEntry>): ActionRequest? {
            val normalized = text.trim()
            val generic = Regex("""(?i)^(?:(?:okay|ok|yes|sure)(?:\s+(?:thanks|thank you|can you|could you|please|do it|now|and))*|do it|go ahead|open it|open that)(?:\s+please)?[?.!]*$""")
                .matches(normalized)
            if (!generic) return null
            val last = history.lastOrNull() ?: return null
            val app = if (last.role == "Jarvis") ActionRequestText.offeredApp(last.text)
                else ActionRequestText.clauses(last.text).firstNotNullOfOrNull(ActionRequestText::appTarget)
            return app?.let { ActionRequest("open_app", mapOf("app" to it)) }
        }

        private fun requestFor(clause: String, nowMs: Long): ActionRequest? {
            // Reminders are checked first: "remind me to open ..." is a
            // reminder about opening, not an app launch.
            ActionRequestText.reminderRequest(clause, nowMs)?.let { spec ->
                return ActionRequest("create_reminder",
                    mapOf("message" to spec.message, "at_ms" to spec.atMs.toString()))
            }
            if (ActionRequestText.scheduleRequest(clause)) return ActionRequest("show_schedule")
            // Settings screens and websites are checked before appTarget: "open wifi
            // settings" and "open youtube.com" must not become open_app requests.
            ActionRequestText.settingsScreen(clause)?.let { return ActionRequest("open_settings", mapOf("screen" to it)) }
            ActionRequestText.websiteTarget(clause)?.let { return ActionRequest("open_website", mapOf("url" to it)) }
            // M4: "browse to X" stays in the internal browser; "open X" hands
            // off to the external browser app (checked just above). Gated:
            // the browser runtime path is not wired yet, so browse requests
            // stay unavailable until the real path passes an Android journey.
            if (MobileToolCatalog.BrowserRuntimeGate.wired) {
                ActionRequestText.browseTarget(clause)?.let { return ActionRequest("browse_open", mapOf("url" to it)) }
                if (ActionRequestText.browseReadRequest(clause)) return ActionRequest("browse_read")
            }
            ActionRequestText.appTarget(clause)?.let { return ActionRequest("open_app", mapOf("app" to it)) }
            val volume = Regex("""(?i)^(?:set|make|turn|adjust|change|raise|lower|increase|decrease)(?:\s+(?:the|my))?(?:\s+media)?\s+volume(?:\s+to)?\s+(.+)$""")
                .matchEntire(clause)?.groupValues?.get(1)
            if (volume != null) return parseExactVolume(volume)?.let { ActionRequest("set_volume", mapOf("level" to it.toString())) }
            if (ActionRequestText.batteryRequest(clause)) return ActionRequest("read_battery")
            ActionRequestText.mediaAction(clause)?.let { return ActionRequest("media_control", mapOf("action" to it)) }
            ActionRequestText.navigationTarget(clause)?.let { return ActionRequest("navigate", mapOf("destination" to it)) }
            // Screen mutations stay on the model path: their targets must come
            // from a fresh screen_observe result, which text cannot supply.
            if (ActionRequestText.screenObserveRequest(clause)) return ActionRequest("screen_observe")
            return null
        }

        /** Whole phrase grammar: no substring/last-number recovery. */
        internal fun parseExactVolume(value: String): Int? {
            val raw = value.trim().lowercase().removeSuffix("%").trim().removeSuffix("percent").trim()
            raw.toIntOrNull()?.let { return it.takeIf { level -> level in 0..100 } }
            if (raw.contains(Regex("[^a-z -]")) || raw.startsWith("negative") || raw.contains("hundred") && raw != "one hundred") return null
            val units = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
                "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9)
            val simple = mapOf("zero" to 0, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
                "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
                "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
                "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90, "one hundred" to 100)
            simple[raw]?.let { return it }
            units[raw]?.let { return it }
            val words = raw.split(Regex("[ -]+"))
            val tens = simple.filterValues { it in 20..90 && it % 10 == 0 }
            return if (words.size == 2 && tens.containsKey(words[0]) && units.containsKey(words[1])) tens.getValue(words[0]) + units.getValue(words[1]) else null
        }
    }
}
