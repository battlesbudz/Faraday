package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

class ActionRequestTextTest {
    // show_schedule routing: Battles's "Can you tell me my schedule?" must route.
    @Test fun tellMeMyScheduleRoutes() {
        assertTrue(ActionRequestText.scheduleRequest(
            ActionRequestText.normalizedRequest("Can you tell me my schedule?")))
        assertTrue(ActionRequestText.scheduleRequest("tell me my schedule"))
        assertTrue(ActionRequestText.scheduleRequest("tell me the schedule"))
    }

    @Test fun whatsOnMyScheduleRoutes() {
        assertTrue(ActionRequestText.scheduleRequest("what's on my schedule"))
        assertTrue(ActionRequestText.scheduleRequest("what is on my schedule"))
        assertTrue(ActionRequestText.scheduleRequest("do i have anything scheduled"))
        assertTrue(ActionRequestText.scheduleRequest("what do i have scheduled"))
    }

    @Test fun existingSchedulePatternsStillRoute() {
        assertTrue(ActionRequestText.scheduleRequest("show my schedule"))
        assertTrue(ActionRequestText.scheduleRequest("what's my schedule"))
        assertTrue(ActionRequestText.scheduleRequest("show me my reminders"))
        assertTrue(ActionRequestText.scheduleRequest("do i have any reminders"))
    }

    @Test fun nonScheduleRequestsDoNotRoute() {
        assertFalse(ActionRequestText.scheduleRequest("tell me a story"))
        assertFalse(ActionRequestText.scheduleRequest("what's the weather"))
        assertFalse(ActionRequestText.scheduleRequest("schedule a meeting for tomorrow"))
    }

    // post_notification routing
    @Test fun postNotificationWithContentRoutes() {
        val result = ActionRequestText.notificationRequest("post a notification saying hello there")
        assertNotNull(result)
        assertEquals("hello there", result!!.second)
    }

    @Test fun sendNotificationVariantsRoute() {
        assertNotNull(ActionRequestText.notificationRequest("send me a notification that says meeting at 3"))
        assertNotNull(ActionRequestText.notificationRequest("post a notification with the text buy milk"))
        assertNotNull(ActionRequestText.notificationRequest("notify me that the build is done"))
    }

    @Test fun barePostNotificationDoesNotRoute() {
        // No content to post: the model should ask what to say, not invent it.
        assertNull(ActionRequestText.notificationRequest("post notification"))
        assertNull(ActionRequestText.notificationRequest("post a notification"))
        assertNull(ActionRequestText.notificationRequest("send me a notification"))
    }

    // End-to-end through ActionTurnPlan
    @Test fun scheduleRequestProducesShowSchedulePlan() {
        val plan = ActionTurnPlan.parse("Can you tell me my schedule?")
        assertTrue(plan is ActionTurnPlan.Ready)
        val ready = plan as ActionTurnPlan.Ready
        assertEquals(1, ready.steps.size)
        assertEquals("show_schedule", ready.steps.single().request.name)
    }

    @Test fun notificationRequestProducesPostNotificationPlan() {
        val plan = ActionTurnPlan.parse("Post a notification saying hello there")
        assertTrue(plan is ActionTurnPlan.Ready)
        val ready = plan as ActionTurnPlan.Ready
        assertEquals(1, ready.steps.size)
        assertEquals("post_notification", ready.steps.single().request.name)
        assertEquals("hello there", ready.steps.single().request.arguments["text"])
    }

    @Test fun bareNotificationRequestIsNotAction() {
        // Router must not invent notification content.
        val plan = ActionTurnPlan.parse("Post notification")
        assertTrue(plan is ActionTurnPlan.NotAction)
    }
}
