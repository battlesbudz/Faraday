package com.battlesbudz.jarvis.v2.conversation

/** Final visible text policy. Verified Android receipts always outrank generated prose. */
internal object ConversationFinalizer {
    data class Result(val text: String, val repeatedFragment: Boolean)

    fun resolve(cleanedResponse: String, previousAssistant: String?, actionResultMessage: String?,
                actionIntent: Boolean, actionName: String?): Result {
        val repeated = cleanedResponse.length in 1..32 && cleanedResponse == previousAssistant
        val final = actionResultMessage ?: if (actionIntent) {
            "I couldn't execute that phone action. Please ask again with the app name or exact setting."
        } else if (repeated) {
            "I lost the thread of the conversation. Please ask that again."
        } else cleanedResponse.ifBlank {
            if (actionName != null) "I couldn't complete that phone action."
            else "I couldn't generate a response. Please try that again."
        }
        return Result(final, repeated)
    }
}
