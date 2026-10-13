package com.battlesbudz.jarvis.v2.actions

/**
 * M1e lock gating (T09, D30, D61).
 *
 * While locked, permitted non-sensitive reads continue; a due, previously
 * authorized workflow may post a lock-screen-hidden notification. Other tools
 * hand off to unlock. Owner recognition is GATED: speaker verification is
 * an unverified device-validation dependency that never bypasses platform
 * authentication, so a voice match never authorizes an action — there is no
 * code path that treats an untested voice match as secure authorization.
 * The mode cannot be switched until on-device speaker verification is
 * measured and explicitly approved. Sensitive remembered details always
 * require unlock even after a voice match (D61).
 */
enum class OwnerRecognitionMode {
    GATED
}

enum class LockVerdict { ALLOWED, NEEDS_UNLOCK }

class DeviceLockGate(
    private val isLocked: () -> Boolean,
    val ownerRecognition: OwnerRecognitionMode = OwnerRecognitionMode.GATED,
    /** Production supplies this only for a live, already-claimed due workflow occurrence. */
    private val privateNotificationAuthorized: () -> Boolean = { false }
) {
    fun check(request: ActionRequest): LockVerdict {
        if (!isLocked()) return LockVerdict.ALLOWED
        // The notification adapter always hides reminder content on the lock screen.
        // This does not admit ad-hoc locked requests or bypass source/OS permission checks.
        if (request.name == "post_notification" && privateNotificationAuthorized()) return LockVerdict.ALLOWED
        // GATED: no voice-match authorization path exists, so every
        // non-read action on a locked device hands off to unlock (T09).
        // read_battery remains the only permitted locked read: it exposes no
        // sensitive content and answers a general question (D30).
        return if (request.name == "read_battery") LockVerdict.ALLOWED else LockVerdict.NEEDS_UNLOCK
    }

    fun unlockMessage(request: ActionRequest): String =
        "Your phone is locked, so I can't ${request.describeForOverlay().lowercase()} right now. " +
            "Unlock your phone and ask again."
}
