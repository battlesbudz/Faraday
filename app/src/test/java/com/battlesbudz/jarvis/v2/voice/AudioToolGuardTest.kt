package com.battlesbudz.jarvis.v2.voice

import com.battlesbudz.jarvis.v2.actions.ActionRequest
import org.junit.Assert.*
import org.junit.Test

class AudioToolGuardTest {
    @Test fun readOnlyToolsNeedNoArguments() {
        assertTrue(AudioToolGuard.allows(ActionRequest("read_battery")))
        assertTrue(AudioToolGuard.allows(ActionRequest("show_schedule")))
    }

    @Test fun setVolumeRequiresValidLevel() {
        assertTrue(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "50"))))
        assertTrue(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "0"))))
        assertTrue(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "100"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "150"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "-1"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("set_volume", mapOf("level" to "loud"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("set_volume")))
    }

    @Test fun openAppRequiresAppName() {
        assertTrue(AudioToolGuard.allows(ActionRequest("open_app", mapOf("app" to "Facebook"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("open_app", mapOf("app" to ""))))
        assertFalse(AudioToolGuard.allows(ActionRequest("open_app")))
    }

    @Test fun postNotificationRequiresContent() {
        assertTrue(AudioToolGuard.allows(
            ActionRequest("post_notification", mapOf("title" to "Hi", "text" to "Hello there"))))
        assertTrue(AudioToolGuard.allows(
            ActionRequest("post_notification", mapOf("text" to "Hello there"))))
        assertFalse(AudioToolGuard.allows(
            ActionRequest("post_notification", mapOf("title" to "", "text" to ""))))
        assertFalse(AudioToolGuard.allows(ActionRequest("post_notification")))
    }

    @Test fun mediaControlRequiresAction() {
        assertTrue(AudioToolGuard.allows(ActionRequest("media_control", mapOf("action" to "play"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("media_control")))
    }

    @Test fun createReminderRequiresMessageAndTime() {
        assertTrue(AudioToolGuard.allows(ActionRequest(
            "create_reminder", mapOf("message" to "Call mom", "at_ms" to "1791852400000"))))
        assertFalse(AudioToolGuard.allows(ActionRequest(
            "create_reminder", mapOf("message" to "", "at_ms" to "1791852400000"))))
        assertFalse(AudioToolGuard.allows(ActionRequest(
            "create_reminder", mapOf("message" to "Call mom", "at_ms" to "soon"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("create_reminder")))
    }

    @Test fun unknownToolsAreRejected() {
        assertFalse(AudioToolGuard.allows(ActionRequest("screen_tap")))
        assertFalse(AudioToolGuard.allows(ActionRequest("browse_open", mapOf("url" to "https://example.com"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("navigate", mapOf("destination" to "Home"))))
        assertFalse(AudioToolGuard.allows(ActionRequest("delete_everything")))
    }
}
