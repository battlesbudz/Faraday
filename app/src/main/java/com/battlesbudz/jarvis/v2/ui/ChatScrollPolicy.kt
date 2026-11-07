package com.battlesbudz.jarvis.v2.ui

/**
 * Tail-follow policy for the conversation transcript (issue #20 item 2).
 *
 * The transcript follows the newest content only while the reader is parked
 * at the tail. Scrolling away to read suspends following (a streamed reply
 * must not drag the reader back down on every chunk); arriving back at the
 * tail resumes it. Kept as pure functions of the list's layout numbers so
 * the policy is pinned by JVM tests, separate from the Compose wiring.
 */
internal object ChatScrollPolicy {
    /** How far past the viewport end (px) the last item's end may sit while still counting as at the tail. */
    const val TAIL_SLACK_PX: Int = 96

    /** True when the visible window reaches the end of the last item. */
    fun isAtTail(
        lastVisibleIndex: Int,
        lastVisibleItemEndPx: Int,
        viewportEndPx: Int,
        lastIndex: Int,
    ): Boolean =
        lastIndex >= 0 && lastVisibleIndex == lastIndex &&
            lastVisibleItemEndPx <= viewportEndPx + TAIL_SLACK_PX

    /**
     * The stick-to-bottom state transition: reaching the tail always sticks;
     * a user drag while away from the tail unsticks; anything else (notably
     * programmatic scrolls and streamed growth) leaves the state unchanged.
     */
    fun updatedStick(current: Boolean, atTail: Boolean, userDragging: Boolean): Boolean = when {
        atTail -> true
        userDragging -> false
        else -> current
    }
}
