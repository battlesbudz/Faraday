package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class MicrophonePreferenceTest {
    @Test fun builtInApi29OrLaterRequestsUserFacingHintOnly() {
        assertEquals(MicrophonePreference(true, 1f, "built_in_towards_user_hint"), MicrophonePreference.forRoute(29, true))
    }

    @Test fun externalHeadsetsAndUnsupportedPlatformsKeepTheirRoute() {
        assertFalse(MicrophonePreference.forRoute(35, false).requestTowardsUser)
        assertFalse(MicrophonePreference.forRoute(28, true).requestTowardsUser)
    }
}
