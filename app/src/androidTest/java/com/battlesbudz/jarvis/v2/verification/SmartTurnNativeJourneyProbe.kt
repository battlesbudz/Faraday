package com.battlesbudz.jarvis.v2.verification

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import com.battlesbudz.jarvis.v2.voice.AdaptiveTurnEnd
import com.battlesbudz.jarvis.v2.voice.CaptureEndpointDecision
import com.battlesbudz.jarvis.v2.voice.CaptureEndpointState
import com.battlesbudz.jarvis.v2.voice.NativePauseEndpointPolicy
import com.battlesbudz.jarvis.v2.voice.RawVadCoverage
import com.battlesbudz.jarvis.v2.voice.SmartTurnEndpointPolicy
import com.battlesbudz.jarvis.v2.voice.smartturn.NativeSmartTurnBackend
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test APK only. One native owner, two frozen synthetic inputs, no microphone/model download. */
internal object SmartTurnNativeJourneyProbe {
    private const val WORKER_TIMEOUT_MS = 45_000L
    private const val CLEANUP_TIMEOUT_MS = 2_000L
    private const val MODEL_BYTES = 8_679_182
    private const val MODEL_SHA256 = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
    private const val FILE_NAME = "smart-turn-v3.2-cpu.onnx"

    class Result(private val fields: Map<String, Any>) {
        fun reportFields(): Map<String, Any> = fields
        fun assertPassed() {
            check(fields["passed"] == true) { "Smart Turn native/control probe failed: $fields" }
        }
    }

    fun run(instrumentation: Instrumentation, remote: String?): Result {
        require(remote != null && remote.matches(Regex(
            "/data/local/tmp/jarvis-smart-turn-input-[0-9]+/smart-turn-v3\\.2-cpu\\.onnx")))
        val cancelled = AtomicBoolean(false)
        val verified = AtomicBoolean(false)
        val privateDeleted = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val observations = AtomicReference<Map<String, Any>>(emptyMap())
        val caller = Thread.currentThread()
        val thread = Thread({
            var owner: NativeSmartTurnBackend? = null
            val directory = File(instrumentation.targetContext.cacheDir, "smart-turn-native-input")
            val input = File(directory, FILE_NAME)
            var ownsDirectory = false
            try {
                check(directory.mkdir()) { "Could not allocate private Smart Turn input" }
                ownsDirectory = true
                // Shell FD bypasses scoped-storage reads without granting storage permission.
                // Validated path is the controller's input tree, never the evidence tree.
                ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("cat $remote")
                ).use { source -> input.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count = 0
                    while (true) {
                        check(!cancelled.get()) { "Smart Turn input read cancelled" }
                        val read = source.read(buffer, 0, minOf(buffer.size, MODEL_BYTES + 1 - count))
                        if (read < 0) break
                        check(read > 0 && count + read <= MODEL_BYTES) { "Smart Turn input byte cap" }
                        output.write(buffer, 0, read)
                        count += read
                    }
                    check(count == MODEL_BYTES) { "Incomplete Smart Turn input" }
                } }
                check(!cancelled.get())
                owner = NativeSmartTurnBackend(input)
                verified.set(true) // Production constructor verified immutable model size/hash.
                val silence = fixture(instrumentation, "silence_8s", 128_000,
                    "24a046dc04fefdb652e4077b41162490b344a4dd45f918505477f84c592f3070")
                val tone = fixture(instrumentation, "tone_440hz_1s", 16_000,
                    "97baea0a56c5b48504ea4b65b41148c6d55dc494d22652b3db7cedb295876efe")
                val complete = owner.infer(silence, 1, cancelled)
                check(complete.probability.isFinite() && complete.probability > 0.5f && complete.probability <= 1f)
                check(complete.frontendNanos >= 0 && complete.inferenceNanos >= 0)
                check(SmartTurnEndpointPolicy.modelState(complete.probability) == CaptureEndpointState.COMPLETE)

                // Exercise prepare/cancel/infer and then reuse that same native session.
                var cancellationRejected = false
                try { owner.infer(tone, 2, AtomicBoolean(true)) }
                catch (_: IllegalStateException) { cancellationRejected = true }
                check(cancellationRejected) { "Pre-cancelled inference published a result" }
                check(!cancelled.get())
                val continuing = owner.infer(tone, 3, cancelled)
                check(continuing.probability.isFinite() && continuing.probability in 0f..0.5f)
                check(continuing.frontendNanos >= 0 && continuing.inferenceNanos >= 0)
                check(SmartTurnEndpointPolicy.modelState(continuing.probability) == CaptureEndpointState.CONTINUE)

                val fallback = AdaptiveTurnEnd.Decision(1500L, "uncertain")
                val raw = RawVadCoverage(256_000L, 128_000L, 112_000L, 250)
                fun eligibility(hasSpeech: Boolean) = NativePauseEndpointPolicy.eligibility(
                    hasSpeech, 1000L, false, 1000L, raw, false, 0L, true)
                fun decide(probability: Float, hasSpeech: Boolean) = SmartTurnEndpointPolicy.decision(
                    fallback, CaptureEndpointDecision(SmartTurnEndpointPolicy.modelState(probability), 128_000L),
                    eligibility(hasSpeech), true, raw, 256_000L, 1000L)
                check(decide(complete.probability, true).silenceMs == 350L)
                check(decide(continuing.probability, true).silenceMs == 3500L)
                // Silence's high score is not speech evidence and cannot authorize capture end.
                val noSpeech = decide(complete.probability, false)
                check(noSpeech.silenceMs == fallback.silenceMs && noSpeech.cue == fallback.cue)
                observations.set(linkedMapOf(
                    "silence_probability" to complete.probability,
                    "tone_probability" to continuing.probability,
                    "silence_frontend_nanos" to complete.frontendNanos,
                    "silence_inference_nanos" to complete.inferenceNanos,
                    "tone_frontend_nanos" to continuing.frontendNanos,
                    "tone_inference_nanos" to continuing.inferenceNanos,
                    "fixtures" to 2,
                    "native_requests" to 3,
                    "pre_cancel_rejected" to true,
                    "session_reused_after_cancel" to true,
                    "control_input_compatible" to true,
                    "no_speech_complete_ignored" to true,
                ))
            } catch (_: Throwable) {
                // Fixed scalar failure only: never publish model, PCM, tensors or exception text.
                failed.set(true)
            } finally {
                try {
                    owner?.close() // Only the owning worker closes after native work returns.
                    closed.set(owner != null)
                } catch (_: Throwable) { failed.set(true) }
                if (ownsDirectory) {
                    privateDeleted.set((!input.exists() || input.delete()) && directory.delete())
                }
            }
        }, "jarvis-smart-turn-native-probe").apply { isDaemon = true }
        var timedOut = false
        var interrupted = false
        thread.start()
        try {
            thread.join(WORKER_TIMEOUT_MS)
            timedOut = thread.isAlive
        } catch (_: InterruptedException) { interrupted = true }
        finally {
            if (thread.isAlive) {
                cancelled.set(true)
                // Do not enter JNI from the caller: even cancellation can wait on a
                // native lock. A live worker fails; the controller owns process teardown.
                thread.interrupt()
                try { thread.join(CLEANUP_TIMEOUT_MS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) caller.interrupt()
        }
        val passed = !thread.isAlive && !timedOut && !interrupted && !failed.get() &&
            verified.get() && privateDeleted.get() && closed.get() && observations.get().isNotEmpty()
        return Result(linkedMapOf<String, Any>(
            "schema" to "android-smart-turn-native-v1",
            "passed" to passed,
            "model_bytes" to MODEL_BYTES,
            "model_sha256" to MODEL_SHA256,
            "input_hash_verified" to verified.get(),
            "private_copy_deleted" to privateDeleted.get(),
            "closed_on_worker" to closed.get(),
            "worker_terminated" to !thread.isAlive,
            "worker_timed_out" to timedOut,
            "caller_interrupted" to interrupted,
            "unexpected_failure" to failed.get(),
            "worker_timeout_ms" to WORKER_TIMEOUT_MS,
            "cleanup_timeout_ms" to CLEANUP_TIMEOUT_MS,
            "coverage" to "Real Android JNI, production hash-bound wrapper, synthetic control input compatibility",
            "not_covered" to "Natural-language endpoint accuracy; physical microphone/audio; Fold latency",
        ).apply { putAll(observations.get()) })
    }

    private fun fixture(instrumentation: Instrumentation, name: String, samples: Int, expectedHash: String): FloatArray {
        // AAPT expands source .gz assets and removes that suffix in the test APK.
        val bytes = instrumentation.context.assets.open("smart-turn/$name.pcm16le").use { source ->
            val data = ByteArray(samples * 2)
            var count = 0
            while (count < data.size) {
                val read = source.read(data, count, data.size - count)
                check(read > 0)
                count += read
            }
            check(source.read() == -1)
            data
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == expectedHash)
        return FloatArray(samples) { index ->
            ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
        }
    }
}
