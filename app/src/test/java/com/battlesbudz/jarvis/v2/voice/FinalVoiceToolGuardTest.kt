package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class FinalVoiceToolGuardTest {
    @Test fun lateCancellationRejectsPreparedAction() {
        assertFalse(FinalVoiceToolGuard.allows("open instagram actually cancel that", "open_app", mapOf("app" to "Instagram")))
        assertFalse(FinalVoiceToolGuard.allows("don't open instagram", "open_app", mapOf("app" to "Instagram")))
    }
    @Test fun finalAppCorrectionRejectsOldTarget() {
        val text = "open instagram actually open youtube"
        assertFalse(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "Instagram")))
        assertTrue(FinalVoiceToolGuard.allows(text, "open_app", mapOf("app" to "YouTube")))
    }
    @Test fun finalNumberMustMatchProposedVolume() {
        assertFalse(FinalVoiceToolGuard.allows("set volume to 50 actually 20", "set_volume", mapOf("level" to "50")))
        assertTrue(FinalVoiceToolGuard.allows("set volume to 50 actually 20", "set_volume", mapOf("level" to "20")))
    }
    @Test fun unspokenPackageCannotOverrideNamedApp() {
        assertFalse(FinalVoiceToolGuard.allows("open youtube", "open_app", mapOf("app" to "YouTube", "package" to "com.instagram.android")))
    }
    @Test fun spokenReminderAllowsMatchingMessageAndTime() {
        // The voice turn that produced the build-1002 confabulation must pass
        // the final spoken-clause guard when the spoken message and time match
        // the planned arguments.
        val text = "remind me to go door dashing tomorrow at 4"
        val spec = com.battlesbudz.jarvis.v2.actions.ActionRequestText
            .reminderRequest(text, System.currentTimeMillis())
        assertNotNull(spec)
        assertTrue(FinalVoiceToolGuard.allows(text, "create_reminder",
            mapOf("message" to "go door dashing", "at_ms" to spec!!.atMs.toString())))
    }
    @Test fun spokenReminderRejectsMismatchedMessageOrTime() {
        val text = "remind me to go door dashing tomorrow at 4"
        val spec = com.battlesbudz.jarvis.v2.actions.ActionRequestText
            .reminderRequest(text, System.currentTimeMillis())
        assertNotNull(spec)
        assertFalse("wrong message must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder",
                mapOf("message" to "go grocery shopping", "at_ms" to spec!!.atMs.toString())))
        assertFalse("wrong time must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder",
                mapOf("message" to "go door dashing", "at_ms" to (spec.atMs + 3_600_000).toString())))
        assertFalse("missing time must not pass",
            FinalVoiceToolGuard.allows(text, "create_reminder", mapOf("message" to "go door dashing")))
    }
    @Test fun spokenScheduleViewIsAllowed() {
        assertTrue(FinalVoiceToolGuard.allows("show my schedule", "show_schedule", emptyMap()))
    }
    @Test fun spokenMediaVerbMustMatchProposedAction() {
        assertTrue(FinalVoiceToolGuard.allows("play some music", "media_control", mapOf("action" to "play")))
        assertTrue(FinalVoiceToolGuard.allows("pause the music", "media_control", mapOf("action" to "pause")))
        assertFalse("verb mismatch must not pass",
            FinalVoiceToolGuard.allows("play some music", "media_control", mapOf("action" to "pause")))
        assertFalse("unknown verb must not pass",
            FinalVoiceToolGuard.allows("play some music", "media_control", mapOf("action" to "blast")))
        assertFalse("late cancellation must not pass",
            FinalVoiceToolGuard.allows("pause the music actually cancel that", "media_control", mapOf("action" to "pause")))
    }
    @Test fun finalMediaCorrectionHonorsCorrectedVerb() {
        // "play music actually pause music" must not pass as play: the final
        // correction is honored (or safely rejected), never silently parsed
        // as the first verb.
        val text = "play music actually pause music"
        assertFalse("stale verb must not pass",
            FinalVoiceToolGuard.allows(text, "media_control", mapOf("action" to "play")))
        assertTrue("corrected verb must pass",
            FinalVoiceToolGuard.allows(text, "media_control", mapOf("action" to "pause")))
        val reverse = "pause music actually play music"
        assertFalse("stale verb must not pass",
            FinalVoiceToolGuard.allows(reverse, "media_control", mapOf("action" to "pause")))
        assertTrue("corrected verb must pass",
            FinalVoiceToolGuard.allows(reverse, "media_control", mapOf("action" to "play")))
    }
}
