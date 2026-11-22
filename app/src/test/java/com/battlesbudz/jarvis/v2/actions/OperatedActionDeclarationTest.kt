package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatedActionDeclarationTest {
    @Test fun catalogRegistrationRejectsMissingOrIncompleteDeclarations() {
        val tool = MobileToolCatalog.Tool("sample", description = "sample")
        try {
            OperatedActionDeclaration.requireComplete(tool, null)
            throw AssertionError("An action without a declaration must not register")
        } catch (_: IllegalArgumentException) { }

        val incomplete = OperatedActionDeclaration(
            risk = ActionControlPolicy.ActionClass.READ_ONLY,
            approvalClass = ActionControlPolicy.ApprovalClass.TASK_READ,
            gateTargetFields = setOf("missing"),
            contentFields = emptySet(),
            batchable = false,
            routable = true,
            offScreenEligible = true,
            maxConcurrentTasks = 1
        )
        try {
            OperatedActionDeclaration.requireComplete(tool, incomplete)
            throw AssertionError("A declaration referencing a missing argument must not register")
        } catch (_: IllegalArgumentException) { }

        MobileToolCatalog.all().forEach { registered ->
            assertNotNull(MobileToolCatalog.register(registered))
            assertTrue(registered.declaration!!.isComplete(registered.parameters.map { it.name }.toSet()))
        }
    }

    @Test fun declaredApprovalClassDrivesTheExecutionPolicy() {
        val read = ActionRequest("read_battery")
        val sensitive = ActionRequest("screen_tap", mapOf("target" to "n1", "token" to "0123456789abcdef"))
        val bound = ActionRequest("browse_submit", mapOf("token" to "0123456789abcdef"))

        assertEquals(ActionControlPolicy.ApprovalClass.TASK_READ,
            MobileToolCatalog.find(read.name)!!.declaration!!.approvalClass)
        assertEquals(ActionControlPolicy.ApprovalClass.EXACT_ACTION,
            ActionControlPolicy.approvalClass(sensitive))
        assertTrue(ActionControlPolicy.requiresExactApproval(sensitive))
        assertEquals(ActionControlPolicy.ApprovalClass.BOUND_ACTION,
            ActionControlPolicy.approvalClass(bound))
        assertTrue(ActionControlPolicy.requiresExactApproval(bound))
        assertFalse(ActionControlPolicy.requiresExactApproval(read))
    }

    @Test fun declaredRiskClassDrivesThePolicyRiskClassification() {
        assertEquals(ActionControlPolicy.ActionClass.READ_ONLY,
            ActionControlPolicy.actionClass(ActionRequest("read_battery")))
        assertEquals(ActionControlPolicy.ActionClass.APP_MUTATION,
            ActionControlPolicy.actionClass(ActionRequest("screen_tap", mapOf("target" to "n1"))))
        assertEquals(ActionControlPolicy.ActionClass.EXTERNAL_EFFECT,
            ActionControlPolicy.actionClass(ActionRequest("post_notification", mapOf("title" to "t", "text" to "x"))))
    }

    @Test fun concurrencyBatchRoutingAndOffScreenDeclarationsAreConsumed() {
        val declaration = MobileToolCatalog.find("screen_tap")!!.declaration!!
        assertEquals(1, declaration.maxConcurrentTasks)
        assertTrue(declaration.permitsStart(activeOperatedTasks = 0))
        assertFalse(declaration.permitsStart(activeOperatedTasks = 1))
        assertFalse(declaration.permitsBatch(actionCount = 2))
        assertFalse(declaration.permitsBatch(actionCount = 0))
        assertTrue(declaration.mayRoute())
        assertFalse(declaration.mayRunOffScreen())
        assertTrue(MobileToolCatalog.find("read_battery")!!.declaration!!.mayRunOffScreen())
    }

    @Test fun declaredTargetAndContentFieldsBindApprovalGrant() {
        val tool = MobileToolCatalog.find("screen_type")!!
        val declaration = tool.declaration!!
        val request = ActionRequest("screen_type", mapOf(
            "target" to "n3", "text" to "hello", "token" to "0123456789abcdef"))

        assertEquals(setOf("target"), declaration.gateTargetFields)
        assertEquals(setOf("text"), declaration.contentFields)
        assertEquals(mapOf("target" to "n3", "text" to "hello"), declaration.grantBinding(request))
        assertNull(declaration.grantBinding(request).keys.find { it == "token" })
    }
}
