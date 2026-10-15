package com.battlesbudz.jarvis.v2

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Incognito-only FLAG_SECURE: private conversations block screenshots and
 * screen recordings; normal conversations stay screenshottable.
 */
class PrivateChatSecureFlagsTest {
    @Test fun privateChatSetsSecureFlag() {
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secureFlagsForPrivateChat(true))
    }

    @Test fun normalChatClearsSecureFlag() {
        assertEquals(0, secureFlagsForPrivateChat(false))
    }
}
