package com.battlesbudz.jarvis.v2.actions

import org.junit.Assert.*
import org.junit.Test

/**
 * M4: the D11 approval admission for browse_submit. Deterministic; the
 * [BrowserSession] is JVM-pure with a fake clock and deterministic tokens.
 */
class BrowserApprovalAdmissionTest {
    private var tokenCounter = 0
    private fun testSession() = BrowserSession(
        clock = { 1_700_000_000_000L },
        newToken = { "t${tokenCounter++}".padEnd(16, '0') }
    )

    private fun openLogin(session: BrowserSession): BrowserPage =
        session.openPage(
            url = "https://example.com/login",
            title = "Example login",
            textExcerpt = "Sign in to Example",
            links = listOf(BrowserLink("l0", "Home", "https://example.com/")),
            forms = listOf(
                BrowserForm(
                    id = "form0",
                    actionUrl = "https://example.com/session",
                    method = "POST",
                    fields = listOf(
                        BrowserField("f0", "Email", FieldKind.EMAIL),
                        BrowserField("f1", "Password", FieldKind.PASSWORD, secret = true)
                    ),
                    submitLabel = "Sign in"
                )
            )
        ).also { session.noteFingerprint("fp-login") }

    private fun submitRequest(token: String) =
        ActionRequest("browse_submit", mapOf("token" to token))

    private fun parkedAttempt(request: ActionRequest, actionRevision: Long = 1L) =
        ToolTaskAttempt(
            id = "attempt-1", generation = 0, state = ToolTaskState.WAITING_APPROVAL,
            request = request, createdAtMs = 1L, updatedAtMs = 1L,
            groupId = "group-1", stepId = "step-1", actionRevision = actionRevision
        )

    private fun exactApproval(request: ActionRequest, actionRevision: Long = 1L) =
        ActionApprovalRequest(
            id = "approval-1", taskId = "group-1", stepId = "step-1", provider = "native",
            action = request, schemaVersion = MobileToolCatalog.VERSION, revision = actionRevision,
            fingerprint = "fp", createdAtMs = 1L, consumed = false
        )

    @Test fun nonBrowseRequestsNeedNoAdmission() {
        val session = testSession()
        val admission = BrowserApprovalAdmission(session)
        val request = ActionRequest("read_battery")
        assertFalse(admission.needsAdmission(request))
        assertTrue(admission.admitForApproval(parkedAttempt(request), exactApproval(request)) is AdmitResult.Admitted)
    }

    @Test fun exactApprovalAdmitsAndSubmitConfirms() {
        val session = testSession()
        val page = openLogin(session)
        val request = submitRequest(page.pageToken)
        val admission = BrowserApprovalAdmission(session)
        assertTrue(admission.needsAdmission(request))
        assertTrue(admission.admitForApproval(parkedAttempt(request), exactApproval(request)) is AdmitResult.Admitted)
        // The admission is live: the executor's confirmSubmit gate now passes.
        val confirmed = session.confirmSubmit(page.pageToken)
        assertTrue(confirmed is SubmitConfirmation.Confirmed)
        assertEquals("https://example.com/session", (confirmed as SubmitConfirmation.Confirmed).destination)
        // One-shot: the admission is consumed by the confirm.
        assertTrue(session.confirmSubmit(page.pageToken) is SubmitConfirmation.NeedsApproval)
    }

    @Test fun consumedApprovalIsDenied() {
        val session = testSession()
        val page = openLogin(session)
        val request = submitRequest(page.pageToken)
        val admission = BrowserApprovalAdmission(session)
        val verdict = admission.admitForApproval(parkedAttempt(request),
            exactApproval(request).copy(consumed = true))
        assertTrue(verdict is AdmitResult.Denied)
        assertEquals("That approval was already used.", (verdict as AdmitResult.Denied).reason)
    }

    @Test fun changedActionIsDenied() {
        val session = testSession()
        val page = openLogin(session)
        val request = submitRequest(page.pageToken)
        val admission = BrowserApprovalAdmission(session)
        // The approval names a different page token than the parked task.
        val changed = exactApproval(submitRequest("fedcba9876543210"))
        val verdict = admission.admitForApproval(parkedAttempt(request), changed)
        assertTrue(verdict is AdmitResult.Denied)
        assertTrue((verdict as AdmitResult.Denied).reason.contains("changed action"))
    }

    @Test fun wrongTaskOrStepIsDenied() {
        val session = testSession()
        val page = openLogin(session)
        val request = submitRequest(page.pageToken)
        val admission = BrowserApprovalAdmission(session)
        val wrongTask = admission.admitForApproval(parkedAttempt(request),
            exactApproval(request).copy(taskId = "group-2"))
        assertTrue(wrongTask is AdmitResult.Denied)
        assertTrue((wrongTask as AdmitResult.Denied).reason.contains("different task"))
        val wrongStep = admission.admitForApproval(parkedAttempt(request),
            exactApproval(request).copy(stepId = "step-2"))
        assertTrue(wrongStep is AdmitResult.Denied)
        assertTrue((wrongStep as AdmitResult.Denied).reason.contains("different task"))
    }

    @Test fun missingTokenIsDenied() {
        val session = testSession()
        openLogin(session)
        val request = ActionRequest("browse_submit")
        val admission = BrowserApprovalAdmission(session)
        val verdict = admission.admitForApproval(parkedAttempt(request), exactApproval(request))
        assertTrue(verdict is AdmitResult.Denied)
        assertEquals("That approval has no page to submit.", (verdict as AdmitResult.Denied).reason)
    }

    @Test fun stalePageIsDenied() {
        val session = testSession()
        val page = openLogin(session)
        val request = submitRequest(page.pageToken)
        val admission = BrowserApprovalAdmission(session)
        // Same URL, replaced DOM: the token the user approved is dead.
        session.adoptSnapshot(
            url = page.url, title = "Example account", textExcerpt = "Welcome",
            links = emptyList(), forms = emptyList(), fingerprint = "fp-after-login"
        )
        val verdict = admission.admitForApproval(parkedAttempt(request), exactApproval(request))
        assertTrue(verdict is AdmitResult.Denied)
        assertTrue((verdict as AdmitResult.Denied).reason.contains("That page changed"))
    }
}
