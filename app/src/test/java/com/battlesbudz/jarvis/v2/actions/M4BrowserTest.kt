package com.battlesbudz.jarvis.v2.actions

import com.battlesbudz.jarvis.v2.ai.ToolCall
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * M4 browser tasks: navigation policy, session model, submission gate,
 * takeover handling, credential-handoff fixture, catalog/validator/decoder
 * parity, and text-parser routing. Deterministic; no network, no Android.
 */
class M4BrowserTest {
    private val validator = MobileActionValidator()
    private val tokenA = "0123456789abcdef"
    private val tokenB = "fedcba9876543210"

    private var tokenCounter = 0
    private fun testSession() = BrowserSession(
        clock = { 1_700_000_000_000L },
        newToken = { "t${tokenCounter++}".padEnd(16, '0') }
    )

    private fun loginPage(url: String = "https://example.com/login"): BrowserPageSnapshot =
        BrowserPageSnapshot(
            url = url,
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
            ),
            contentFingerprint = "fp-login"
        )

    private fun openSnapshot(session: BrowserSession, snapshot: BrowserPageSnapshot): BrowserPage =
        session.openPage(
            url = snapshot.url,
            title = snapshot.title,
            textExcerpt = snapshot.textExcerpt,
            links = snapshot.links,
            forms = snapshot.forms
        ).also { session.noteFingerprint(snapshot.contentFingerprint) }

    private fun strict(name: String, args: Map<String, String>) =
        MobileToolCatalog.decodeStrict(name, JSONObject(args as Map<*, *>))

    // ---- Navigation policy ----

    @Test fun normalizesBareDomainsToHttps() {
        assertEquals("https://example.com", BrowserNavigationPolicy.normalizeUrl("example.com"))
        assertEquals("https://example.com/a", BrowserNavigationPolicy.normalizeUrl("https://example.com/a"))
    }

    @Test fun rejectsDangerousSchemesAndMalformedHosts() {
        for (url in listOf("", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi",
            "intent://x", "not a url", ".com", "com", "http://no-dot")) {
            assertNull("'$url' must be rejected", BrowserNavigationPolicy.normalizeUrl(url))
        }
    }

    @Test fun httpAllowedOnlyForLoopback() {
        assertEquals("http://localhost:8080/a", BrowserNavigationPolicy.normalizeUrl("http://localhost:8080/a"))
        assertEquals("http://127.0.0.1/", BrowserNavigationPolicy.normalizeUrl("http://127.0.0.1/"))
        assertNull(BrowserNavigationPolicy.normalizeUrl("http://example.com/"))
    }

    @Test fun checkSubmissionFlagsCrossHostPosts() {
        val same = BrowserNavigationPolicy.checkSubmission(
            "https://example.com/login", "https://example.com/session")
        assertTrue(same is SubmissionDestination.SameHost)
        assertEquals("https://example.com/session", (same as SubmissionDestination.SameHost).url)

        val cross = BrowserNavigationPolicy.checkSubmission(
            "https://example.com/login", "https://tracker.example.net/collect")
        assertTrue(cross is SubmissionDestination.CrossHost)
        cross as SubmissionDestination.CrossHost
        assertEquals("example.com", cross.pageHost)
        assertEquals("tracker.example.net", cross.destHost)

        assertTrue(BrowserNavigationPolicy.checkSubmission("not a url", "https://example.com/")
            is SubmissionDestination.Invalid)
    }

    // ---- Session: navigation and tokens ----

    @Test fun openAssignsTokenAndProvenance() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        assertEquals(16, page.pageToken.length)
        assertEquals("https://example.com/login", page.provenance.sourceUrl)
        assertTrue(page.provenance.cite().contains("example.com"))
        assertTrue(page.provenance.cite().contains("https://example.com/login"))
    }

    @Test fun historyRotatesTokensOnBackAndForward() {
        val session = testSession()
        val first = openSnapshot(session, loginPage("https://example.com/a"))
        openSnapshot(session, loginPage("https://example.com/b"))
        assertTrue(session.canGoBack())
        val back = session.goBack()!!
        assertEquals("https://example.com/a", back.url)
        assertNotEquals(first.pageToken, back.pageToken)
        assertTrue(session.canGoForward())
        val forward = session.goForward()!!
        assertEquals("https://example.com/b", forward.url)
        // The old token no longer dispatches.
        assertEquals(FillOutcome.StaleToken, session.fillField("f0", "x", first.pageToken))
    }

    @Test fun backAtHistoryStartReturnsNull() {
        val session = testSession()
        openSnapshot(session, loginPage())
        assertFalse(session.canGoBack())
        assertFalse(session.canGoForward())
        assertNull(session.goBack())
        assertNull(session.goForward())
    }

    @Test fun compactTextCarriesIdsTokenAndCitation() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        val text = page.compactText()
        assertTrue(text.contains("[l0]"))
        assertTrue(text.contains("(f0)"))
        assertTrue(text.contains("(f1)"))
        assertTrue(text.contains("page_token: ${page.pageToken}"))
        assertTrue(text.contains("Source: example.com"))
    }

    // ---- Session: filling ----

    @Test fun fillFieldRecordsAndMasksSecrets() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        assertEquals(FillOutcome.Filled("f0"), session.fillField("f0", "user@example.com", page.pageToken))
        assertEquals(FillOutcome.Filled("f1"), session.fillField("f1", "s3cret!", page.pageToken))
        assertEquals(setOf("f0", "f1"), session.filledFieldIds())
        val summary = session.fillSummary()
        assertTrue(summary.any { it.contains("user@example.com") })
        // The secret value never appears in the summary.
        assertTrue(summary.none { it.contains("s3cret") })
        assertTrue(summary.any { it.contains("••••") })
    }

    @Test fun fillRejectsUnknownFieldStaleTokenAndNoPage() {
        val session = testSession()
        assertEquals(FillOutcome.NoPage, session.fillField("f0", "x", tokenA))
        val page = openSnapshot(session, loginPage())
        assertEquals(FillOutcome.NoSuchField, session.fillField("f9", "x", page.pageToken))
        assertEquals(FillOutcome.StaleToken, session.fillField("f0", "x", tokenB))
    }

    // ---- Session: link clicks ----

    @Test fun clickLinkNavigatesWithNormalizedUrl() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        val outcome = session.clickLink("l0", page.pageToken)
        assertTrue(outcome is ClickOutcome.Navigating)
        assertEquals("https://example.com/", (outcome as ClickOutcome.Navigating).url)
    }

    @Test fun clickLinkRejectsBadTargets() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        assertEquals(ClickOutcome.NoSuchLink, session.clickLink("l7", page.pageToken))
        assertEquals(ClickOutcome.StaleToken, session.clickLink("l0", tokenB))
        assertEquals(ClickOutcome.NoPage, testSession().clickLink("l0", tokenA))
    }

    @Test fun clickLinkRejectsDangerousUrls() {
        val session = testSession()
        val evil = loginPage().copy(
            links = listOf(BrowserLink("l0", "evil", "javascript:alert(1)")))
        val page = openSnapshot(session, evil)
        val outcome = session.clickLink("l0", page.pageToken)
        assertTrue(outcome is ClickOutcome.RejectedUrl)
    }

    // ---- Session: the D11 submission gate ----

    @Test fun submitWithoutAdmissionNeedsApproval() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        val confirmation = session.confirmSubmit(page.pageToken)
        assertTrue(confirmation is SubmitConfirmation.NeedsApproval)
        val proposal = (confirmation as SubmitConfirmation.NeedsApproval).proposal
        assertEquals("https://example.com/session", proposal.destination)
        assertFalse(proposal.crossHost)
        assertEquals(listOf("Email", "Password"), proposal.fieldNames)
        assertEquals(1, proposal.filledCount)
    }

    @Test fun submitProposalFlagsCrossHostDestination() {
        val session = testSession()
        val cross = loginPage().copy(
            forms = listOf(loginPage().forms.first().copy(actionUrl = "https://tracker.example.net/collect")))
        val page = openSnapshot(session, cross)
        val proposal = session.proposeSubmit(page.pageToken)!!
        assertTrue(proposal.crossHost)
        assertTrue(proposal.destHostNote!!.contains("tracker.example.net"))
        assertTrue(proposal.destHostNote.contains("example.com"))
    }

    @Test fun submitWithAdmissionConfirmsAndConsumesIt() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        session.admitSubmit(session.proposeSubmit(page.pageToken)!!)
        val confirmed = session.confirmSubmit(page.pageToken)
        assertTrue(confirmed is SubmitConfirmation.Confirmed)
        assertEquals("https://example.com/session", (confirmed as SubmitConfirmation.Confirmed).destination)
        // One-shot: the next submit needs a fresh approval.
        assertTrue(session.confirmSubmit(page.pageToken) is SubmitConfirmation.NeedsApproval)
    }

    @Test fun changedFillsInvalidateAdmission() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        session.admitSubmit(session.proposeSubmit(page.pageToken)!!)
        session.fillField("f1", "s3cret!", page.pageToken)
        assertTrue(session.confirmSubmit(page.pageToken) is SubmitConfirmation.NeedsApproval)
    }

    @Test fun staleTokenOrMissingFormCannotSubmit() {
        val session = testSession()
        assertEquals(SubmitConfirmation.NoPage, session.confirmSubmit(tokenA))
        val page = openSnapshot(session, loginPage())
        assertEquals(SubmitConfirmation.StaleToken, session.confirmSubmit(tokenB))
        val noFormSession = testSession()
        val noFormPage = openSnapshot(noFormSession, loginPage().copy(forms = emptyList()))
        assertEquals(SubmitConfirmation.NoForm, noFormSession.confirmSubmit(noFormPage.pageToken))
        assertNull(session.proposeSubmit(tokenB))
    }

    // ---- Session: manual takeover ----

    @Test fun takeoverPausesAutomationUntilResume() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.pauseForTakeover()
        assertTrue(session.isPausedForTakeover())
        assertEquals(FillOutcome.Paused, session.fillField("f0", "x", page.pageToken))
        assertEquals(ClickOutcome.Paused, session.clickLink("l0", page.pageToken))
        assertEquals(SubmitConfirmation.Paused, session.confirmSubmit(page.pageToken))
        assertNull(session.proposeSubmit(page.pageToken))
    }

    @Test fun resumeOnUnchangedPageKeepsWorking() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.pauseForTakeover()
        assertEquals(TakeoverResume.Unchanged,
            session.resumeTakeover("https://example.com/login", "fp-login"))
        assertFalse(session.isPausedForTakeover())
        assertEquals(FillOutcome.Filled("f0"), session.fillField("f0", "x", page.pageToken))
    }

    @Test fun resumeOnChangedUrlRotatesTokenAndDropsFills() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        session.pauseForTakeover()
        assertEquals(TakeoverResume.Changed,
            session.resumeTakeover("https://example.com/other", "fp-other"))
        val fresh = session.currentPage()!!
        assertNotEquals(page.pageToken, fresh.pageToken)
        assertTrue(session.filledFieldIds().isEmpty())
        assertEquals(FillOutcome.StaleToken, session.fillField("f0", "x", page.pageToken))
    }

    @Test fun resumeOnChangedFingerprintDetectsSilentChange() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.pauseForTakeover()
        // Same URL, but the DOM changed under the user (e.g. a login completed).
        assertEquals(TakeoverResume.Changed,
            session.resumeTakeover("https://example.com/login", "fp-after-login"))
        assertNotEquals(page.pageToken, session.currentPage()!!.pageToken)
    }

    @Test fun resumeWithNoPageIsNoPage() {
        assertEquals(TakeoverResume.NoPage, testSession().resumeTakeover("https://example.com/", "fp"))
    }

    // ---- Credential handoff policy ----

    @Test fun credentialHandoffNeedsPageAndLoginForm() {
        val noPage = BrowserCredentialPolicy.decide(null, false)
        assertTrue(noPage is CredentialHandoffDecision.Rejected)

        val noForm = BrowserCredentialPolicy.decide("example.com", false)
        assertTrue(noForm is CredentialHandoffDecision.Rejected)
        assertTrue((noForm as CredentialHandoffDecision.Rejected).reason.contains("no login form"))

        val allowed = BrowserCredentialPolicy.decide("example.com", true)
        assertTrue(allowed is CredentialHandoffDecision.Allowed)
        assertEquals("example.com", (allowed as CredentialHandoffDecision.Allowed).host)
    }

    // ---- Catalog ----

    @Test fun catalogDeclaresAllNineBrowserTools() {
        // The nine browse tools are declared (find/decode keep resolving
        // them for storage) but withheld from the model-visible catalog
        // while the browser runtime is unwired.
        val gate = MobileToolCatalog.BrowserRuntimeGate
        gate.wired = false
        try {
            val tools = listOf("browse_open", "browse_read", "browse_click", "browse_back",
                "browse_forward", "browse_fill", "browse_submit", "browse_handoff", "browse_login")
            tools.forEach { assertNotNull("not declared: $it", MobileToolCatalog.find(it)) }
            val names = MobileToolCatalog.all().map { it.name }
            tools.forEach { assertFalse("withheld while unwired: $it", names.contains(it)) }
            gate.wired = true
            val wiredNames = MobileToolCatalog.all().map { it.name }
            tools.forEach { assertTrue("missing $it", wiredNames.contains(it)) }
        } finally {
            gate.wired = false
        }
    }

    @Test fun strictDecodeRejectsWrongTypesAndExtraKeys() {
        val good = mapOf("target" to "l3", "token" to tokenA)
        assertNotNull(strict("browse_click", good))
        // Extra keys are rejected.
        assertNull(strict("browse_click", good + ("extra" to "x")))
        // Link IDs are strict: n3 is a screen target, not a browser link.
        assertNull(strict("browse_click", mapOf("target" to "n3", "token" to tokenA)))
        assertNull(strict("browse_fill", mapOf("field" to "f2", "text" to "x", "token" to "short")))
        assertNull(strict("browse_submit", mapOf("token" to "not-a-token")))
        // Paramless tools take no arguments.
        assertNotNull(strict("browse_read", emptyMap()))
        assertNull(strict("browse_read", mapOf("x" to "y")))
    }

    // ---- Validator ----

    @Test fun validatesBrowseOpenAndNormalizes() {
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseOpen("https://example.com")),
            validator.validate(ActionRequest("browse_open", mapOf("url" to "example.com")))
        )
        for (url in listOf("", "javascript:alert(1)", "file:///x", "not a url")) {
            val result = validator.validate(ActionRequest("browse_open", mapOf("url" to url)))
            assertTrue("'$url' must be rejected", result is ActionValidation.Rejected)
        }
    }

    @Test fun validatesBrowseClickFillSubmitLogin() {
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseClick("l12", tokenA)),
            validator.validate(ActionRequest("browse_click", mapOf("target" to "l12", "token" to tokenA)))
        )
        assertTrue(validator.validate(
            ActionRequest("browse_click", mapOf("target" to "n1", "token" to tokenA)))
            is ActionValidation.Rejected)
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseFill("f2", "hello", tokenA)),
            validator.validate(ActionRequest("browse_fill",
                mapOf("field" to "f2", "text" to "hello", "token" to tokenA)))
        )
        assertTrue(validator.validate(ActionRequest("browse_fill",
            mapOf("field" to "f2", "text" to "x".repeat(501), "token" to tokenA)))
            is ActionValidation.Rejected)
        assertTrue(validator.validate(ActionRequest("browse_fill",
            mapOf("field" to "f2", "text" to "", "token" to tokenA)))
            is ActionValidation.Rejected)
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseSubmit(tokenA)),
            validator.validate(ActionRequest("browse_submit", mapOf("token" to tokenA)))
        )
        assertTrue(validator.validate(ActionRequest("browse_submit", mapOf("token" to "bad")))
            is ActionValidation.Rejected)
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseLogin(tokenA)),
            validator.validate(ActionRequest("browse_login", mapOf("token" to tokenA)))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseRead),
            validator.validate(ActionRequest("browse_read"))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseBack),
            validator.validate(ActionRequest("browse_back"))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseForward),
            validator.validate(ActionRequest("browse_forward"))
        )
        assertEquals(
            ActionValidation.Valid(MobileAction.BrowseHandoff),
            validator.validate(ActionRequest("browse_handoff"))
        )
    }

    // ---- Tolerant decoder ----

    private fun decode(name: String, argsJson: String) =
        NativeActionDecoder.decode(ToolCall(name = name, arguments = argsJson))

    @Test fun decoderMapsBrowserToolsTolerantly() {
        assertEquals(
            ActionRequest("browse_open", mapOf("url" to "example.com")),
            decode("browse_open", """{"url":"example.com"}""")
        )
        // Model-style nested args object is unwrapped.
        assertEquals(
            ActionRequest("browse_fill", mapOf("field" to "f1", "text" to "x", "token" to tokenA)),
            decode("browse_fill", """{"args":{"field":"f1","text":"x","token":"$tokenA"}}""")
        )
        assertEquals(ActionRequest("browse_read"), decode("browse_read", "{}"))
        assertEquals(ActionRequest("browse_back"), decode("browse_back", "{}"))
        assertEquals(ActionRequest("browse_forward"), decode("browse_forward", "{}"))
        assertEquals(ActionRequest("browse_handoff"), decode("browse_handoff", "{}"))
        assertEquals(
            ActionRequest("browse_submit", mapOf("token" to tokenA)),
            decode("browse_submit", """{"token":"$tokenA"}""")
        )
        assertEquals(
            ActionRequest("browse_login", mapOf("token" to tokenA)),
            decode("browse_login", """{"token":"$tokenA"}""")
        )
        assertEquals(
            ActionRequest("browse_click", mapOf("target" to "l3", "token" to tokenA)),
            decode("browse_click", """{"target":"l3","token":"$tokenA"}""")
        )
    }

    // ---- Text parser and turn plan ----

    @Test fun browseTargetMatchesInternalBrowserPhrasing() {
        assertEquals("example.com", ActionRequestText.browseTarget("browse to example.com"))
        assertEquals("https://example.com/a", ActionRequestText.browseTarget("look up https://example.com/a"))
        assertNull(ActionRequestText.browseTarget("open example.com"))
        assertNull(ActionRequestText.browseTarget("look up the weather"))
    }

    @Test fun browseReadRequestMatchesPageReadingPhrasing() {
        assertTrue(ActionRequestText.browseReadRequest("read this page"))
        assertTrue(ActionRequestText.browseReadRequest("what's on this page"))
        assertTrue(ActionRequestText.browseReadRequest("summarize this page"))
        assertFalse(ActionRequestText.browseReadRequest("read this book"))
        assertFalse(ActionRequestText.browseReadRequest("open example.com"))
    }

    @Test fun turnPlanRoutesBrowsePhrasing() {
        // Browse routing follows the wiring gate: unwired requests are
        // ordinary speech; wired requests plan browse steps.
        val gate = MobileToolCatalog.BrowserRuntimeGate
        gate.wired = false
        assertTrue(ActionTurnPlan.parse("browse to example.com") is ActionTurnPlan.NotAction)
        assertTrue(ActionTurnPlan.parse("read this page") is ActionTurnPlan.NotAction)
        gate.wired = true
        try {
            val open = ActionTurnPlan.parse("browse to example.com")
            assertTrue(open is ActionTurnPlan.Ready)
            val openStep = (open as ActionTurnPlan.Ready).steps.single()
            assertEquals("browse_open", openStep.request.name)
            assertEquals("example.com", openStep.request.arguments["url"])

            val read = ActionTurnPlan.parse("read this page")
            assertTrue(read is ActionTurnPlan.Ready)
            assertEquals("browse_read", (read as ActionTurnPlan.Ready).steps.single().request.name)
        } finally {
            gate.wired = false
        }

        // "open example.com" still hands off to the external browser app.
        val external = ActionTurnPlan.parse("open example.com")
        assertTrue(external is ActionTurnPlan.Ready)
        assertEquals("open_website", (external as ActionTurnPlan.Ready).steps.single().request.name)
    }

    // ---- Approval binding: the token dies with the DOM it was issued for ----

    @Test fun fingerprintForTracksNotedFingerprints() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        assertEquals("fp-login", session.fingerprintFor(page.pageToken))
        assertNull(session.fingerprintFor("deadbeefdeadbeef"))
    }

    @Test fun adoptSnapshotRotatesTokenWithoutTouchingHistory() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        val proposal = session.proposeSubmit(page.pageToken)!!
        session.admitSubmit(proposal)
        // Same URL, replaced DOM (e.g. the login completed via SPA nav).
        val fresh = session.adoptSnapshot(
            url = page.url, title = "Example account", textExcerpt = "Welcome",
            links = emptyList(), forms = emptyList(), fingerprint = "fp-after-login"
        )
        assertNotEquals(page.pageToken, fresh.pageToken)
        assertEquals("fp-after-login", session.fingerprintFor(fresh.pageToken))
        // The old token is dead: fills, approvals and submissions bound to
        // the old DOM are all rejected.
        assertEquals(FillOutcome.StaleToken, session.fillField("f0", "x", page.pageToken))
        assertEquals(SubmitConfirmation.StaleToken, session.confirmSubmit(page.pageToken))
        assertTrue(session.filledFieldIds().isEmpty())
        // History is untouched: the user did not navigate.
        assertFalse(session.canGoBack())
        assertNull(session.goBack())
    }

    @Test fun changedActionDestinationInvalidatesApproval() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        session.fillField("f1", "s3cr3t", page.pageToken)
        val proposal = session.proposeSubmit(page.pageToken)!!
        assertEquals("https://example.com/session", proposal.destination)
        session.admitSubmit(proposal)
        // The form now posts somewhere else (same URL, new DOM).
        val changed = loginPage().copy(
            forms = listOf(
                BrowserForm(
                    id = "form0",
                    actionUrl = "https://evil.example/collect",
                    method = "POST",
                    fields = listOf(
                        BrowserField("f0", "Email", FieldKind.EMAIL),
                        BrowserField("f1", "Password", FieldKind.PASSWORD, secret = true)
                    ),
                    submitLabel = "Sign in"
                )
            ),
            contentFingerprint = "fp-hijacked"
        )
        session.adoptSnapshot(
            url = changed.url, title = changed.title, textExcerpt = changed.textExcerpt,
            links = changed.links, forms = changed.forms, fingerprint = changed.contentFingerprint
        )
        // The stale approval cannot submit anywhere.
        assertEquals(SubmitConfirmation.StaleToken, session.confirmSubmit(page.pageToken))
    }

    @Test fun reorderedFormsInvalidateApproval() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        val proposal = session.proposeSubmit(page.pageToken)!!
        session.admitSubmit(proposal)
        // Same URL, same fields, forms in a different order (new DOM).
        val reordered = loginPage().copy(
            forms = listOf(
                BrowserForm(id = "form9", actionUrl = null, method = "GET",
                    fields = listOf(BrowserField("f9", "Search", FieldKind.TEXT)),
                    submitLabel = "Go"),
                loginPage().forms.single()
            ),
            contentFingerprint = "fp-reordered"
        )
        session.adoptSnapshot(
            url = reordered.url, title = reordered.title, textExcerpt = reordered.textExcerpt,
            links = reordered.links, forms = reordered.forms, fingerprint = reordered.contentFingerprint
        )
        assertEquals(SubmitConfirmation.StaleToken, session.confirmSubmit(page.pageToken))
    }

    @Test fun changedFieldValuesInvalidateAdmission() {
        val session = testSession()
        val page = openSnapshot(session, loginPage())
        session.fillField("f0", "user@example.com", page.pageToken)
        val proposal = session.proposeSubmit(page.pageToken)!!
        session.admitSubmit(proposal)
        // A fill changed after the approval: the admission dies with it.
        session.fillField("f0", "someone-else@example.com", page.pageToken)
        val confirmation = session.confirmSubmit(page.pageToken)
        assertTrue("changed fills must need a fresh approval, got $confirmation",
            confirmation is SubmitConfirmation.NeedsApproval)
    }
}
