package com.battlesbudz.jarvis.v2.code

import java.util.ArrayDeque

/** Console severity, mirroring the WebView's own message levels one for one. */
enum class CodeConsoleLevel { TIP, LOG, WARNING, ERROR, DEBUG }

/**
 * One captured console message (code-and-export scope section 4.5):
 * the text, its [level], and its source position — [sourceId] is the
 * page URL the message came from and [lineNumber] the line in it.
 * [sequence] is the store's own arrival counter, stamped by
 * [CodeConsoleStore.add]; it is what the fix loop's watermark counts,
 * so a message built by hand (tests, fakes) simply carries 0.
 */
data class CodeConsoleMessage(
    val level: CodeConsoleLevel,
    val message: String,
    val sourceId: String?,
    val lineNumber: Int,
    val sequence: Long = 0L
)

/**
 * The error feed the fix loop reads (section 4.5): each call hands
 * over the errors captured since the previous call, oldest first.
 * [CodeConsoleStore.newErrors] is the real implementation; tests and
 * Unit 6 wiring can substitute a fake without a WebView in the room.
 */
fun interface CodeConsoleErrors {
    fun takeNewErrors(): List<CodeConsoleMessage>
}

/**
 * The preview's console store (section 4.5): bounded at [MAX_ENTRIES]
 * entries, oldest dropped first, fed by the preview's
 * `WebChromeClient.onConsoleMessage` (see [CodePreview]).
 *
 * Two reads serve two consumers. The Console pane (Unit 6) reads
 * [entries] and loses nothing by reading. The fix loop reads
 * [newErrors], which advances a watermark: every error is handed to
 * the loop exactly once, and messages the pane displayed are not
 * re-fed as if they were new. The watermark is sequence-based, not
 * list-position-based, so eviction under the bound cannot resurrect
 * or skip an error: an error evicted before the loop's next check is
 * gone (the bound is the scope's), and one still held is handed over
 * exactly once.
 *
 * Console callbacks arrive on the WebView's thread while the fix loop
 * reads from a coroutine; every method here is synchronized.
 */
class CodeConsoleStore : CodeConsoleErrors {

    private val lock = Any()
    private val entries = ArrayDeque<CodeConsoleMessage>()
    private var nextSequence = 0L
    private var errorWatermark = -1L

    /** Stamps [message] with the next sequence and keeps it, evicting past the bound. */
    fun add(message: CodeConsoleMessage): CodeConsoleMessage = synchronized(lock) {
        val stamped = message.copy(sequence = nextSequence++)
        entries.addLast(stamped)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        stamped
    }

    /** Convenience for the WebView callback's flat fields; same as [add] on a built message. */
    fun add(level: CodeConsoleLevel, message: String, sourceId: String?, lineNumber: Int) =
        add(CodeConsoleMessage(level, message, sourceId, lineNumber))

    /** Entries currently held, oldest first. A snapshot copy. */
    fun entries(): List<CodeConsoleMessage> = synchronized(lock) { entries.toList() }

    /** Error entries currently held, oldest first. A snapshot copy. */
    fun errors(): List<CodeConsoleMessage> = synchronized(lock) {
        entries.filter { it.level == CodeConsoleLevel.ERROR }
    }

    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * The fix loop's read: errors stamped since the last [newErrors]
     * call, oldest first. Advances the watermark past everything
     * stamped so far — errors and non-errors alike — so each error is
     * handed over exactly once, whenever it arrived.
     */
    override fun takeNewErrors(): List<CodeConsoleMessage> = newErrors()

    fun newErrors(): List<CodeConsoleMessage> = synchronized(lock) {
        val fresh = entries.filter {
            it.level == CodeConsoleLevel.ERROR && it.sequence > errorWatermark
        }
        errorWatermark = nextSequence - 1
        fresh
    }

    /** Drops everything held; sequences keep counting, so the watermark stays honest. */
    fun clear() = synchronized(lock) {
        entries.clear()
        errorWatermark = nextSequence - 1
    }

    companion object {
        /** Entries kept (section 4.5: the last 300). */
        const val MAX_ENTRIES: Int = 300
    }
}
