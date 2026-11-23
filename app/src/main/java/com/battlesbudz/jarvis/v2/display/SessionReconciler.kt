package com.battlesbudz.jarvis.v2.display

/** Ordered recovery ports let launch reconciliation share S10/A7 stores without owning them. */
interface SessionRecoveryPorts {
    fun releasePreviousProcessDisplays(): SweepResult
    fun clearLocksWithoutRunner(): SweepResult
    fun closeRunnerRecordsWithoutTaskEnded(): SweepResult
    fun writeLaunchDiagnostic(result: SessionReconciliation)
}

data class SweepResult(val found: Int, val released: Int, val orphaned: Int = 0, val details: List<String> = emptyList())
data class SessionReconciliation(val displays: SweepResult, val locks: SweepResult, val runners: SweepResult)

class SessionReconciler(private val ports: SessionRecoveryPorts) {
    fun reconcileBeforeTaskAdmission(): SessionReconciliation {
        val result = SessionReconciliation(
            displays = ports.releasePreviousProcessDisplays(),
            locks = ports.clearLocksWithoutRunner(),
            runners = ports.closeRunnerRecordsWithoutTaskEnded(),
        )
        ports.writeLaunchDiagnostic(result)
        return result
    }
}
