package com.battlesbudz.jarvis.v2.actions

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M7 external-agent access (T24): caller authorization, narrow scope,
 * anti-recursion, no session-grant inheritance, and D11 non-evasion.
 */
class ExternalAgentAccessTest {
    private val battery = ActionRequest("read_battery")
    private val volume = ActionRequest("set_volume", mapOf("level" to "25"))

    private fun withFile(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("external-agent").toFile()
        try {
            test(File(directory, "tasks.json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun accessFor(
        store: ToolTaskStore = InMemoryToolTaskStore(),
        executor: ExternalActionExecutor = ExternalActionExecutor { ExecutionResult(true, "ok") }
    ): ExternalAgentAccess = ExternalAgentAccess(ToolTaskLedger(store), executor)

    private fun denied(result: ExternalAgentCallResult): ExternalAgentCallResult.Denied {
        assertTrue("expected Denied but was $result", result is ExternalAgentCallResult.Denied)
        return result as ExternalAgentCallResult.Denied
    }

    private fun executed(result: ExternalAgentCallResult): ExternalAgentCallResult.Executed {
        assertTrue("expected Executed but was $result", result is ExternalAgentCallResult.Executed)
        return result as ExternalAgentCallResult.Executed
    }

    @Test fun unknownCallerIsDeniedAndDispatchesNothing() {
        var effects = 0
        val access = accessFor(executor = ExternalActionExecutor {
            effects++
            ExecutionResult(true, "ok")
        })
        val result = denied(access.call("some.stranger", battery))
        assertEquals(ExternalDenialCode.CALLER_UNKNOWN, result.code)
        assertEquals(0, effects)
    }

    @Test fun unusableCallerIdIsDenied() {
        val access = accessFor()
        val result = denied(access.call("!!!", battery))
        assertEquals(ExternalDenialCode.CALLER_UNKNOWN, result.code)
    }

    @Test fun authorizedCallExecutesAndRecordsExternalReceipt() {
        val store = InMemoryToolTaskStore()
        val ledger = ToolTaskLedger(store)
        val grant = ExternalAgentAccess(ledger, ExternalActionExecutor {
            assertTrue(it is MobileAction.ReadBattery)
            ExecutionResult.battery(80)
        }).let { access ->
            val g = access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
            val done = executed(access.call("com.example.agent", battery))
            assertEquals(ExecutionResult.Outcome.SUCCEEDED, done.result.outcome)
            assertEquals(80, done.result.batteryPercent)
            g to done
        }
        val attempt = checkNotNull(ledger.get(grant.second.attemptId))
        assertEquals(ToolTaskState.SUCCEEDED, attempt.state)
        assertEquals(ToolAuthority.EXTERNAL_AGENT, attempt.authority)
        assertEquals(grant.first.id, attempt.grantId)
        assertEquals("Battery is at 80 percent.", attempt.result)
    }

    @Test fun functionOutsideGrantIsDenied() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        val result = denied(access.call("com.example.agent", volume))
        assertEquals(ExternalDenialCode.FUNCTION_NOT_GRANTED, result.code)
    }

    @Test fun unknownFunctionNameIsDenied() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        val result = denied(access.call("com.example.agent", ActionRequest("delete_everything")))
        assertEquals(ExternalDenialCode.FUNCTION_NOT_EXPOSED, result.code)
    }

    @Test fun authorizeRejectsNonExposedFunctions() {
        val access = accessFor()
        try {
            access.authorizeCaller("com.example.agent", "Example Agent", setOf("screen_tap"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        try {
            access.authorizeCaller("com.example.agent", "Example Agent", emptySet())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        try {
            access.authorizeCaller("com.example.agent", "Example Agent", setOf("browse_submit"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        assertTrue(access.grants().isEmpty())
    }

    @Test fun screenMutationsAreNeverExposed() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", ExternalAgentFunctions.EXPOSED)
        val tap = ActionRequest("screen_tap", mapOf("target" to "n3", "token" to "0123456789abcdef"))
        val result = denied(access.call("com.example.agent", tap))
        assertEquals(ExternalDenialCode.FUNCTION_NOT_EXPOSED, result.code)
    }

    @Test fun d11SubmitIsNeverExposed() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", ExternalAgentFunctions.EXPOSED)
        val submit = ActionRequest("browse_submit", mapOf("token" to "0123456789abcdef"))
        val result = denied(access.call("com.example.agent", submit))
        assertEquals(ExternalDenialCode.FUNCTION_NOT_EXPOSED, result.code)
    }

    @Test fun revokedCallerIsDenied() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        assertTrue(access.revokeCaller("com.example.agent"))
        val result = denied(access.call("com.example.agent", battery))
        assertEquals(ExternalDenialCode.CALLER_REVOKED, result.code)
    }

    @Test fun disabledCallerIsDeniedUntilReEnabled() {
        var effects = 0
        val access = accessFor(executor = ExternalActionExecutor {
            effects++
            ExecutionResult(true, "ok")
        })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        assertTrue(access.setCallerEnabled("com.example.agent", false))
        assertEquals(ExternalDenialCode.CALLER_DISABLED, denied(access.call("com.example.agent", battery)).code)
        assertEquals(0, effects)
        assertTrue(access.setCallerEnabled("com.example.agent", true))
        assertTrue(executed(access.call("com.example.agent", battery)).result.succeeded)
        assertEquals(1, effects)
    }

    @Test fun reAuthorizationReplacesTheGrant() {
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery", "set_volume"))
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        assertEquals(1, access.grants().count { it.callerId == "com.example.agent" })
        assertEquals(ExternalDenialCode.FUNCTION_NOT_GRANTED,
            denied(access.call("com.example.agent", volume)).code)
        assertTrue(executed(access.call("com.example.agent", battery)).result.succeeded)
    }

    @Test fun userDenialBlocksExternalCallersToo() {
        val store = InMemoryToolTaskStore()
        val ledger = ToolTaskLedger(store)
        val access = ExternalAgentAccess(ledger, ExternalActionExecutor { ExecutionResult(true, "ok") })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        ledger.recordSourceDenial("phone")
        val result = denied(access.call("com.example.agent", battery))
        assertEquals(ExternalDenialCode.DENIED_PERMISSION, result.code)
    }

    @Test fun invalidArgumentsDispatchNothing() {
        var effects = 0
        val access = accessFor(executor = ExternalActionExecutor {
            effects++
            ExecutionResult(true, "ok")
        })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("set_volume"))
        val bad = ActionRequest("set_volume", mapOf("level" to "999"))
        val result = denied(access.call("com.example.agent", bad))
        assertEquals(ExternalDenialCode.INVALID_ARGUMENTS, result.code)
        assertEquals(0, effects)
    }

    @Test fun recursiveExternalCallIsBounded() {
        lateinit var access: ExternalAgentAccess
        var inner: ExternalAgentCallResult? = null
        access = ExternalAgentAccess(ToolTaskLedger(), ExternalActionExecutor {
            inner = access.call("com.example.agent", ActionRequest("show_schedule"))
            ExecutionResult(true, "outer ok")
        })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery", "show_schedule"))
        val outer = executed(access.call("com.example.agent", battery))
        assertTrue(outer.result.succeeded)
        val blocked = denied(checkNotNull(inner))
        assertEquals(ExternalDenialCode.RECURSION_BOUNDED, blocked.code)
    }

    @Test fun externalCallCarriesNoSessionAuthority() {
        val store = InMemoryToolTaskStore()
        val ledger = ToolTaskLedger(store)
        val access = ExternalAgentAccess(ledger, ExternalActionExecutor { ExecutionResult(true, "ok") })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("set_volume"))
        // The boundary takes no session token and consults no session
        // grant: the attempt is admitted purely under the caller's grant.
        val done = executed(access.call("com.example.agent", volume))
        val attempt = checkNotNull(ledger.get(done.attemptId))
        assertEquals(ToolAuthority.EXTERNAL_AGENT, attempt.authority)
        assertTrue(ledger.journal().approvals.isEmpty())
    }

    @Test fun grantSurvivesFileRoundTrip() = withFile { file ->
        val first = ExternalAgentAccess(ToolTaskLedger(FileToolTaskStore(file)),
            ExternalActionExecutor { ExecutionResult(true, "ok") })
        val grant = first.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        val reopened = ExternalAgentAccess(ToolTaskLedger(FileToolTaskStore(file)),
            ExternalActionExecutor { ExecutionResult(true, "ok") })
        val restored = reopened.grants().single { it.callerId == "com.example.agent" }
        assertEquals(grant.id, restored.id)
        assertEquals(setOf("read_battery"), restored.allowedFunctions)
        assertTrue(executed(reopened.call("com.example.agent", battery)).result.succeeded)
    }

    @Test fun handEditedGrantCannotBroadenScope() = withFile { file ->
        val access = ExternalAgentAccess(ToolTaskLedger(FileToolTaskStore(file)),
            ExternalActionExecutor { ExecutionResult(true, "ok") })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        val root = JSONObject(file.readText())
        val grants = root.getJSONArray("externalAgentGrants")
        grants.getJSONObject(0).getJSONArray("allowedFunctions").put("screen_tap")
        file.writeText(root.toString())
        try {
            ToolTaskLedger(FileToolTaskStore(file)).journal()
            fail("expected ToolTaskStorageException")
        } catch (e: ToolTaskStorageException) {
            assertEquals(ToolTaskStorageFailure.INVALID_CONTENT, e.failure)
        }
    }

    @Test fun legacyJournalWithoutGrantsReadsCleanly() = withFile { file ->
        // Journals written before M7 have no externalAgentGrants key;
        // they decode with an empty grant list.
        val access = ExternalAgentAccess(ToolTaskLedger(FileToolTaskStore(file)),
            ExternalActionExecutor { ExecutionResult(true, "ok") })
        access.authorizeCaller("com.example.agent", "Example Agent", setOf("read_battery"))
        val root = JSONObject(file.readText())
        root.remove("externalAgentGrants")
        file.writeText(root.toString())
        val journal = ToolTaskLedger(FileToolTaskStore(file)).journal()
        assertTrue(journal.externalAgentGrants.isEmpty())
    }

    // ---------- M7 service/provider layer ----------

    @Test fun providerHoldsSingletonAcrossInit() {
        val ledger = ToolTaskLedger(InMemoryToolTaskStore())
        val executor = ExternalActionExecutor { ExecutionResult(true, "ok") }
        ExternalAgentProvider.init(ledger, executor)
        assertNotNull(ExternalAgentProvider.get())
        // Second init does not replace the instance.
        val first = ExternalAgentProvider.get()
        ExternalAgentProvider.init(ToolTaskLedger(InMemoryToolTaskStore()), executor)
        assertSame(first, ExternalAgentProvider.get())
    }

    @Test fun serviceDeniesUnlistedFunctionsHonestly() {
        // The boundary refuses anything outside the exposed five.
        val access = accessFor()
        access.authorizeCaller("com.example.agent", "Example", setOf("read_battery"))
        val result = denied(access.call("com.example.agent", ActionRequest("browse_submit", mapOf("token" to "x"))))
        assertEquals(ExternalDenialCode.FUNCTION_NOT_EXPOSED, result.code)
        assertTrue(result.message.contains("never exposed"))
    }

    @Test fun grantRoundTripThroughProviderBackedBoundary() {
        val access = accessFor()
        val grant = access.authorizeCaller("com.example.agent", "Example Agent",
            setOf("read_battery", "set_volume"))
        assertEquals("com.example.agent", grant.callerId)
        assertEquals(setOf("read_battery", "set_volume"), grant.allowedFunctions)
        assertTrue(access.grants().any { it.callerId == "com.example.agent" })
        assertTrue(access.revokeCaller("com.example.agent"))
        assertTrue(access.grants().first { it.callerId == "com.example.agent" }.revoked)
    }

    @Test fun exposedSetIsExactlyTheFive() {
        assertEquals(
            setOf("read_battery", "show_schedule", "media_control", "set_volume", "post_notification"),
            ExternalAgentFunctions.EXPOSED,
        )
    }
}
