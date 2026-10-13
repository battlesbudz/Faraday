package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarkTextExportTest {
    @Test fun copiedHeaderKeepsAppSendAndSdkProxyClockOriginsDistinct() {
        val text = render(turn("origin"))
        assertTrue(text.contains("ttft_ms starts at app inference measurement creation"))
        assertTrue(text.contains("submit_to_first_raw_text_ms starts at SDK send entry"))
        assertTrue(text.contains("sdk_first_prefill_decode_ttft_proxy_ms"))
        assertFalse(text.contains("TTFT starts at model submission"))
    }

    private val provenance = PipelineBenchmarkProvenance("fixture", 1237, "fixture-source",
        deviceModel = "Fold fixture", androidVersion = "16", sdkLevel = 36,
        models = mapOf("llm" to PipelineBenchmarkModel("Gemma-fixture", "LiteRT", "test", "CPU")),
        configuration = mapOf("threads" to "4"))
    private fun turn(id: String, outcome: PipelineBenchmarkOutcome = PipelineBenchmarkOutcome.COMPLETE, duration: Long? = 100) =
        PipelineBenchmarkTurn(id, callId = "whole-call", channel = "voice", capturedAtEpochMs = 10,
            provenance = provenance, outcome = outcome, conversationId = "conversation",
            stageOffsetsMs = duration?.let { mapOf("turn_finished" to it) } ?: emptyMap())
    private fun render(vararg turns: PipelineBenchmarkTurn) = PipelineBenchmarkTextExport.render(PipelineBenchmarkReport(turns.toList(), 20), "call=whole-call")

    @Test fun includesEveryTurnBeyondTheUiLimitAndPreservesFullFormats() {
        val turns = (1..160).map { turn("turn-$it") }
        val report = PipelineBenchmarkReport(turns, 20)
        val jsonBefore = report.toJson().toString()
        val csvBefore = report.toCsv()
        val text = PipelineBenchmarkTextExport.render(report, "call=whole-call")
        turns.forEach { assertTrue(text.contains("id=\"${it.turnId}\"")) }
        assertTrue(text.contains("TURN 160/160"))
        assertTrue("Shared availability/provenance avoids full JSON repetition", text.length < jsonBefore.length)
        assertEquals(jsonBefore, report.toJson().toString())
        assertEquals(csvBefore, report.toCsv())
        assertEquals(text, PipelineBenchmarkTextExport.chunks(text).joinToString("") { it.substringAfter('\n') })
    }

    @Test fun percentilesSeparateBuildModelConfigurationAndOutcomesAndNeverZeroFillMissing() {
        val text = render(turn("a", duration = 100), turn("b", duration = 300), turn("missing", duration = null),
            turn("cancelled", PipelineBenchmarkOutcome.CANCELLED, 999999), turn("error", PipelineBenchmarkOutcome.ERROR, 888888),
            turn("other-build", duration = 900).copy(provenance = provenance.copy(buildCode = 1238)),
            turn("other-config", duration = 800).copy(provenance = provenance.copy(configuration = mapOf("threads" to "8"))),
            turn("other-model", duration = 700).copy(provenance = provenance.copy(models = mapOf("llm" to PipelineBenchmarkModel("other")))))
        assertEquals(4, Regex("(?m)^GROUP G").findAll(text).count())
        assertTrue(text.contains("turn_total_ms: n=2 missing=1 p50=100.0 p95=300.0(exploratory)"))
        assertTrue(text.contains("outcome=CANCELLED"))
        assertTrue(text.contains("turn_total_ms=999999.0"))
        assertTrue(text.contains("Measured metrics: none; all catalog keys unavailable."))
        assertTrue(text.contains("TURN catalog:"))
        assertFalse(text.contains("p95=999999"))
        assertFalse(text.contains("p95=888888"))
    }

    @Test fun estimatesMissingTelemetryAndSubmissionPurposeAreExplicit() {
        val text = render(turn("a").copy(submissions = listOf(
            PipelineBenchmarkSubmission("answer", PipelineBenchmarkPurpose.ANSWER, PipelineBenchmarkOutcome.COMPLETE,
                firstTokenMs = 12, estimatedOutputTokens = 7, estimatedDecodeTokensPerSecond = 2.0),
            PipelineBenchmarkSubmission("draft", PipelineBenchmarkPurpose.DRAFT, PipelineBenchmarkOutcome.CANCELLED,
                firstTokenMs = 999))))
        assertTrue(text.contains("purpose=ANSWER")); assertTrue(text.contains("purpose=DRAFT"))
        assertTrue(text.contains("estimated_decode_tokens_per_second=2.0 [estimated]"))
        assertFalse(text.contains("exact_output_tokens=0"))
        assertTrue(text.contains("SUBMISSION catalog:"))
        assertTrue(text.contains("exact_output_tokens"))
        assertTrue(text.contains("No success-latency distribution; raw observations follow."))
        assertTrue(text.contains("tokenTelemetry=unavailable"))
    }

    @Test fun textFreeScoreAndProvenanceWithHonestEndpoints() {
        val text = render(turn("a").copy(accuracy = PipelineBenchmarkAccuracyEvaluator.evaluate("private reference", "private spoken sentence", "fixture", retainText = true)))
        assertFalse(text.contains("private spoken sentence")); assertFalse(text.contains("private reference"))
        listOf("1237", "fixture-source", "Fold fixture", "Gemma-fixture", "threads", "Last detected user speech", "Excludes filler", "acoustic onset unavailable").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun emptyReportAndNonFiniteValuesDoNotInventMeasurements() {
        val empty = render()
        assertTrue(empty.contains("No retained measurements")); assertFalse(empty.contains("p50="))
        val text = render(turn("a").copy(observedMetrics = mapOf("bad" to Double.NaN, "bad2" to Double.POSITIVE_INFINITY)))
        assertTrue(text.contains("bad, bad2")); assertFalse(text.contains("NaN")); assertFalse(text.contains("Infinity"))
    }

    @Test fun chunksHaveStableReportIdentityNumberingAndExactUtf8Reassembly() {
        val payload = ("日本語 🙂 café\n".repeat(3000)) + "尾🙂" + "x".repeat(20000)
        val parts = PipelineBenchmarkTextExport.chunks(payload)
        assertTrue(parts.size > 1)
        val id = parts.first().substringBefore(" | ")
        parts.forEachIndexed { index, part ->
            assertTrue(part.toByteArray(Charsets.UTF_8).size <= PipelineBenchmarkTextExport.COPY_BYTES)
            assertTrue(part.startsWith("$id | part ${index + 1}/${parts.size}\n"))
            assertEquals(part, part.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        }
        assertEquals(payload, parts.joinToString("") { it.substringAfter('\n') })
        assertEquals(parts, PipelineBenchmarkTextExport.chunks(payload))
        assertNotEquals(id, PipelineBenchmarkTextExport.chunks(payload + "changed").first().substringBefore(" | "))
    }

    @Test fun frozenCollectionsDoNotChangeAndProvenanceDedupRetainsEveryCorrelation() {
        val metrics = mutableMapOf<String, Double?>("custom" to 7.0)
        val config = mutableMapOf("threads" to "4", "reply_id" to "reply-a")
        val turns = mutableListOf(turn("a").copy(provenance = provenance.copy(configuration = config), observedMetrics = metrics))
        val frozen = PipelineBenchmarkTextExport.freeze(PipelineBenchmarkReport(turns, 20))
        metrics["custom"] = 999.0; config["threads"] = "99"; turns.clear()
        assertEquals(7.0, frozen.turns.single().observedMetrics["custom"]!!, 0.0)
        assertEquals("4", frozen.turns.single().provenance.configuration["threads"])
        val report = PipelineBenchmarkReport(listOf(frozen.turns.single(), frozen.turns.single().copy(turnId = "b",
            provenance = frozen.turns.single().provenance.copy(configuration = mapOf("threads" to "4", "reply_id" to "reply-b")))), 20)
        val text = PipelineBenchmarkTextExport.render(report, "call")
        assertEquals(1, Regex("(?m)^P[0-9]+:").findAll(text).count())
        assertTrue(text.contains("reply-a")); assertTrue(text.contains("reply-b"))
        assertFalse(text.contains("999.0"))
    }

    @Test fun chunkBoundariesHandleSmallLimitsAndEmptyTextWithoutTruncation() {
        listOf("", "a".repeat(128), "🙂".repeat(100), "\n".repeat(1000)).forEach { payload ->
            val parts = PipelineBenchmarkTextExport.chunks(payload, 256)
            assertTrue(parts.isNotEmpty()); assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 256 })
            assertEquals(payload, parts.joinToString("") { it.substringAfter('\n') })
        }
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkTextExport.chunks("x", 255) }
    }

    @Test fun followupCorrelationAndTransferEvidenceShareProvenanceButRealSettingsSeparate() {
        val key = "followup_smart_turn_utterance_id"
        val transferKey = "capture_first_raw_transfer"
        val transfers = listOf(
            "capture_first_raw_ready prefixBytes=3200 firstSequence=17 lastSequence=18",
            "capture_first_raw_ready prefixBytes=6400 firstSequence=41 lastSequence=44")
        fun followup(id: String, duration: Long, transfer: String) = turn(id, duration = duration).copy(
            provenance = provenance.copy(configuration = provenance.configuration + mapOf(
                key to "next-$id", transferKey to transfer, "followup_smart_turn_mode" to "disabled",
                "capture_profile_requested" to "communication_noise_filtered")))
        val first = followup("first", 100, transfers[0])
        val second = followup("second", 300, transfers[1])
        val text = render(first, second)
        assertEquals(1, Regex("(?m)^GROUP G").findAll(text).count())
        assertEquals(1, Regex("(?m)^P[0-9]+:").findAll(text).count())
        assertTrue(text.contains("turn_total_ms: n=2 missing=0 p50=100.0 p95=300.0"))
        val provenanceLine = text.lineSequence().single { it.startsWith("P1:") }
        assertFalse(provenanceLine.contains(key)); assertFalse(provenanceLine.contains(transferKey))
        val correlation = text.lineSequence().filter { it.startsWith("thermalStatus=") }
            .map { org.json.JSONObject(it.substringAfter("correlation=")) }.toList()
        assertEquals(listOf("next-first", "next-second"), correlation.map { it.getString(key) })
        assertEquals(transfers, correlation.map { it.getString(transferKey) })
        for (setting in listOf("followup_smart_turn_mode" to "shadow_only_v1", "capture_profile_requested" to "different-profile")) {
            val third = followup("third", 9000, transfers[1]).let { it.copy(provenance = it.provenance.copy(
                configuration = it.provenance.configuration + setting)) }
            val changed = render(first, second, third)
            assertEquals(2, Regex("(?m)^GROUP G").findAll(changed).count())
            assertEquals(2, Regex("(?m)^P[0-9]+:").findAll(changed).count())
            assertTrue(changed.contains("turn_total_ms: n=2 missing=0 p50=100.0 p95=300.0"))
            assertTrue(changed.contains("turn_total_ms: n=1 missing=0 p50=9000.0"))
        }
    }

}
