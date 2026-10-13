package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class RoutedAudioInputTest {
    private class Source(private val events: MutableList<String>, val fail: Boolean = false) : AudioInput {
        override val sampleRateHz = 16000
        override val channelCount = 1
        override fun chunks() = flow<ByteArray> { awaitCancellation() }
        override suspend fun start() { events += "mic_start"; if (fail) error("mic failed") }
        override suspend fun stop() { events += "mic_stop" }
    }
    @Test fun routeSurvivesTurnHandoffsAndClosesAfterRecorder() = runBlocking {
        val events = mutableListOf<String>()
        val input = RoutedAudioInput(Source(events)) {
            events += "route_ready"
            AutoCloseable { events += "route_close" }
        }
        val session = VoiceAudioSession(input, this)
        for (label in listOf("command", "reply", "command")) {
            session.borrow(label).let { it.start(); it.stop() }
        }
        assertEquals(listOf("route_ready", "mic_start"), events)
        session.close(); session.close()
        assertEquals(listOf("route_ready", "mic_start", "mic_stop", "route_close"), events)
    }
    @Test fun recorderFailureReleasesRoute() = runBlocking {
        val events = mutableListOf<String>()
        val input = RoutedAudioInput(Source(events, fail = true)) {
            AutoCloseable { events += "route_close" }
        }
        try { input.start(); fail("Expected failure") } catch (_: IllegalStateException) { }
        input.stop()
        assertEquals(1, events.count { it == "route_close" })
        assertTrue(events.indexOf("mic_stop") < events.indexOf("route_close"))
    }
    @Test fun cancelledRecorderStartupReleasesRoute() = runBlocking {
        val events = mutableListOf<String>()
        val entered = CompletableDeferred<Unit>()
        val source = object : AudioInput by Source(events) {
            override suspend fun start() { entered.complete(Unit); awaitCancellation() }
        }
        val input = RoutedAudioInput(source) { AutoCloseable { events += "route_close" } }
        val job = launch { input.start() }
        entered.await(); job.cancelAndJoin()
        assertEquals(listOf("mic_stop", "route_close"), events)
    }
    @Test fun wakeToCallSwitchReplacesHardwareOnlyOnce() = runBlocking {
        val created = mutableListOf<Boolean>()
        val resources = VoiceCallResources(createAudio = { communication ->
            created += communication
            VoiceAudioSession(Source(mutableListOf()), this)
        }, createModels = { error("Unused") })
        try {
            resources.borrowMicrophone("command").let { it.start(); it.stop() }
            resources.borrowMicrophone("command", communication = true).let { it.start(); it.stop() }
            resources.borrowMicrophone("reply", communication = true).let { it.start(); it.stop() }
            resources.borrowMicrophone("command", communication = true).let { it.start(); it.stop() }
            assertEquals(listOf(false, true), created)
        } finally { resources.closeMicrophone() }
    }
    @Test fun profileChangesReplaceRecorderWithoutChangingDuplexRoute() = runBlocking {
        var profile = "speech_preserving"
        val created = mutableListOf<String>()
        val resources = VoiceCallResources(createAudio = {
            created += profile
            VoiceAudioSession(Source(mutableListOf()), this)
        }, createModels = { error("Unused") }, captureIdentity = { profile })
        try {
            resources.borrowMicrophone("command", communication = true).let { it.start(); it.stop() }
            profile = "communication_noise_filtered"
            resources.borrowMicrophone("command", communication = true).let { it.start(); it.stop() }
            resources.borrowMicrophone("reply", communication = true).let { it.start(); it.stop() }
            assertEquals(listOf("speech_preserving", "communication_noise_filtered"), created)
        } finally { resources.closeMicrophone() }
    }
}
