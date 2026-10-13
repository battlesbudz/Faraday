package com.battlesbudz.jarvis.v2.chat

/**
 * Streams ordinary assistant text immediately while holding only the uncertain
 * protocol prefix long enough to detect leaked model control syntax.
 */
class AssistantStreamFilter(
    private val emit: (String) -> Unit
) {
    private val pending = StringBuilder()
    private var decided = false
    private var suppressControl = false

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        if (suppressControl) {
            return
        }
        if (decided) {
            emit(chunk)
            return
        }

        pending.append(chunk)
        val candidate = pending.toString()
        val trimmed = candidate.trimStart()
        val isControl = Regex("""(?i)(tool_call|function_call)""")
            .containsMatchIn(trimmed)
        if (isControl) {
            suppressControl = true
            pending.clear()
            return
        }

        if (trimmed.isEmpty()) return

        // Normal prose is emitted as soon as its first token is available.
        // A leading '<' is held briefly because it may begin model markup.
        if (!trimmed.startsWith("<") || pending.length >= 256) {
            decided = true
            emit(pending.toString())
            pending.clear()
        }
    }
}
