package com.battlesbudz.jarvis.v2.verification

import android.os.Looper
import com.google.ai.edge.litertlm.NativeAudioArtifactLease
import com.google.ai.edge.litertlm.NativeAudioIdentity
import com.google.ai.edge.litertlm.NativeAudioOwner
import com.google.ai.edge.litertlm.NativeAudioWorker
import com.google.ai.edge.litertlm.NativeClockContract
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Calls the shipping public owner API up to its first artifact lease check.
 * The reviewed SDK performs three real timing-v1 JNI/schema preflights first.
 * This does not establish usable clock alignment, snapshots or model timing.
 */
internal object NativeAudioTimingPreflightProbe {
    private const val WORKER_TIMEOUT_MS = 5_000L
    private const val CLEANUP_TIMEOUT_MS = 1_000L

    private class LeaseBoundaryReached : RuntimeException()

    class Result internal constructor(
        val sentinelMatched: Boolean,
        val leaseCheckCalls: Int,
        val immutablePathReads: Int,
        val workerTerminated: Boolean,
        val timedOut: Boolean,
        val interrupted: Boolean,
        val ownerReturned: Boolean,
        private val unexpectedFailure: Throwable?,
    ) {
        val passed: Boolean get() = sentinelMatched && leaseCheckCalls == 1 &&
            immutablePathReads == 0 && workerTerminated && !timedOut &&
            !interrupted && !ownerReturned && unexpectedFailure == null

        /** Fixed keys/scalars only: no raw clocks, exception messages or model data. */
        fun reportFields(): Map<String, Any> = linkedMapOf(
            "schema" to "android-native-timing-preflight-v1",
            "passed" to passed,
            "timing_v1_handshake_schema_verified" to passed,
            "lease_boundary_sentinel_matched" to sentinelMatched,
            "lease_check_calls" to leaseCheckCalls,
            "immutable_path_reads" to immutablePathReads,
            "worker_terminated" to workerTerminated,
            "worker_timed_out" to timedOut,
            "caller_interrupted" to interrupted,
            "owner_returned" to ownerReturned,
            "unexpected_failure" to (unexpectedFailure != null),
            "worker_timeout_ms" to WORKER_TIMEOUT_MS,
            "cleanup_timeout_ms" to CLEANUP_TIMEOUT_MS,
            "clock_contract" to "UNVERIFIED",
            "coverage" to "Real timing-v1 JNI handshake and schema preflight through public createOnWorker; stops at first artifact lease check",
            "not_covered" to "Clock calibration usability; timing snapshots; model reads or inference; native owner lifecycle; physical audio; device performance",
        )

        fun assertPassed() {
            if (!passed) throw AssertionError(
                "Native timing-v1 preflight must reach exactly the first lease sentinel, " +
                    "without reading a model path, returning an owner or leaving a live worker: ${reportFields()}",
                unexpectedFailure,
            )
        }
    }

    fun run(): Result {
        val caller = Thread.currentThread()
        val sentinel = LeaseBoundaryReached()
        val leaseChecks = AtomicInteger()
        val pathReads = AtomicInteger()
        val returnedOwner = AtomicBoolean()
        val observedFailure = AtomicReference<Throwable?>()
        val artifact = object : NativeAudioArtifactLease {
            override val immutablePath: String get() {
                pathReads.incrementAndGet()
                throw AssertionError("Timing handshake probe must never request a model path")
            }
            override fun checkImmutableAndLive() {
                leaseChecks.incrementAndGet()
                throw sentinel
            }
        }
        val thread = Thread({
            try {
                val worker = NativeAudioWorker.bindCurrentThread {
                    check(Thread.currentThread() !== caller && Looper.myLooper() == null) {
                        "Timing handshake probe requires its own non-Looper worker"
                    }
                }
                // UNVERIFIED intentionally makes no Android clock-contract claim.
                // The throwing lease stops before file access, wrapper allocation
                // and nativeCreate in the reviewed production SDK.
                val owner = NativeAudioOwner.createOnWorker(
                    worker, artifact, NativeAudioIdentity("timing-preflight", 1L),
                    maxSamples = 1, maxPacketSamples = 1, stepTokens = 1,
                    clockContract = NativeClockContract.UNVERIFIED,
                )
                returnedOwner.set(true)
                // Defensive bounded cleanup if a future SDK violates this boundary.
                check(owner.closeOnWorker(0L)) { "Unexpected native owner did not close" }
                throw AssertionError("Owner creation unexpectedly returned past the lease sentinel")
            } catch (failure: Throwable) {
                observedFailure.set(failure)
            }
        }, "jarvis-timing-preflight")
        // A wedged JNI call cannot safely be killed from Java. A still-live worker
        // always fails the probe; daemon status only permits process teardown.
        thread.isDaemon = true
        var timedOut = false
        var interrupted = false
        thread.start()
        try {
            thread.join(WORKER_TIMEOUT_MS)
            timedOut = thread.isAlive
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            if (thread.isAlive) {
                thread.interrupt()
                try { thread.join(CLEANUP_TIMEOUT_MS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) caller.interrupt()
        }
        val failure = observedFailure.get()
        return Result(
            sentinelMatched = failure === sentinel,
            leaseCheckCalls = leaseChecks.get(),
            immutablePathReads = pathReads.get(),
            workerTerminated = !thread.isAlive,
            timedOut = timedOut,
            interrupted = interrupted,
            ownerReturned = returnedOwner.get(),
            unexpectedFailure = failure.takeUnless { it === sentinel },
        )
    }
}
