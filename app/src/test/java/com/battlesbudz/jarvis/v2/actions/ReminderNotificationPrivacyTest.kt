package com.battlesbudz.jarvis.v2.actions

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderNotificationPrivacyTest {
    @Test fun reminderPayloadIsHiddenOnLockScreenAndPermissionDenialPostsNothing() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        manager.cancelAll()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(ReminderNotification.post(context, "Private title", "Sensitive reminder body"))
        val posted = shadow.allNotifications.single()
        assertEquals(Notification.VISIBILITY_SECRET, posted.visibility)
        assertEquals(Notification.VISIBILITY_SECRET, manager.getNotificationChannel("jarvis_reminders").lockscreenVisibility)
        assertFalse(manager.getNotificationChannel("jarvis_reminders").canBypassDnd())
        manager.cancelAll()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ReminderNotification.post(context, "Private title", "Sensitive reminder body"))
        assertTrue(shadow.allNotifications.isEmpty())
    }
}
