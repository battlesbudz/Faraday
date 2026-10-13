package com.battlesbudz.jarvis.v2.ai

/** Resolves local dialogue references without another model pass or authorizing a phone action. */
object DialogueContextPolicy {
    data class Resolution(val recall: Boolean = false, val storyInstruction: String? = null)
    private val storyRequest = Regex("\\b(?:(?:tell|write|make|create|give)\\b.{0,65}\\b(?:story|tale)\\b|story of your choice)")
    private val negative = Regex("\\b(no|not|never|stop|cancel|don't|dont)\\b")
    private fun normalized(text: String) = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun resolve(prompt: String, history: List<Pair<String, String>>): Resolution {
        if (ReferenceGroundingClient().isExplicitLookupRequest(prompt)) return Resolution()
        val text = normalized(prompt)
        val recent = history.takeLast(8)
        val assistant = recent.filter { it.first.equals("Jarvis", true) || it.first.equals("assistant", true) }
        val stories = assistant.filter { isStory(it.second) }
        val explicitRecall = Regex("\\b(?:what did (?:you|i) (?:say|ask|tell)|what (?:were|was) (?:we|i|you) (?:discussing|saying|asking)|repeat (?:that|your (?:last )?(?:answer|reply))|remind me what you said)\\b").containsMatchIn(text)
        val dialogueReference = Regex("\\b(?:who|what) (?:is|was|were) (?:the |that )?(?:person|character|name|thing) (?:you|we) (?:were |was |are |just )?(?:talking about|discussing|mentioned)\\b").containsMatchIn(text)
        val storyReference = Regex("\\b(?:in (?:the|your|that) (?:story|tale)|that character)\\b").containsMatchIn(text)
        val nameQuestion = Regex("\\b(?:name|named|called)\\b").containsMatchIn(text) &&
            Regex("^(?:what|who|remind)\\b").containsMatchIn(text)
        val ignored = setOf("what", "was", "were", "name", "named", "called", "the", "that", "this", "your", "please", "remind", "tell", "about", "again")
        val subjects = text.split(' ').filter { it.length >= 4 && it !in ignored }
        val anchoredName = nameQuestion && stories.any { (_, story) ->
            val words = normalized(story).split(' ').toSet()
            subjects.any { it in words } || Regex("\\b(his|her|their|its)\\b").containsMatchIn(text)
        }
        if (assistant.isNotEmpty() && (explicitRecall || dialogueReference || storyReference || anchoredName)) return Resolution(recall = true)

        if (storyRequest.containsMatchIn(text) && !negative.containsMatchIn(text)) {
            return Resolution(storyInstruction = "Tell the requested story now. Choose any unspecified details yourself. Begin the narrative; do not offer a premise or ask for approval.")
        }
        val requestIndex = recent.indexOfLast { (role, content) ->
            role.equals("You", true) && storyRequest.containsMatchIn(normalized(content))
        }
        if (requestIndex < 0 || !isAcceptance(text)) return Resolution()
        val following = recent.drop(requestIndex + 1)
        if (following.any { (role, content) -> role.equals("Jarvis", true) && isStory(content) }) return Resolution()
        // An intervening new user task invalidates the pending-story interpretation.
        if (following.any { (role, content) -> role.equals("You", true) && !isAcceptance(normalized(content)) }) return Resolution()
        return Resolution(storyInstruction = "The user is accepting the pending story from the recent dialogue. Tell that story now using the proposed characters and premise. Do not offer it again or treat this as a farewell.")
    }

    private fun isAcceptance(text: String): Boolean {
        if (text.split(' ').size > 22 || negative.containsMatchIn(text)) return false
        return Regex("^(?:yes|yeah|yep|sure|okay|ok|absolutely)(?: (?:please|do (?:that|it)|go ahead|that works(?: for me)?|of course|sounds good))*$").matches(text) ||
            Regex("^(?:please do|go ahead|do that|that works for me)$").matches(text) ||
            Regex("^i (?:really )?do(?: yes)?(?: please)?(?: do that)?$").matches(text) ||
            Regex("^(?:yeah |yes )?(?:you can )?do what you (?:actually )?(?:just )?said you were going to do$").matches(text)
    }

    private fun isStory(text: String): Boolean = text.length > 250 &&
        (Regex("(?i)\\b(once upon|there (?:was|lived)|one day|the year is|chapter one)\\b").containsMatchIn(text) || (text.length > 900 && Regex("(?i)\\b(story|tale|character)\\b").containsMatchIn(text)))
}
