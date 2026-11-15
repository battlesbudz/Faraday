package com.battlesbudz.jarvis.v2.ui

import org.junit.Assert.*
import org.junit.Test

/** Issue #20 item 2: the transcript's tail-follow decisions. */
class ChatScrollPolicyTest {
    @Test fun atTailWhenLastItemEndIsInsideTheViewport() {
        assertTrue(ChatScrollPolicy.isAtTail(lastVisibleIndex = 4, lastVisibleItemEndPx = 900, viewportEndPx = 1_000, lastIndex = 4))
    }

    @Test fun atTailWithinSlackOfTheViewportEnd() {
        assertTrue(ChatScrollPolicy.isAtTail(4, 1_000 + ChatScrollPolicy.TAIL_SLACK_PX, 1_000, 4))
        assertFalse(ChatScrollPolicy.isAtTail(4, 1_001 + ChatScrollPolicy.TAIL_SLACK_PX, 1_000, 4))
    }

    @Test fun notAtTailWhenAnEarlierItemIsTheLastVisible() {
        assertFalse(ChatScrollPolicy.isAtTail(lastVisibleIndex = 2, lastVisibleItemEndPx = 900, viewportEndPx = 1_000, lastIndex = 4))
    }

    @Test fun notAtTailForAnEmptyList() {
        assertFalse(ChatScrollPolicy.isAtTail(lastVisibleIndex = -1, lastVisibleItemEndPx = 0, viewportEndPx = 1_000, lastIndex = -1))
    }

    @Test fun reachingTheTailSticksEvenMidDrag() {
        assertTrue(ChatScrollPolicy.updatedStick(current = false, atTail = true, userDragging = true))
        assertTrue(ChatScrollPolicy.updatedStick(current = false, atTail = true, userDragging = false))
    }

    @Test fun draggingAwayFromTheTailUnsticks() {
        assertFalse(ChatScrollPolicy.updatedStick(current = true, atTail = false, userDragging = true))
    }

    @Test fun programmaticMovementAwayFromTheTailKeepsTheState() {
        // Streamed growth and the screen's own scroll animations are not user
        // drags: they must neither unstick a following reader nor restick a
        // reader who scrolled up.
        assertTrue(ChatScrollPolicy.updatedStick(current = true, atTail = false, userDragging = false))
        assertFalse(ChatScrollPolicy.updatedStick(current = false, atTail = false, userDragging = false))
    }
}
