package com.battlesbudz.jarvis.v2.privilege

/** Policy for the one accessibility enable request made after a paired helper attaches. */
internal object PostPairingCompletion {
    fun shouldAttemptEnable(helperPresent: Boolean, consentGranted: Boolean, accessibilityEnabled: Boolean): Boolean =
        helperPresent && consentGranted && !accessibilityEnabled
}
