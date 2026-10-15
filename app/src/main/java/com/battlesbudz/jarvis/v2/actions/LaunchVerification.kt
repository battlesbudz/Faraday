package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.vision.ScreenshotVisionEvidence

import java.util.concurrent.TimeoutException

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Screenshot+vision launch verification (Battles 2026-10-15, built on top
 * of Jerry's 3b66763 conservative restore — not a revert of it).
 *
 * Jerry's restore re-established the honest rule: a clean startActivity
 * return verifies nothing, so an unobserved launch stays
 * UNKNOWN_COMPLETION. This file adds the verification mechanism Battles
 * specified on top of that rule: after a launch, wait ~1s for the app to
 * reach the foreground, capture a screenshot, analyze it with the existing
 * vision stack, and only then claim the app opened.
 *
 * The clean-return heuristic is gone, not weakened: [ScreenshotLaunchVerifier]
 * is the only path from a submitted launch to a verified one, and every
 * failure mode below returns [LaunchVerification.Unconfirmed], never a
 * false success. In particular, when screenshot capture is unavailable
 * (accessibility service disabled, test/emulator context without a
 * capture channel, capture error/timeout) verification reports
 * "could not be confirmed" — the production executor's default verifier
 * fails fast in that case via [observationAvailable] instead of polling a
 * channel that cannot answer.
 *
 * Evidence legs for [LaunchVerification.Verified], all required:
 * 1. the accessibility/UI observation shows the expected package in the
 *    foreground after the launch;
 * 2. a real screenshot of that screen was captured post-launch;
 * 3. the foreground package is re-observed AFTER the capture and still
 *    matches: a capture taken after the foreground switched belongs to
 *    another app and proves nothing about the requested launch. A fresh
 *    capture alone does not bind identity throughout the operation.
 * 4. the existing vision stack analyzed the screenshot and the pixels
 *    were non-blank (a black/empty capture is not evidence of an opening).
 * 5. the foreground package is re-observed AFTER vision analysis and
 *    still matches: analysis runs up to 90s of inference, and a switch
 *    during analysis leaves stale pixels that prove nothing about
 *    this launch. A Verified receipt requires the target to still be
 *    foreground when the analysis result is trusted.
 * The foreground-package observation is what ties the screenshot to the
 * target app: the vision models describe what is visible, the package
 * observation identifies whose screen it is.
 *
 * OCR note: the repo has no text-recognition engine (no ML Kit, no OCR in
 * :phoneinference), and Battles forbade building a new vision stack, so
 * this layer uses the existing NativeVision analysis (detection /
 * segmentation / pose / classification) plus the foreground-package
 * observation. It does NOT read text out of the screenshot. If literal OCR
 * of the screenshot is wanted later, that is a new dependency (e.g. ML Kit
 * Text Recognition) and a separate decision — this layer must not claim
 * OCR it does not perform.
 *
 * Blocking: [LaunchVerifier.verify] blocks the calling thread for the
 * settle delay plus the foreground poll plus screenshot analysis.
 * Callers must dispatch launches off the main thread.
 */
sealed interface LaunchVerification {
    /** All five evidence legs held: the launch counts as a verified opening. */
    data object Verified : LaunchVerification

    /** Any leg missing or failed: the launch stays unconfirmed. Never success. */
    data class Unconfirmed(val reason: String) : LaunchVerification
}

/** One launch-verification decision for the expected package. */
fun interface LaunchVerifier {
    fun verify(expectedPackage: String?, label: String): LaunchVerification
}

/**
 * Screenshot+vision launch verification. All collaborators are seams so
 * JVM tests can drive the contract deterministically and instrumentation
 * tests can wire the real UiDevice capture + the real NativeVision
 * analyzer while production wires the accessibility-service screenshot.
 */
class ScreenshotLaunchVerifier(
    /** Whether any foreground-observation channel exists at all. Fail-fast when false. */
    private val observationAvailable: () -> Boolean,
    /** Foreground package right now; null when it cannot be observed this poll. */
    private val foregroundPackage: () -> String?,
    /** One post-launch screenshot as encoded image bytes; null when capture is unavailable. */
    private val captureScreenshot: () -> ByteArray?,
    /** Vision analysis of the screenshot; throws on undecodable/failed analysis. */
    private val analyzeScreenshot: (ByteArray) -> ScreenshotVisionEvidence,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val onDiagnostic: (String) -> Unit = {},
    /** Settle delay after the launch before checking the foreground (Battles's ~1s). */
    private val settleMs: Long = 1_000L,
    /** How long to poll for the expected package to reach the foreground. */
    private val timeoutMs: Long = 10_000L,
    private val pollMs: Long = 250L
) : LaunchVerifier {
    override fun verify(expectedPackage: String?, label: String): LaunchVerification {
        try {
            if (expectedPackage.isNullOrBlank()) {
                onDiagnostic("launch_verify label=$label result=unconfirmed reason=no_expected_package")
                return LaunchVerification.Unconfirmed("no expected package to verify against")
            }
            if (!observationAvailable()) {
                // No observation channel (e.g. accessibility service disabled):
                // fail fast instead of polling a channel that cannot answer.
                onDiagnostic("launch_verify label=$label result=unconfirmed reason=observation_unavailable")
                return LaunchVerification.Unconfirmed("foreground observation is unavailable")
            }
            // Wait ~1s for the launched app to reach the foreground.
            sleeper(settleMs)
            // Bounded poll budget, not a wall-clock deadline: the loop must
            // terminate even when the sleeper is a fake that does not
            // advance wall-clock time (JVM tests). With a real sleeper the
            // worst case is settleMs + maxPolls * pollMs ~= settle + timeout.
            val maxPolls = (timeoutMs / pollMs).coerceAtLeast(1)
            var polls = 0
            while (true) {
                val foreground = foregroundPackage()
                if (foreground == expectedPackage) break
                if (polls >= maxPolls) {
                    onDiagnostic("launch_verify label=$label result=unconfirmed reason=foreground_timeout saw=$foreground")
                    return LaunchVerification.Unconfirmed(
                        "expected $expectedPackage in the foreground, saw ${foreground ?: "nothing observable"}"
                    )
                }
                polls++
                sleeper(pollMs)
            }
            val bytes = captureScreenshot()
            if (bytes == null) {
                onDiagnostic("launch_verify label=$label result=unconfirmed reason=screenshot_unavailable")
                return LaunchVerification.Unconfirmed("screenshot capture is unavailable")
            }
            // Recheck foreground identity AFTER capture, before analysis:
            // the foreground may have switched between the pre-capture
            // poll and the capture itself, in which case these pixels are
            // another app's screen. Analyzing them (up to 90s of
            // inference) would prove nothing about this launch.
            val afterCapture = foregroundPackage()
            if (afterCapture != expectedPackage) {
                onDiagnostic("launch_verify label=$label result=unconfirmed " +
                    "reason=foreground_changed_after_capture saw=$afterCapture")
                return LaunchVerification.Unconfirmed(
                    "foreground changed after screenshot capture, saw ${afterCapture ?: "nothing observable"}"
                )
            }
            val evidence = analyzeScreenshot(bytes)
            // Recheck foreground identity AFTER analysis, before trusting
            // the result: analysis runs up to 90s of inference, and the
            // foreground may have switched while it ran. A Verified receipt
            // for pixels of an app that is no longer foreground would prove
            // nothing about this launch.
            val afterAnalysis = foregroundPackage()
            if (afterAnalysis != expectedPackage) {
                onDiagnostic("launch_verify label=$label result=unconfirmed " +
                    "reason=foreground_changed_during_analysis saw=$afterAnalysis")
                return LaunchVerification.Unconfirmed(
                    "foreground changed during screenshot analysis, saw ${afterAnalysis ?: "nothing observable"}"
                )
            }
            if (!evidence.nonBlank) {
                onDiagnostic("launch_verify label=$label result=unconfirmed reason=blank_screenshot")
                return LaunchVerification.Unconfirmed("screenshot contained no visible content")
            }
            onDiagnostic(
                "launch_verify label=$label result=verified expected=$expectedPackage " +
                    "detections=${evidence.detections} " +
                    "classification=${evidence.classification} vision_ms=${evidence.totalMs.toLong()}"
            )
            return LaunchVerification.Verified
        } catch (timeout: TimeoutCancellationException) {
            onDiagnostic("launch_verify label=$label result=unconfirmed reason=analysis_timeout")
            return LaunchVerification.Unconfirmed("screenshot analysis timed out")
        } catch (cancelled: CancellationException) {
            // Genuine coroutine cancellation is not an Unconfirmed receipt: propagate it.
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            onDiagnostic("launch_verify label=$label result=unconfirmed reason=interrupted")
            return LaunchVerification.Unconfirmed("verification was interrupted")
        } catch (timeout: TimeoutException) {
            // NativeVision's bounded blocking analysis throws
            // java.util.concurrent.TimeoutException on timeout (its
            // TimeoutCancellationException constructors are internal in
            // pinned coroutines 1.9.0 and cannot be constructed).
            onDiagnostic("launch_verify label=$label result=unconfirmed reason=analysis_timeout")
            return LaunchVerification.Unconfirmed("screenshot analysis timed out")
        } catch (error: Exception) {
            onDiagnostic("launch_verify label=$label result=unconfirmed reason=error:${error.message?.take(80)}")
            return LaunchVerification.Unconfirmed("verification failed: ${error.message ?: "unknown error"}")
        }
    }
}
