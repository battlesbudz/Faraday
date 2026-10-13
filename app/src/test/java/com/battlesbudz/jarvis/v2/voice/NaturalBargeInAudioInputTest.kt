package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class NaturalBargeInAudioInputTest {
    @Volatile private var clock = 0L
    private var confirmed = 0
    private var models = 0
    private var closedModels = 0
    private var keywordCalls = 0
    private val logs = mutableListOf<String>()
    private fun gate(text: String = "Actually open settings", reference: String = "The sky is blue",
                     textNow: () -> String = { text }, speech: Boolean = true, budget: Boolean = true, keywordAt: Int = -1, keyword: String = "Hey_Jarvis",
                     failing: Boolean = false, chunks: Int = 30,
                     dispatcher: CoroutineDispatcher = Dispatchers.Unconfined, beforeFrame: (Int) -> Unit = {},
                     acceptAction: () -> Unit = {}, loadAction: () -> Unit = {}, speechNow: () -> Boolean = { speech },
                     budgetNow: () -> Boolean = { budget }, backlogNow: () -> Long = { 0L }, onConfirmation: (Boolean) -> Unit = {},
                     onEvidence: (String) -> Unit = {}, minimumProbeMs: Int = 1000, probability: Float = .99f): NaturalBargeInAudioInput {
        val input = object : AudioInput {
            override val sampleRateHz = 16000
            override val channelCount = 1
            override val lastChunkCaptureTimeMs get() = clock
            override val bufferedAudioMs get() = backlogNow()
            override suspend fun start() {}
            override suspend fun stop() {}
            override fun chunks() = flow {
                repeat(chunks) { index -> clock += 100; beforeFrame(index); emit(ByteArray(3200) { (index + 1).toByte() }); yield() }
            }
        }
        return NaturalBargeInAudioInput(input,
            createKeyword = { object : InterruptionKeywordDetector {
                override val ready = true
                override fun accept(pcm: ByteArray): String? {
                    keywordCalls++; return if (keywordCalls == keywordAt) keyword else null
                }
                override fun close() {}
            } },
            createVad = { object : SpeechDetector {
                override fun accept(pcm: ByteArray) = SpeechDecision(speechNow(), if (speechNow()) probability else 0f)
                override fun close() {}
            } },
            createTranscriber = {
                models++
                loadAction()
                object : StreamingTranscriber {
                    override fun accept(pcm: ByteArray): String { acceptAction(); if (failing) error("slow native"); return textNow() }
                    override fun finish() = textNow()
                    override fun close() { closedModels++ }
                }
            }, playing = { true }, reference = { reference }, hasPlaybackBudget = budgetNow,
            onConfirmed = { natural, evidence ->
                onEvidence(evidence)
                if (natural) assertEquals(models, closedModels)
                onConfirmation(natural)
                confirmed++
            }, log = logs::add, nowMs = { clock }, dispatcher = dispatcher,
            minimumProbeAudioMs = minimumProbeMs)
    }
    @Test fun weakSingleProbeThanksCannotStopByAgingWhenFurtherWorkIsUnavailable() = runBlocking {
        gate(text = "Thank you", probability = .6f, minimumProbeMs = 250,
            budgetNow = { models == 0 || clock <= 300 }).chunks().toList()
        assertEquals(0, confirmed)
        assertTrue(logs.any { "reason=fresh_audio_required" in it })
    }
    @Test fun weakSpeechCanConfirmOnFreshGrowingAudioAndRetainsOnset() = runBlocking {
        val audio = gate(text = "No", probability = .6f, minimumProbeMs = 250).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(2, models)
        assertEquals(30 * 3200, audio.sumOf { it.size })
        assertTrue(logs.any { "reason=fresh_audio_agreement" in it })
    }
    @Test fun changingWeakProbeTextDoesNotInterrupt() = runBlocking {
        gate(probability = .6f, minimumProbeMs = 250, textNow = {
            when (models) { 1 -> "You"; 2 -> "Thank you"; else -> "" }
        }).chunks().toList()
        assertEquals(0, confirmed)
    }
    @Test fun briefNoInterruptsWithoutAnEnrolledVoice() = runBlocking {
        gate(text = "No", minimumProbeMs = 250, speechNow = { clock <= 300 }, chunks = 15).chunks().toList()
        assertEquals(1, confirmed)
        assertTrue(logs.any { "barge_probe_started" in it && "preRollMs=300" in it })
        assertTrue(logs.any { "speakerIdentity=disabled" in it && "acousticOnly=false" in it })
    }
    @Test fun recognizedWordsInterruptWithoutIdentityScoring() = runBlocking {
        for (text in listOf("No", "Yes", "I", "Can you move?", "You know, I was")) {
            confirmed = 0
            gate(text = text, minimumProbeMs = 250).chunks().toList()
            assertEquals(text, 1, confirmed)
        }
    }
    @Test fun noiseCaptionsAndPlaybackEchoDoNotInterrupt() = runBlocking {
        for (text in listOf("", "[cough]", "(buzzing)", "[wind]", "...", "The sky is blue")) {
            assertTrue(gate(text = text, minimumProbeMs = 250).chunks().toList().isEmpty())
            assertEquals(text, 0, confirmed)
        }
    }
    @Test fun keywordNoiseCannotStopPlaybackWithoutAsrWords() = runBlocking {
        gate(budget = false, keywordAt = 10).chunks().toList()
        assertEquals(0, confirmed); assertEquals(0, models)
        assertTrue(logs.any { "barge_stop_rejected reason=verification_budget" in it })
    }
    @Test fun recognizedKeywordEventuallyConfirmsOnlyAfterItsAsrVerification() = runBlocking {
        val delivered = gate(text = "Hey Jarvis", speech = false, keywordAt = 10, keyword = "Hey_Jarvis").chunks().toList()
        assertEquals(1, confirmed)
        assertTrue(models >= 1)
        assertTrue(logs.any { "barge_keyword_confirmed keyword=Hey_Jarvis verification=asr_non_echo" in it })
        assertTrue(delivered.isNotEmpty())
    }
    @Test fun sirMistakenForStopDoesNotCutOffPlayback() = runBlocking {
        val audio = gate(text = "Sir, I apologize for the interruption", reference = "Sir, I apologize for the interruption",
            speech = false, keywordAt = 10, keyword = "stop").chunks().toList()
        assertTrue(audio.isEmpty()); assertEquals(0, confirmed)
        assertEquals(1, models); assertEquals(models, closedModels)
        assertTrue(logs.any { "barge_stop_rejected reason=unconfirmed_or_echo" in it })
    }
    @Test fun recognizedStopConfirmsOnlyAfterVerificationAndNativeRelease() = runBlocking {
        val audio = gate(text = "Stop.", speech = false, keywordAt = 10, keyword = "stop",
            onConfirmation = { natural -> assertFalse(natural); assertEquals(models, closedModels) }).chunks().toList()
        assertEquals(1, confirmed); assertEquals(1, models)
        assertTrue(audio.isNotEmpty())
        assertTrue(logs.any { "verification=asr_non_echo" in it })
    }
    @Test fun assistantSayingStopCannotConfirmItsOwnKeyword() = runBlocking {
        assertTrue(gate(text = "Stop.", reference = "You can say stop at any time.",
            speech = false, keywordAt = 10, keyword = "stop").chunks().toList().isEmpty())
        assertEquals(0, confirmed)
    }
    @Test fun finalStopListeningKeepsEndCallIntentDespiteNumericArtifacts() = runBlocking {
        var evidence = ""
        gate(text = "1. Stop listening. 2.", speech = false, keywordAt = 10, keyword = "stop",
            onEvidence = { evidence = it }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals("stop listening", evidence)
        assertEquals(models, closedModels)
    }
    @Test fun stopWithoutVerificationBudgetLeavesPlaybackRunning() = runBlocking {
        assertTrue(gate(budget = false, speech = false, keywordAt = 10, keyword = "stop").chunks().toList().isEmpty())
        assertEquals(0, models); assertEquals(0, confirmed)
        assertTrue(logs.any { "barge_stop_rejected reason=verification_budget" in it })
    }
    @Test fun temporaryDecodeOverrunAllowsLaterNaturalCandidate() = runBlocking {
        var first = true
        val audio = gate(chunks = 60, acceptAction = {
            if (first) { first = false; clock += 1700 }
        }).chunks().toList()
        assertTrue(audio.isNotEmpty()); assertEquals(1, confirmed)
        assertTrue(models >= 2); assertEquals(models, closedModels)
        assertTrue(logs.any { "reason=decode_budget retryable=true" in it })
        assertFalse(logs.any { "barge_natural_unavailable" in it })
    }
    @Test fun ordinaryCorrectionPreservesOnsetAndStreamsFollowingAudioOnce() = runBlocking {
        val delivered = gate().chunks().toList()
        assertEquals(1, confirmed); assertEquals(1, models); assertEquals(models, closedModels)
        assertEquals(30 * 3200, delivered.sumOf { it.size })
        // The retained prefix starts with the first observed speech chunk, not confirmation.
        assertEquals(1, delivered.first()[0].toInt())
        assertEquals(30, delivered.last()[0].toInt())
        assertTrue(logs.any { it.startsWith("barge_evidence_") && "transcript=\"Actually open settings\"" in it &&
            "reason=confirmed_new_request" in it })
    }
    @Test fun aSingleWordInTheSecondProbeAlreadyHandsOverTheFloor() = runBlocking {
        val delivered = gate(chunks = 45, textNow = {
            when (models) { 1 -> ""; 2 -> "Actually"; else -> "Actually tell me what two plus two is" }
        }).chunks().toList()
        assertEquals(1, confirmed); assertEquals(2, models); assertEquals(models, closedModels)
        val bytes = delivered.flatMap { it.toList() }.toByteArray()
        val expected = ByteArray(45 * 3200) { (it / 3200 + 1).toByte() }
        assertArrayEquals(expected, bytes)
        assertTrue(logs.any { "reason=confirmed_new_request" in it && "probes=2" in it })
    }
    @Test fun pendingResultCanSettleAfterCandidateInputDeadline() = runBlocking {
        var firstAccept = true
        val delivered = gate(chunks = 25, loadAction = { clock += 800 }, acceptAction = {
            if (firstAccept) { firstAccept = false; clock += 1600 }
        }).chunks().toList()
        assertEquals(1, confirmed); assertEquals(1, models); assertEquals(models, closedModels)
        assertEquals(25 * 3200, delivered.sumOf { it.size })
        assertTrue(logs.any { "reason=words_settling" in it })
        assertFalse(logs.any { "reason=window_limit" in it })
    }
    @Test fun lateEchoStillCannotConfirmAfterCandidateInputDeadline() = runBlocking {
        var firstAccept = true
        val delivered = gate(chunks = 15, text = "The sky is blue", loadAction = { clock += 800 }, acceptAction = {
            if (firstAccept) { firstAccept = false; clock += 1600 }
        }).chunks().toList()
        assertTrue(delivered.isEmpty()); assertEquals(0, confirmed); assertEquals(models, closedModels)
        assertTrue(logs.any { "barge_candidate_rejected reason=window_limit" in it })
    }
    @Test fun echoNeverPausesOrConfirms() = runBlocking {
        for (text in listOf("Can you open settings", "The sky is blue")) {
            assertTrue(gate(text = text, reference = "Can you open settings? The sky is blue").chunks().toList().isEmpty())
        }
        assertEquals(0, confirmed); assertEquals(models, closedModels)
    }
    @Test fun silenceDoesNotLoadRecognizer() = runBlocking {
        assertTrue(gate(speech = false).chunks().toList().isEmpty())
        assertEquals(0, models); assertEquals(30, keywordCalls)
    }
    @Test fun lowSupplyKeywordHitLeavesPlaybackUninterruptedWithoutAsr() = runBlocking {
        val delivered = gate(budget = false, keywordAt = 15).chunks().toList()
        assertEquals(0, models); assertEquals(0, confirmed)
        assertEquals(30, keywordCalls); assertTrue(delivered.isEmpty())
        assertTrue(logs.any { "barge_stop_rejected reason=verification_budget" in it })
    }
    @Test fun recognizerFailureKeywordHitLeavesPlaybackUninterrupted() = runBlocking {
        val delivered = gate(failing = true, keywordAt = 20).chunks().toList()
        assertTrue(models >= 1); assertEquals(models, closedModels); assertEquals(0, confirmed)
        assertTrue(delivered.isEmpty())
        assertTrue(logs.any { it.contains("fallback=keyword") })
    }
    @Test fun sustainedEchoHasBoundedProbeCountAndNoDuplicateAudio() = runBlocking {
        assertTrue(gate(text = "The sky is blue", chunks = 500).chunks().toList().isEmpty())
        assertEquals(0, confirmed); assertTrue(models in 5..20); assertEquals(models, closedModels)
        assertEquals(500, keywordCalls)
        assertTrue(logs.any { it.contains("rolling_work_budget") })
        assertFalse(logs.any { it.contains("reply_probe_limit") })
    }
    @Test fun resultFromDelayedWorkerCannotInterruptFromOldAudio() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val delivered = gate(chunks = 15, dispatcher = dispatcher, beforeFrame = { index ->
            if (index == 10) {
                clock += 1500
                while (pending.isNotEmpty()) pending.removeFirst().run()
            }
        }).chunks().toList()
        assertTrue(delivered.isEmpty()); assertEquals(0, confirmed)
        assertTrue(logs.any { it.contains("queued_audio_too_old") })
    }
    @Test fun keywordCannotStopPlaybackWhileVerificationIsUnavailable() = runBlocking {
        val delivered = gate(chunks = 15, keywordAt = 12, speech = false, budgetNow = { false }).chunks().toList()
        assertEquals(0, confirmed)
        assertEquals(0, models)
        assertTrue(delivered.isEmpty())
        assertTrue(logs.any { "barge_stop_rejected reason=verification_budget" in it })
    }
    @Test fun completedResultIsConsumedDuringSilenceWithoutAnotherCandidate() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        var voiced = true
        gate(chunks = 23, dispatcher = dispatcher, speechNow = { voiced }, beforeFrame = { index ->
            if (index == 10) voiced = false
            if (index == 19) while (pending.isNotEmpty()) pending.removeFirst().run()
            if (index == 20) assertTrue(logs.any { it.contains("queued_audio_too_old") })
        }).chunks().toList()
        assertEquals(0, confirmed)
        assertEquals(0, models) // Old queued work is rejected before model acquisition.
    }
    @Test fun temporaryPlaybackPressureRecoversForLaterSpeechInSameReply() = runBlocking {
        var budget = true
        var first = true
        val delivered = gate(chunks = 40, budgetNow = { budget }, acceptAction = {
            if (first) { first = false; budget = false }
        }, beforeFrame = { index -> if (index == 15) budget = true }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(2, models)
        assertEquals(models, closedModels)
        assertTrue(delivered.isNotEmpty())
        assertTrue(logs.any { it.contains("barge_probe_deferred") })
        assertFalse(logs.any { it.contains("barge_natural_unavailable") })
    }
    @Test fun delayedCredibleResultSettlesBeforeSubmittingMoreAudio() = runBlocking {
        val pending = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.add(block) }
        }
        val delivered = gate(chunks = 23, dispatcher = dispatcher, beforeFrame = { index ->
            if (index == 11) while (pending.isNotEmpty()) pending.removeFirst().run()
        }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(1, models)
        assertEquals(23 * 3200, delivered.sumOf { it.size })
    }
    @Test fun temporarySevenHundredMsBacklogRecoversInSameReply() = runBlocking {
        var backlog = 700L
        val delivered = gate(chunks = 50, backlogNow = { backlog },
            beforeFrame = { if (it == 10) backlog = 0L }).chunks().toList()
        assertEquals(1, confirmed)
        assertTrue(delivered.isNotEmpty())
        assertTrue(logs.any { it.contains("barge_natural_suspended") })
        assertTrue(logs.any { it.contains("barge_natural_recovered") })
        assertTrue(logs.any { it.contains("backlogRecoveries=1") })
    }
    @Test fun twelveHundredMsNativeResultCanSettleAfterSpeechEnds() = runBlocking {
        var first = true
        var voiced = true
        val delivered = gate(chunks = 25, speechNow = { voiced }, acceptAction = {
            if (first) { clock += 1200; first = false; voiced = false }
        }).chunks().toList()
        assertEquals(1, confirmed)
        assertEquals(1, models)
        assertTrue(delivered.isNotEmpty())
        assertFalse(logs.any { it.contains("barge_probe_discarded") })
    }

    @Test fun genuineSpeechCanInterruptAfterFourEmptyProbesInTheSameReply() = runBlocking {
        val delivered = gate(chunks = 200, textNow = { if (models <= 4) "" else "Actually open settings" }).chunks().toList()
        assertEquals(1, confirmed)
        assertTrue(models > 4)
        assertEquals(models, closedModels)
        assertTrue(delivered.isNotEmpty())
        assertFalse(logs.any { "reply_probe_limit" in it })
    }

}
