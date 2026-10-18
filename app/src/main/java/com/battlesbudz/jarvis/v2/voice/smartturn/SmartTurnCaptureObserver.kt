package com.battlesbudz.jarvis.v2.voice.smartturn

import com.battlesbudz.jarvis.v2.voice.CaptureShadowFrame
import com.battlesbudz.jarvis.v2.voice.CaptureEndpointObserver
import com.battlesbudz.jarvis.v2.voice.CaptureEndpointState
import com.battlesbudz.jarvis.v2.voice.CaptureEndpointDecision

internal interface SmartTurnTelemetry {
    fun metric(name: String, value: Number?)
    fun configuration(name: String, value: String)
    fun event(message: String)
    fun markAt(name: String, monotonicMs: Long) {}
    fun completion(index: Int, completion: SmartTurnWorkCompletion, postPriorityWorkerMs: Double?) {}
}

/** Diagnostics are never a dependency for revocation or ordinary voice availability. */
internal class SafeSmartTurnTelemetry(private val delegate: SmartTurnTelemetry) : SmartTurnTelemetry {
    override fun metric(name: String, value: Number?) { runCatching { delegate.metric(name, value) } }
    override fun configuration(name: String, value: String) { runCatching { delegate.configuration(name, value) } }
    override fun event(message: String) { runCatching { delegate.event(message) } }
    override fun markAt(name: String, monotonicMs: Long) { runCatching { delegate.markAt(name, monotonicMs) } }
    override fun completion(index: Int, completion: SmartTurnWorkCompletion, postPriorityWorkerMs: Double?) {
        runCatching { delegate.completion(index, completion, postPriorityWorkerMs) }
    }
}

/** Bounded call-owned inference. Endpoint mode returns a current-pause verdict, never PCM ownership. */
internal class SmartTurnCaptureObserver(
    private val shadow: SmartTurnShadow,
    turnId: String,
    captureGeneration: Long,
    telemetry: SmartTurnTelemetry,
    private val ownerIsCurrent: () -> Boolean,
    private val admissionBlocker: () -> String?,
    private val clock: () -> Long = System::nanoTime,
    private val endpointEnabled: Boolean = false,
) : CaptureEndpointObserver {
    override val controlsEndpoint get() = endpointEnabled
    private val evidence = SafeSmartTurnTelemetry(telemetry)
    private var generation = SmartTurnGeneration(turnId, captureGeneration, 0)
    private val ring = ShortArray(SmartTurnAudioSnapshot.MAX_SAMPLES)
    private var size = 0
    private var next = 0
    private var boundary = 0L
    private var hasBoundary = false
    private var closed = false
    private var prioritized = false
    private var possibleSpeech = true
    private var observedFrame = false
    private var observedPossibleSpeechFrame = false
    private var attempts = 0
    private var accepted = 0
    private var busy = 0
    private var blocked = 0
    private var resumed = 0
    private var lastAttemptAt: Long? = null
    private var priorityAt: Long? = null
    private var endpointVerdict: CaptureEndpointState? = null
    private var requestAt: Long? = null
    private var lastFrame: CaptureShadowFrame? = null
    private var modelThroughSample: Long? = null
    private var pauseRequests = 0
    private var lastAcceptedBoundary = 0L
    private var fallbackReason = "awaiting_pause"
    private var readyListener: (() -> Unit)? = null
    @Synchronized override fun setEndpointReadyListener(listener: (() -> Unit)?) { readyListener = listener }
    private val results = mutableMapOf<Long, Int>()
    private val observedAt = mutableMapOf<Int, Long>()
    private val resumedObservations = mutableSetOf<Int>() // At most eight endpoint or three shadow observation identities.

    init {
        shadow.updateGeneration(generation)
        evidence.configuration("smart_turn_mode", if (endpointEnabled) "native_endpoint_v1" else "shadow_only_v1")
        evidence.configuration("smart_turn_model_sha256", SmartTurnModelSpec.SHA256)
        evidence.configuration("smart_turn_budget", if (endpointEnabled)
            "accepted_requests=8_per_turn,max_requests_per_pause=2,refresh_after_samples=8000,spacing_ms=500,pause_ms=350,deadline_ms=250,continue_classified_quiet_ms=3500,window_samples=128000,threads=1"
            else "attempts=3,spacing_ms=500,pause_ms=200,deadline_ms=250,window_samples=128000,threads=1")
        evidence.configuration("smart_turn_contention", "background_priority_requested;cancel_on_native_priority;overlap_may_remain_until_drain")
    }

    @Synchronized override fun onPcm(pcm16: ByteArray, captureSampleBoundary: Long) {
        if (closed || (prioritized && !endpointEnabled)) return
        if (!runCatching(ownerIsCurrent).getOrDefault(false)) { close(); return }
        if (pcm16.isEmpty() || pcm16.size % 2 != 0 || captureSampleBoundary < pcm16.size / 2 ||
            (hasBoundary && captureSampleBoundary - boundary != (pcm16.size / 2).toLong())) {
            onPriority("pcm_discontinuity")
            return
        }
        // Copy at most eight seconds, never retain the producer array or earlier audio.
        val count = minOf(pcm16.size / 2, ring.size)
        val offset = pcm16.size / 2 - count
        repeat(count) { index ->
            val position = (index + offset) * 2
            ring[next] = ((pcm16[position].toInt() and 255) or (pcm16[position + 1].toInt() shl 8)).toShort()
            next = (next + 1) % ring.size
        }
        size = minOf(ring.size, size + count)
        boundary = captureSampleBoundary
        hasBoundary = true
    }

    @Synchronized override fun onFrame(frame: CaptureShadowFrame) {
        if (closed) return
        if (!runCatching(ownerIsCurrent).getOrDefault(false)) { close(); return }
        if (!observedFrame) {
            observedFrame = true
            evidence.markAt("smart_turn_first_frame_observed", clock() / 1_000_000)
        }
        if (frame.hasSpeech && frame.possibleSpeech && !observedPossibleSpeechFrame) {
            observedPossibleSpeechFrame = true
            evidence.markAt("smart_turn_first_possible_speech_frame_captured", frame.capturedAtNanos / 1_000_000)
            evidence.markAt("smart_turn_first_possible_speech_frame_observed", clock() / 1_000_000)
        }
        if (frame.possibleSpeech && !possibleSpeech) {
            resumed++
            generation = generation.copy(revision = generation.revision + 1)
            shadow.updateGeneration(generation)
            endpointVerdict = null; requestAt = null; pauseRequests = 0
            // A new pause may use the remaining whole-turn budget after a cancelled proposal.
            if (endpointEnabled) prioritized = false
            evidence.metric("smart_turn_resumption_invalidations", resumed)
            observedAt.forEach { (index, capturedAt) ->
                if (resumedObservations.add(index)) {
                    evidence.metric("smart_turn_${index}_speech_resumed_after_observation", 1)
                    evidence.metric("smart_turn_${index}_resumption_after_ms", (frame.capturedAtNanos - capturedAt).coerceAtLeast(0) / 1e6)
                }
            }
        }
        possibleSpeech = frame.possibleSpeech
        lastFrame = frame
        if (prioritized) return
        drainResult()
        // One bounded refresh can correct an early CONTINUE, but only after 500ms
        // of new retained PCM. Wall-clock gaps and repeated wake events add no audio.
        // A pending/failed refresh retains the earlier CONTINUE, never promotes UNKNOWN.
        if (endpointEnabled && (endpointVerdict == CaptureEndpointState.COMPLETE || pauseRequests >= 2 ||
            (endpointVerdict == CaptureEndpointState.CONTINUE && boundary - lastAcceptedBoundary < 8_000))) return
        if (!frame.hasSpeech || frame.possibleSpeech || frame.silenceMs < (if (endpointEnabled) 350 else 200) ||
            size < 1600 || attempts >= (if (endpointEnabled) 8 else 3)) return
        val now = clock()
        if (lastAttemptAt?.let { now - it < 500_000_000L } == true) return
        lastAttemptAt = now
        if (!endpointEnabled) attempts++
        evidence.metric("smart_turn_observation_attempts", attempts)
        val blocker = runCatching(admissionBlocker).getOrDefault("admission_unavailable") ?: if (frame.backlogMs > 40) "capture_backlog" else null
        if (blocker != null) {
            fallbackReason = blocker
            blocked++
            evidence.metric("smart_turn_priority_or_backlog_skips", blocked)
            evidence.configuration("smart_turn_last_admission_blocker", blocker)
            return
        }
        if (shadow.isBusy()) {
            fallbackReason = "worker_busy"; busy++; evidence.metric("smart_turn_busy_skips", busy); return
        }
        if (boundary != frame.captureSampleBoundary) {
            fallbackReason = "retained_pcm_boundary_mismatch"
            evidence.configuration("smart_turn_last_admission_blocker", "retained_pcm_boundary_mismatch")
            return
        }
        val pcm = ShortArray(size) { ring[(next - size + it + ring.size) % ring.size] }
        val snapshot = SmartTurnAudioSnapshot.fromPcm16(generation, boundary, frame.capturedAtNanos, pcm)
        pcm.fill(0)
        val index = accepted
        val offer = shadow.offer(snapshot) { completion -> finished(index, completion) }
        evidence.configuration("smart_turn_last_offer", offer.name.lowercase())
        if (offer == SmartTurnOffer.ACCEPTED) {
            evidence.markAt("smart_turn_${index}_request_accepted", clock() / 1_000_000)
            evidence.markAt("smart_turn_${index}_source_captured", frame.capturedAtNanos / 1_000_000)
            if (endpointEnabled) { attempts++; pauseRequests++; lastAcceptedBoundary = boundary; evidence.metric("smart_turn_observation_attempts", attempts) }
            accepted++
            requestAt = frame.capturedAtNanos
            fallbackReason = "pending_model"
            results[boundary] = index
            observedAt[index] = frame.capturedAtNanos
            evidence.configuration("smart_turn_${index}_worker_completion", "pending;late_completion_retained_in_diagnostics")
            evidence.metric("smart_turn_${index}_actual_worker_wall_ms", null)
            evidence.metric("smart_turn_${index}_post_priority_worker_wall_ms", null)
            evidence.metric("smart_turn_accepted_observations", accepted)
            evidence.metric("smart_turn_${index}_capture_boundary", boundary)
            evidence.metric("smart_turn_${index}_silence_ms", frame.silenceMs)
            evidence.metric("smart_turn_${index}_source_age_ms", (now - frame.capturedAtNanos) / 1e6)
        }
    }

    @Synchronized override fun endpointDecision(frame: CaptureShadowFrame): CaptureEndpointDecision {
        if (!endpointEnabled || closed || !runCatching(ownerIsCurrent).getOrDefault(false)) return CaptureEndpointDecision(CaptureEndpointState.FALLBACK)
        if (lastFrame != frame || frame.possibleSpeech || !frame.hasSpeech) return CaptureEndpointDecision(CaptureEndpointState.FALLBACK)
        drainResult()
        // The verdict belongs to the exact speech revision. Newly classified quiet PCM may
        // extend that pause, but the capture owner must independently cover ALL final PCM.
        endpointVerdict?.let { return CaptureEndpointDecision(it, modelThroughSample) }
        // Reserve the first pause for model admission, but do not extend the ordinary
        // endpoint while waiting. This only keeps Gemma speculation from stealing its budget.
        if (!prioritized && attempts < 8 && requestAt == null && frame.silenceMs in 200 until 350)
            return CaptureEndpointDecision(CaptureEndpointState.PENDING)
        val age = requestAt?.let { clock() - it }
        if (!prioritized && age != null && age in 0 until 250_000_000L) return CaptureEndpointDecision(CaptureEndpointState.PENDING)
        evidence.configuration("smart_turn_endpoint_fallback", if (attempts >= 8) "attempt_budget_exhausted" else if (age != null && age >= 250_000_000L) "request_deadline" else fallbackReason)
        return CaptureEndpointDecision(CaptureEndpointState.FALLBACK)
    }

    private fun drainResult() {
        val result = shadow.takeResult(generation) ?: return
        val index = results[result.captureSampleBoundary] ?: return
        if (endpointEnabled) {
            val nextVerdict = result.probability?.takeIf { result.unknown == null }?.let {
                // Official pinned Pipecat local_smart_turn_v3.py: strict >0.5, not calibration.
                com.battlesbudz.jarvis.v2.voice.SmartTurnEndpointPolicy.modelState(it)
            }
            if (nextVerdict != null) {
                endpointVerdict = nextVerdict
                modelThroughSample = result.captureSampleBoundary
            }
            fallbackReason = result.unknown?.name?.lowercase() ?: "model_returned"
            requestAt = null
            evidence.configuration("smart_turn_endpoint_verdict", endpointVerdict?.name?.lowercase() ?: "fallback_${result.unknown?.name?.lowercase()}")
        }
        evidence.metric("smart_turn_${index}_probability", result.probability)
        evidence.configuration("smart_turn_${index}_outcome", result.unknown?.name?.lowercase() ?: if (endpointEnabled) endpointVerdict?.name?.lowercase().orEmpty() else "observed_only")
        evidence.event("smart_turn_shadow observation=$index probability=${result.probability} unknown=${result.unknown?.name ?: "none"}")
    }

    @Synchronized private fun finished(index: Int, completion: SmartTurnWorkCompletion) {
        // Exact old-request metadata remains safe after generation change. No probability is published here.
        val prefix = "smart_turn_${index}_"
        evidence.metric(prefix + "initialization_ms", completion.timing.initializationNanos?.div(1e6))
        evidence.metric(prefix + "frontend_ms", completion.timing.frontendNanos?.div(1e6))
        evidence.metric(prefix + "inference_ms", completion.timing.inferenceNanos?.div(1e6))
        evidence.metric(prefix + "actual_worker_wall_ms", completion.timing.totalNanos / 1e6)
        evidence.metric(prefix + "cancelled", if (completion.cancelled) 1 else 0)
        val overlapMs = priorityAt?.let { priority ->
            (completion.finishedAtNanos - maxOf(priority, completion.startedAtNanos)).coerceAtLeast(0) / 1e6
        }
        evidence.metric(prefix + "post_priority_worker_wall_ms", overlapMs)
        evidence.configuration(prefix + "worker_completion", "measured;completion_record_retained_in_diagnostics")
        evidence.completion(index, completion, overlapMs)
        // Notification is conflated; the collector checks identity/PCM again on its own lane.
        if (endpointEnabled && !closed) runCatching { readyListener?.invoke() }
    }

    @Synchronized override fun onPriority(reason: String) {
        if (closed || prioritized) return
        drainResult()
        prioritized = true
        priorityAt = clock()
        val busyAtPriority = shadow.isBusy()
        // A final-drain deferral must retain the accepted pause verdict. In particular,
        // dropping CONTINUE here would let the next partial raw frame use ordinary timing.
        val preserveComplete = endpointEnabled && endpointVerdict == CaptureEndpointState.COMPLETE &&
            reason in setOf("gemma_speculation", "endpoint_finalization")
        val preserveContinue = endpointEnabled && endpointVerdict == CaptureEndpointState.CONTINUE &&
            reason == "endpoint_finalization"
        if (!preserveComplete) {
            if (!preserveContinue) endpointVerdict = null
            requestAt = null
            // Retaining CONTINUE never retains authority for an in-flight refresh.
            // Cancel that work and fence its result before native priority; raw resumption
            // still invalidates the retained verdict on the next serialized capture event.
            generation = generation.copy(revision = generation.revision + 1)
            shadow.updateGeneration(generation)
        }
        if (!endpointEnabled) { ring.fill(0); size = 0 }
        evidence.configuration("smart_turn_revoked_for_priority", reason)
        evidence.metric("smart_turn_busy_at_priority", if (busyAtPriority) 1 else 0)
    }

    @Synchronized override fun onInvalidated(reason: String) {
        if (closed) return
        generation = generation.copy(revision = generation.revision + 1)
        shadow.updateGeneration(generation)
        ring.fill(0); size = 0; next = 0; possibleSpeech = true; hasBoundary = false
        endpointVerdict = null; requestAt = null; lastFrame = null; pauseRequests = 0
        if (endpointEnabled) prioritized = false
        evidence.configuration("smart_turn_capture_invalidation", reason)
    }

    @Synchronized override fun close() {
        if (closed) return
        drainResult()
        closed = true
        readyListener = null
        generation = generation.copy(revision = generation.revision + 1)
        shadow.updateGeneration(generation)
        ring.fill(0); size = 0
        evidence.metric("smart_turn_busy_at_capture_close", if (shadow.isBusy()) 1 else 0)
    }
}
