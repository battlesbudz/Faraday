package com.battlesbudz.jarvis.v2.sandbox

/**
 * Shapes a finished sandbox run into the tool-result receipt (Sandbox
 * Phase 2): honest and structured — the real exit code, the captured
 * output (already capped by the runner at the D4 surface limit, with the
 * truncation marker when it was cut), a timed-out flag in words, and the
 * runner's typed start error verbatim (for example "not installed").
 * Nothing here invents output: an empty stream is reported as no output.
 *
 * Pure function of [SandboxRunner.RunResult]; unit-tested directly.
 */
internal object SandboxRunReceipt {

    data class Receipt(val succeeded: Boolean, val message: String)

    fun format(result: SandboxRunner.RunResult): Receipt {
        result.error?.let { return Receipt(false, it) }
        val body = result.output.trimEnd().ifEmpty { "(no output)" }
        val truncation = if (result.truncated) "\n(output truncated)" else ""
        return when {
            result.timedOut -> Receipt(
                false,
                "Sandbox command timed out after ${result.durationMs / 1000} seconds and was stopped. " +
                    "Output captured before the stop:\n$body$truncation"
            )
            result.exitCode == 0 -> Receipt(
                true,
                "Sandbox command finished with exit code 0.\n$body$truncation"
            )
            else -> Receipt(
                false,
                "Sandbox command exited with code ${result.exitCode?.toString() ?: "unknown"}.\n$body$truncation"
            )
        }
    }
}
