package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarkTest {
    private fun provenance() = PipelineBenchmarkProvenance("test-build", 866, "fixture-not-device-evidence",
        deviceManufacturer = "Fixture", deviceModel = "FakeDevice", sdkLevel = 35,
        models = mapOf("llm" to PipelineBenchmarkModel("E2B", "LiteRT-LM", "0.16", "GPU")),
        configuration = mapOf("temperature" to "0.7"))
    private fun turn(id: String = "t1", outcome: PipelineBenchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE) =
        PipelineBenchmarkTurn(id, "call", "voice", 123L, provenance(), outcome, clock = "System.nanoTime",
            stageOffsetsMs = mapOf("turn_started" to 0L, "speech_ended" to 1_000L,
                "recognition_finalized" to 2_000L, "reply_dispatched" to 2_100L,
                "first_reply_text" to 3_000L, "first_reply_audio" to 3_500L, "turn_finished" to 5_000L))

    @Test fun missingMeasurementsNeverBecomeZeroOrEnterQuantileDenominator() {
        val s = PipelineBenchmarkStatistics.from(listOf(null, 0.0, Double.NaN, 100.0))
        assertEquals(4, s.total); assertEquals(2, s.n); assertEquals(2, s.missing)
        assertEquals(0.0, s.min!!, 0.0); assertEquals(0.0, s.p50!!, 0.0); assertEquals(100.0, s.p99!!, 0.0)
        val empty = PipelineBenchmarkStatistics.from(listOf(null))
        assertNull(empty.mean); assertNull(empty.p95); assertNull(empty.sampleStandardDeviation)
        assertTrue(empty.json().isNull("p95"))
    }

    @Test fun nearestRankPercentilesAndSampleStandardDeviationUseExplicitSampleCounts() {
        val s = PipelineBenchmarkStatistics.from((1..100).map(Int::toDouble))
        assertEquals(50.0, s.p50!!, 0.0); assertEquals(90.0, s.p90!!, 0.0)
        assertEquals(95.0, s.p95!!, 0.0); assertEquals(99.0, s.p99!!, 0.0)
        assertEquals(50.5, s.mean!!, 0.0); assertEquals(29.01149, s.sampleStandardDeviation!!, 0.0001)
        assertFalse(s.json().getBoolean("p99LowSampleCount"))
        assertTrue(PipelineBenchmarkStatistics.from(listOf(1.0)).json().getBoolean("p95LowSampleCount"))
    }

    @Test fun stageMetricsSeparateSpeechEndpointDispatchAndPlayback() {
        val metrics = turn().metrics()
        assertEquals(1000.0, metrics["endpoint_to_first_answer_text_ms"]!!, 0.0)
        assertEquals(1500.0, metrics["endpoint_to_first_answer_playback_ms"]!!, 0.0)
        assertEquals(2500.0, metrics["speech_end_to_first_answer_playback_ms"]!!, 0.0)
        assertEquals(900.0, metrics["reply_dispatch_to_first_answer_text_ms"]!!, 0.0)
        val missing = turn().copy(stageOffsetsMs = mapOf("first_reply_audio" to 50)).metrics()
        assertNull(missing["speech_end_to_first_answer_playback_ms"])
        val backwards = turn().copy(stageOffsetsMs = mapOf("speech_ended" to 100, "first_reply_audio" to 50)).metrics()
        assertNull(backwards["speech_end_to_first_answer_playback_ms"])
    }
    @Test fun textReadinessAndPublicationStayDistinctAndMissingReadinessIsUnknown() {
        val original = turn()
        assertNull(original.metrics()["first_reply_text_ready_ms"])
        assertNull(original.metrics()["endpoint_to_first_answer_text_ready_ms"])
        val ready = original.copy(stageOffsetsMs = original.stageOffsetsMs + ("first_reply_text_ready" to 2_800L)).metrics()
        assertEquals(2_800.0, ready["first_reply_text_ready_ms"]!!, 0.0)
        assertEquals(800.0, ready["endpoint_to_first_answer_text_ready_ms"]!!, 0.0)
        assertEquals(1000.0, ready["endpoint_to_first_answer_text_ms"]!!, 0.0)
    }

    @Test fun exactTokensRequireProvenanceAndCallbacksAreNeverUsedAsTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            PipelineBenchmarkSubmission("s", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE, exactOutputTokens = 10)
        }
        val estimated = PipelineBenchmarkSubmission("s", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE,
            firstTokenMs = 500, totalGenerationMs = 1_500, estimatedOutputTokens = 20,
            estimatedDecodeTokensPerSecond = 20.0, streamEvents = 999)
        assertNull(estimated.exactOutputTokens); assertNull(estimated.exactDecodeTokensPerSecond)
        assertEquals(999.0, estimated.metrics()["stream_events"]!!, 0.0)
        assertEquals(20.0, estimated.metrics()["estimated_output_tokens"]!!, 0.0)
        assertNull(estimated.copy(firstTokenMs = -1).decodeSpanMs)
        assertNull(estimated.copy(exactOutputTokens = 11, tokenTelemetrySource = "fixture_native_token_count_only").exactDecodeTokensPerSecond)
        val exact = estimated.copy(exactOutputTokens = 11, tokenTelemetrySource = "fixture_native_token_ids", nativeFirstOutputTokenMs = 500)
        assertEquals(10.0, exact.exactDecodeTokensPerSecond!!, 0.0)
        assertNull(exact.copy(totalGenerationMs = 500).exactDecodeTokensPerSecond)
        assertNull(exact.copy(exactOutputTokens = 1).exactDecodeTokensPerSecond)
    }

    @Test fun cancelledErrorsAndDraftsRetainSeparateOutcomesAndSubmissionGroups() {
        val passes = listOf(
            PipelineBenchmarkSubmission("draft", PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkOutcome.COMPLETE, PipelineBenchmarkWarmState.COLD, firstTokenMs = 50),
            PipelineBenchmarkSubmission("retry", PipelineBenchmarkPurpose.RETRY, PipelineBenchmarkOutcome.ERROR),
            PipelineBenchmarkSubmission("audio", PipelineBenchmarkPurpose.TRANSCRIPTION_FALLBACK, PipelineBenchmarkOutcome.CANCELLED))
        val report = PipelineBenchmarkReport(listOf(turn().copy(submissions = passes), turn("t2", PipelineBenchmarkOutcome.CANCELLED),
            turn("t3", PipelineBenchmarkOutcome.ERROR)), 1000)
        val aggregate = report.aggregate()
        assertEquals(3, aggregate.turnCount); assertEquals(3, aggregate.submissionCount)
        assertEquals(1, aggregate.outcomes["CANCELLED"]); assertEquals(1, aggregate.outcomes["ERROR"])
        assertEquals(3, aggregate.submissionMetrics.getValue("ttft_ms").total)
        assertEquals(2, aggregate.submissionMetrics.getValue("ttft_ms").missing)
        val json = report.toJson()
        assertEquals(3, json.getJSONArray("submissionGroups").length())
        assertEquals(1, json.getJSONObject("completedTurns").getInt("turnCount"))
    }

    @Test fun realtimeFactorUsesBusyWorkAndPositiveAudioDenominator() {
        val asr = PipelineBenchmarkAsr("moonshine", inputAudioMs = 4000, decodeWorkMs = 800, loadMs = 7000)
        val tts = PipelineBenchmarkTts("piper", synthesisMs = 300, generatedAudioMs = 3000, loadMs = 9000)
        assertEquals(0.2, asr.realtimeFactor!!, 0.0); assertEquals(0.1, tts.realtimeFactor!!, 0.0)
        assertNull(asr.copy(inputAudioMs = 0).realtimeFactor); assertNull(asr.copy(decodeWorkMs = null).realtimeFactor)
    }

    @Test fun jsonRoundTripPreservesUnknownFieldsAndSignedAcousticMetrics() {
        val t = turn().copy(observedMetrics = mapOf("rms_dbfs" to -64.0, "invalid" to Double.NaN, "unknown" to null),
            asr = PipelineBenchmarkAsr("moonshine"), tts = PipelineBenchmarkTts("piper"),
            submissions = listOf(PipelineBenchmarkSubmission("s", PipelineBenchmarkPurpose.ANSWER,
                PipelineBenchmarkOutcome.CANCELLED, measurements = mapOf("rms_dbfs" to -12.0), metadata = mapOf("mode" to "text"))))
        val json = t.json()
        assertTrue(json.getJSONObject("asr").isNull("loadMs"))
        val read = PipelineBenchmarkTurn.read(JSONObject(json.toString()))
        assertEquals(-64.0, read.observedMetrics["rms_dbfs"]!!, 0.0)
        assertNull(read.observedMetrics["invalid"]); assertNull(read.asr!!.loadMs)
        assertEquals(-12.0, read.submissions.single().measurements["rms_dbfs"]!!, 0.0)
        assertEquals(t.provenance, read.provenance)
        val report = PipelineBenchmarkReport.read(PipelineBenchmarkReport(listOf(t), 456).toJson())
        assertEquals(456L, report.exportedAtEpochMs); assertEquals("System.nanoTime", report.turns.single().clock)
    }

    @Test fun exportsRedactReferenceAndHypothesisUnlessExplicitlyOptedIn() {
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("unique private reference", "unique private hypothesis", "user_verified", retainText = true)
        val report = PipelineBenchmarkReport(listOf(turn().copy(accuracy = score)), 1000)
        val redacted = report.toJson().toString()
        assertFalse(redacted.contains("unique private reference")); assertFalse(redacted.contains("unique private hypothesis"))
        assertTrue(report.toJson(includeText = true).toString().contains("unique private reference"))
        assertFalse(report.toCsv().contains("unique private reference")); assertFalse(report.toCsv().contains("unique private hypothesis"))
        assertFalse(report.toJson().getJSONObject("privacy").getBoolean("pcmIncluded"))
    }

    @Test fun duplicateTurnsAndUnsupportedSchemasAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkReport(listOf(turn(), turn()), 1) }
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkReport.read(PipelineBenchmarkReport(listOf(turn()), 1).toJson().put("schemaVersion", 999)) }
        assertThrows(IllegalArgumentException::class.java) {
            val s = PipelineBenchmarkSubmission("s", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE)
            turn().copy(submissions = listOf(s,s))
        }
    }

    @Test fun comparableGroupsSeparateDeviceBackendConfigSourceEnvironmentAndWarmState() {
        val t = turn()
        val key = PipelineBenchmarkReport.comparableGroupKey(t)
        val alternatives = listOf(t.copy(provenance = t.provenance.copy(deviceModel = "OtherPhone")),
            t.copy(provenance = t.provenance.copy(configuration = mapOf("temperature" to "0.8"))),
            t.copy(provenance = t.provenance.copy(sourceCommit = "other")),
            t.copy(provenance = t.provenance.copy(models = mapOf("llm" to PipelineBenchmarkModel("E2B", "LiteRT-LM", "0.16", "CPU")))),
            t.copy(environment = PipelineBenchmarkEnvironment.NOISY), t.copy(asr = PipelineBenchmarkAsr("moonshine", PipelineBenchmarkWarmState.COLD)))
        alternatives.forEach { assertNotEquals(key, PipelineBenchmarkReport.comparableGroupKey(it)) }
        assertEquals(key, PipelineBenchmarkReport.comparableGroupKey(t.copy(provenance = t.provenance.copy(batteryPercent = 20, thermalStatus = 4))))
    }
    @Test fun correlationMetadataDoesNotSplitGroupsAndRemainsOnRawExportRows() {
        val base = turn()
        val identityKeys = listOf("parent_task_ids", "reply_id", "utterance_id", "returned_utterance_id",
            "result_utterance_id", "result_captured_at_epoch_ms", "linked_reply_turn_id")
        val settings = base.provenance.configuration + ("capture_scope" to "reply_voice_capture")
        fun withIds(id: String) = base.copy(turnId = id,
            provenance = base.provenance.copy(configuration = settings + identityKeys.associateWith { "$id:$it" }))
        val first = withIds("first")
        val second = withIds("second")
        val groupKey = PipelineBenchmarkReport.comparableGroupKey(first)
        assertEquals(groupKey, PipelineBenchmarkReport.comparableGroupKey(second))
        assertEquals(groupKey, PipelineBenchmarkReport.comparableGroupKey(base.copy(provenance = base.provenance.copy(configuration = settings))))
        assertNotEquals(groupKey, PipelineBenchmarkReport.comparableGroupKey(second.copy(
            provenance = second.provenance.copy(configuration = second.provenance.configuration + ("capture_scope" to "command_capture")))))
        assertNotEquals(groupKey, PipelineBenchmarkReport.comparableGroupKey(second.copy(
            provenance = second.provenance.copy(configuration = second.provenance.configuration + ("model_id" to "a-real-setting")))))
        val json = PipelineBenchmarkReport(listOf(first, second), 1).toJson()
        val groups = json.getJSONArray("comparableGroups")
        assertEquals(1, groups.length())
        assertEquals(2, groups.getJSONObject(0).getJSONObject("allAttempts").getInt("turnCount"))
        val groupConfig = groups.getJSONObject(0).getJSONObject("group").getJSONObject("provenance").getJSONObject("configuration")
        assertEquals("reply_voice_capture", groupConfig.getString("capture_scope"))
        identityKeys.forEach { assertFalse(groupConfig.has(it)) }
        val rawConfig = json.getJSONArray("turns").getJSONObject(0).getJSONObject("provenance").getJSONObject("configuration")
        identityKeys.forEach { assertEquals("first:$it", rawConfig.getString(it)) }
        assertTrue(PipelineBenchmarkReport(listOf(first, second), 1).toCsv().contains("first:reply_id"))
    }

    @Test fun corpusWerUsesReferenceWeightedCountsAndTracksEmptyReferenceFalsePositives() {
        val short = PipelineBenchmarkAccuracyEvaluator.evaluate("one", "wrong", "fixture")
        val long = PipelineBenchmarkAccuracyEvaluator.evaluate("one two three four five six seven eight nine", "one two three four five six seven eight nine", "fixture")
        val empty = PipelineBenchmarkAccuracyEvaluator.evaluate("", "background voice", "verified_no_speech")
        val aggregate = PipelineBenchmarkReport(listOf(turn().copy(accuracy = short), turn("t2").copy(accuracy = long), turn("t3").copy(accuracy = empty), turn("t4")), 1).aggregate()
        assertEquals(3, aggregate.evaluatedAsrTurns); assertEquals(10L, aggregate.referenceWords)
        assertEquals(3L, aggregate.wordErrors); assertEquals(0.3, aggregate.corpusWer!!, 0.0)
        assertEquals(1, aggregate.emptyReferenceTurns); assertEquals(2L, aggregate.falsePositiveWords)
    }

    @Test fun humanTaskAssessmentIsDistinctFromWordAccuracyAndExecutionSuccess() {
        val t = turn().copy(accuracy = PipelineBenchmarkAccuracyEvaluator.evaluate("correct", "correct", "user_verified"),
            observedMetrics = mapOf("tool_execution_success" to 1.0),
            quality = PipelineBenchmarkQuality(PipelineBenchmarkVerdict.FAIL, "human_reviewed_turn", 1000))
        val aggregate = PipelineBenchmarkReport(listOf(t, turn("t2")), 1000).aggregate()
        assertEquals(0.0, aggregate.corpusWer!!, 0.0)
        assertEquals(1, aggregate.humanQualityCounts["task.FAIL"])
        assertEquals(1, aggregate.humanQualityCounts["task.NOT_EVALUATED"])
        assertEquals(2, aggregate.humanQualityCounts["intent.NOT_EVALUATED"])
        assertEquals(t.quality, PipelineBenchmarkTurn.read(t.json()).quality)
    }

    @Test fun csvHasSeparateRowsQuotedMetadataAndNoFormulaExecution() {
        val t = turn("=HYPERLINK(\"unsafe\")").copy(provenance = provenance().copy(buildName = "build,\"quoted\"\nline"),
            submissions = listOf(PipelineBenchmarkSubmission("s", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE)))
        val csv = PipelineBenchmarkReport(listOf(t), 1).toCsv()
        assertTrue(csv.contains("'=")); assertTrue(csv.contains("\"build,\"\"quoted\"\"\nline\""))
        assertTrue(csv.contains("turn,")); assertTrue(csv.contains("submission,"))
        assertTrue(PipelineBenchmarkReport(listOf(turn("  =formula")), 1).toCsv().contains("'  =formula"))
    }

    @Test fun followupCorrelationAndTransferEvidenceShareGroupWithoutDroppingRawValues() {
        val key = "followup_smart_turn_utterance_id"
        val transferKey = "capture_first_raw_transfer"
        val transfers = listOf(
            "capture_first_raw_ready prefixBytes=3200 firstSequence=17 lastSequence=18",
            "capture_first_raw_ready prefixBytes=6400 firstSequence=41 lastSequence=44")
        val first = turn("first").let { it.copy(provenance = it.provenance.copy(configuration =
            it.provenance.configuration + mapOf(key to "followup-first", transferKey to transfers[0],
                "followup_smart_turn_mode" to "disabled", "capture_profile_requested" to "communication_noise_filtered"))) }
        val second = first.copy(turnId = "second", provenance = first.provenance.copy(configuration =
            first.provenance.configuration + mapOf(key to "followup-second", transferKey to transfers[1])))
        val report = PipelineBenchmarkReport(listOf(first, second), 1)
        val json = report.toJson()
        val groups = json.getJSONArray("comparableGroups")
        assertEquals(1, groups.length())
        assertEquals(2, groups.getJSONObject(0).getJSONObject("allAttempts").getInt("turnCount"))
        val groupConfig = groups.getJSONObject(0).getJSONObject("group").getJSONObject("provenance").getJSONObject("configuration")
        assertFalse(groupConfig.has(key)); assertFalse(groupConfig.has(transferKey))
        assertEquals("disabled", groupConfig.getString("followup_smart_turn_mode"))
        assertEquals("communication_noise_filtered", groupConfig.getString("capture_profile_requested"))
        for ((index, id) in listOf("followup-first", "followup-second").withIndex()) {
            val raw = json.getJSONArray("turns").getJSONObject(index).getJSONObject("provenance").getJSONObject("configuration")
            assertEquals(id, raw.getString(key)); assertEquals(transfers[index], raw.getString(transferKey))
            assertTrue(report.toCsv().contains(id)); assertTrue(report.toCsv().contains(transfers[index]))
        }
        for (setting in listOf("followup_smart_turn_mode" to "shadow_only_v1", "capture_profile_requested" to "different-profile")) {
            val changed = second.copy(turnId = "third", provenance = second.provenance.copy(configuration =
                second.provenance.configuration + setting))
            assertEquals(2, PipelineBenchmarkReport(listOf(first, second, changed), 1).toJson()
                .getJSONArray("comparableGroups").length())
        }
    }

}
