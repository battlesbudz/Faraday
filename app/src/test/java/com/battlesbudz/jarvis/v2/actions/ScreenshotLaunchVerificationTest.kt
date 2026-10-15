package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.vision.ScreenshotVisionEvidence

import org.junit.Assert.*
import org.junit.Test

/**
 * Screenshot+vision launch verification contract (Battles 2026-10-15):
 * launch -> ~1s settle -> expected package in the foreground -> real
 * screenshot captured -> vision analysis over non-blank pixels -> Verified.
 * Every failure leg reports Unconfirmed, never success. JVM-pure: the
 * foreground/capture/analyze collaborators are fakes.
 */
class ScreenshotLaunchVerificationTest {

    private fun verifier(
        observationAvailable: Boolean = true,
        foreground: () -> String? = { "com.example.target" },
        capture: () -> ByteArray? = { byteArrayOf(1, 2, 3) },
        analyze: (ByteArray) -> ScreenshotVisionEvidence =
            { ScreenshotVisionEvidence(nonBlank = true, detections = 2, classification = "screen", totalMs = 42.0) },
        sleeps: MutableList<Long> = mutableListOf(),
        settleMs: Long = 1_000L,
        timeoutMs: Long = 50L,
        pollMs: Long = 10L
    ) = ScreenshotLaunchVerifier(
        observationAvailable = { observationAvailable },
        foregroundPackage = foreground,
        captureScreenshot = capture,
        analyzeScreenshot = analyze,
        sleeper = { sleeps += it },
        settleMs = settleMs,
        timeoutMs = timeoutMs,
        pollMs = pollMs
    )

    @Test fun verifiedWhenForegroundMatchesAndScreenshotAnalyzes() {
        var analyzed: ByteArray? = null
        val result = verifier(analyze = {
            analyzed = it
            ScreenshotVisionEvidence(nonBlank = true, detections = 2, classification = "screen", totalMs = 42.0)
        }).verify("com.example.target", "Target")
        assertEquals(LaunchVerification.Verified, result)
        assertNotNull("the captured screenshot bytes must reach the analyzer", analyzed)
    }

    @Test fun settleDelayRunsBeforeForegroundCheck() {
        val sleeps = mutableListOf<Long>()
        verifier(sleeps = sleeps).verify("com.example.target", "Target")
        assertEquals("the first sleep is the ~1s settle delay", 1_000L, sleeps.first())
    }

    @Test fun unconfirmedWhenExpectedPackageNeverForeground() {
        val result = verifier(foreground = { "com.example.other" })
            .verify("com.example.target", "Target")
        val unconfirmed = result as LaunchVerification.Unconfirmed
        assertTrue("the reason names the expected package: ${unconfirmed.reason}",
            unconfirmed.reason.contains("com.example.target"))
    }

    @Test fun pollRetriesUntilForegroundAppears() {
        var polls = 0
        var captures = 0
        val result = verifier(
            foreground = {
                polls++
                if (polls < 3) "com.example.other" else "com.example.target"
            },
            capture = { captures++; byteArrayOf(1) }
        ).verify("com.example.target", "Target")
        assertEquals(LaunchVerification.Verified, result)
        assertEquals("the screenshot is captured exactly once, after the foreground matches", 1, captures)
    }

    @Test fun unconfirmedWithoutObservationChannelAndNoCaptureAttempt() {
        var captures = 0
        val sleeps = mutableListOf<Long>()
        val result = verifier(
            observationAvailable = false,
            capture = { captures++; byteArrayOf(1) },
            sleeps = sleeps
        ).verify("com.example.target", "Target")
        assertTrue(result is LaunchVerification.Unconfirmed)
        assertEquals("no observation channel means fail fast: no capture, no settle sleep", 0, captures)
        assertTrue("no observation channel means fail fast: no settle sleep", sleeps.isEmpty())
    }

    @Test fun unconfirmedWhenForegroundChangesAfterCapture() {
        var analyzed = 0
        var foregroundCalls = 0
        val result = verifier(
            foreground = {
                foregroundCalls++
                if (foregroundCalls <= 1) "com.example.target" else "com.example.other"
            },
            analyze = {
                analyzed++
                ScreenshotVisionEvidence(nonBlank = true, detections = 2, classification = "screen", totalMs = 42.0)
            }
        ).verify("com.example.target", "Target")
        assertTrue("a capture from another app is not evidence of this launch", result is LaunchVerification.Unconfirmed)
        assertTrue(
            "the reason must name the switch: ${(result as LaunchVerification.Unconfirmed).reason}",
            result.reason.contains("foreground changed after screenshot capture")
        )
        assertEquals("another app's pixels must never reach analysis", 0, analyzed)
    }

    @Test fun unconfirmedWhenForegroundUnobservableAfterCapture() {
        var analyzed = 0
        var foregroundCalls = 0
        val result = verifier(
            foreground = {
                foregroundCalls++
                if (foregroundCalls <= 1) "com.example.target" else null
            },
            analyze = {
                analyzed++
                ScreenshotVisionEvidence(nonBlank = true, detections = 2, classification = "screen", totalMs = 42.0)
            }
        ).verify("com.example.target", "Target")
        assertTrue(result is LaunchVerification.Unconfirmed)
        assertEquals("an unobservable foreground after capture must not reach analysis", 0, analyzed)
    }

    @Test fun unconfirmedWhenForegroundChangesDuringAnalysis() {
        var analyzed = 0
        var foregroundCalls = 0
        val result = verifier(
            foreground = {
                foregroundCalls++
                // Poll (1) and after-capture recheck (2) see the target;
                // the foreground switches while analysis runs (3).
                if (foregroundCalls <= 2) "com.example.target" else "com.example.other"
            },
            analyze = {
                analyzed++
                ScreenshotVisionEvidence(nonBlank = true, detections = 2, classification = "screen", totalMs = 42.0)
            }
        ).verify("com.example.target", "Target")
        assertTrue("pixels analyzed while another app took the foreground prove nothing",
            result is LaunchVerification.Unconfirmed)
        assertTrue(
            "the reason must name the switch: ${(result as LaunchVerification.Unconfirmed).reason}",
            result.reason.contains("foreground changed during screenshot analysis")
        )
        assertEquals("analysis ran, but its result must be discarded", 1, analyzed)
    }

    @Test fun unconfirmedWhenScreenshotUnavailable() {
        var analyzed = 0
        val result = verifier(
            capture = { null },
            analyze = { analyzed++; ScreenshotVisionEvidence(true, 0, null, 0.0) }
        ).verify("com.example.target", "Target")
        assertTrue(result is LaunchVerification.Unconfirmed)
        assertEquals("no screenshot means the analyzer never runs", 0, analyzed)
    }

    @Test fun unconfirmedWhenAnalysisThrows() {
        val result = verifier(analyze = { throw IllegalStateException("could not decode") })
            .verify("com.example.target", "Target")
        assertTrue(result is LaunchVerification.Unconfirmed)
    }

    @Test fun unconfirmedWhenScreenshotBlank() {
        val result = verifier(
            analyze = { ScreenshotVisionEvidence(nonBlank = false, detections = 0, classification = null, totalMs = 1.0) }
        ).verify("com.example.target", "Target")
        assertTrue("a blank screenshot is not evidence of an opening", result is LaunchVerification.Unconfirmed)
    }

    @Test fun unconfirmedWhenNoExpectedPackage() {
        var captures = 0
        val result = verifier(capture = { captures++; byteArrayOf(1) }).verify(null, "Target")
        assertTrue(result is LaunchVerification.Unconfirmed)
        assertEquals("no expected package means nothing to verify against: no capture", 0, captures)
    }
}
