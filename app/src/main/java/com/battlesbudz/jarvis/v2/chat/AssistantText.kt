package com.battlesbudz.jarvis.v2.chat

/** Formatting at the display/speech boundary; does not alter prompts or tool validation. */
internal object AssistantText {
    fun forSpeech(text: String): String = text
        .replace("*", "")
        .replace("_", "")
        .replace("`", "")
        .replace(Regex("(?m)^\\s*#+\\s*"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")

    fun forDisplay(text: String): String {
        val cleaned = text
            .replace(
                Regex("""(?s)(?:<\|)?tool_call>.*?(?:<\|tool_call\|>|$)"""),
                ""
            )
            .replace(
                Regex("""(?s)<start_function_call>.*?(?:<end_function_call>|$)"""),
                ""
            )
            .replace(Regex("""(?i)<\|tool_call\|>|<end_function_call>|<\|end_function_call\|>"""), "")
            .trim()
        // The bare legacy form (`call:<name>{...}` with no wrapper tags)
        // survives the regexes above; it is the same protocol text and is
        // removed for the same reason, so no markup shape can be the
        // published answer.
        return ToolCallMarkup.strip(cleaned).trim()
    }

}
