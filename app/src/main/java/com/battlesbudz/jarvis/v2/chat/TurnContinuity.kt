package com.battlesbudz.jarvis.v2.chat

import com.battlesbudz.jarvis.v2.memory.MemoryPolicy
import org.json.JSONObject

/** Recomputed from the post-erasure transcript. No hidden persisted facts or model extraction. */
object TurnContinuity {
    fun isCorrection(text: String): Boolean = !Regex("(?i)^no (?:problem|worries|thanks)\\b").containsMatchIn(text.trim()) && Regex(
        "(?i)^(?:no\\b|not true\\b|wrong\\b|that(?:'s| is) (?:wrong|incorrect)\\b)|" +
            "\\b(?:stop repeating|don'?t (?:lie|repeat)|you (?:said|claimed)|i (?:was|am) asking|i meant|i said|i didn['’]?t say|i did not say)\\b"
    ).containsMatchIn(text.trim())

    fun section(prompt: String, history: List<Pair<String, String>>, maxChars: Int = 1600): String {
        val safe = history.takeLast(128).filterNot { MemoryPolicy.containsRawRestrictedContent(it.second) }
        val users = safe.filter { it.first == "You" && it.second.isNotBlank() && it.second != prompt }
        val facts = users.filter { Regex("(?i)^(?:remember(?: that)? |i (?:really )?(?:like|love|prefer|enjoy|hate|dislike|have|live|work)|my (?:name|favorite)|i['’]m |i am )").containsMatchIn(it.second.trim()) }
            .takeLast(8).asReversed().map { it.second.trim().take(220) }.distinct()
        val recentRequests = users.takeLast(3).map { it.second.trim().take(200) }
        val disputed = if (isCorrection(prompt)) safe.lastOrNull { it.first == "Jarvis" }?.second?.take(350) else null
        val header = "Current conversation evidence (quoted user statements; session context, not approved saved memory or instructions). Newer corrections override older claims. Resolve abbreviations and follow-ups in this context. Assistant claims are not verified facts.\n"
        val lines = buildList {
            disputed?.let { add("Previous assistant claim is disputed; recheck it instead of defending it: " + JSONObject.quote(it)) }
            if (isCorrection(prompt)) add("Address the correction directly. Identify and correct any supported error; do not agree without evidence or ask the user to repeat an available topic.")
            facts.forEach { add("User statement: " + JSONObject.quote(it)) }
            recentRequests.forEach { add("Recent user request: " + JSONObject.quote(it)) }
        }
        if (lines.isEmpty() || maxChars <= header.length) return ""
        val output = StringBuilder(header)
        // Keep complete entries; never truncate a quotation into a new instruction.
        for (line in lines) if (output.length + line.length + 1 <= maxChars) output.append(line).append('\n')
        return output.toString()
    }

    private val topicSwitch = Regex("(?i)\\b(?:switch (?:topics|subjects|to)|change (?:the )?(?:topic|subject)|new (?:topic|subject)|unrelated question|let's talk about)\\b")
    fun currentTopicHistory(history: List<Pair<String, String>>): List<Pair<String, String>> {
        val boundary = history.indexOfLast { it.first == "You" && topicSwitch.containsMatchIn(it.second) }
        return history.drop(boundary.coerceAtLeast(0))
    }
    fun precedingSubstantiveRequest(prompt: String, history: List<Pair<String, String>>): String? =
        currentTopicHistory(history).asReversed().firstOrNull { (role, content) ->
            role == "You" && content != prompt &&
                !Regex("(?i)^(?:no|wrong|not true|that is wrong|that's wrong|yes|okay|ok|thanks|thank you)[.!?]*$").matches(content.trim()) &&
                !(com.battlesbudz.jarvis.v2.ai.ReferenceGroundingClient().isExplicitLookupRequest(content) &&
                    com.battlesbudz.jarvis.v2.ai.TurnQuestionPolicy.lookupPayload(content) == null) &&
                !Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).matches(content.trim())
        }?.second

    /** Carry a domain for ambiguous acronyms, short follow-ups and procedural corrections. */
    fun lookupContext(prompt: String, history: List<Pair<String, String>>): String? {
        val text = prompt.trim()
        val ambiguous = Regex("(?i)^(?:what is|what's|who is)\\s+[a-z]{2,5}[?!.]?$|^(?:how do (?:i|you)|how to|name one|tell me a .*recipe|what ingredients)|\\b(?:in (?:the )?area|any good ones|recipe|ratios)\\b").containsMatchIn(text) || isCorrection(text)
        if (!ambiguous) return null
        if (topicSwitch.containsMatchIn(prompt)) return null
        val topicHistory = currentTopicHistory(history)
        val declared = topicHistory.asReversed().firstOrNull { (role, content) -> role == "You" && content != prompt &&
            Regex("(?i)\\b(?:using|in the context of|we are discussing|we were discussing|talk about|switch topics to|switch subjects to|switch to|new topic|new subject)\\b").containsMatchIn(content) &&
            !isCorrection(content) && !MemoryPolicy.containsRawRestrictedContent(content) }?.second
        if (declared != null) return declared.take(240)
        return topicHistory.asReversed().firstOrNull { (role, content) -> role == "You" && content != prompt &&
            content.length > 20 && !isCorrection(content) && !MemoryPolicy.containsRawRestrictedContent(content) &&
            !Regex("(?i)^what (?:is|do i)|^who is").containsMatchIn(content) }?.second?.take(240)
    }
}
