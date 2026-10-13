package com.battlesbudz.jarvis.v2.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * M4 browser tasks: Android dispatch for the browse_* tools.
 *
 * Decorates any [MobileActionExecutor]: browse actions are handled here
 * against the [BrowserSession] and [BrowserBridge]; everything else
 * delegates untouched. Receipts stay honest: loads are reported as handed
 * off (never as verified reads), unavailability is reported instead of
 * claimed, and secret field values are never echoed.
 *
 * The D11 submission gate lives in [BrowserSession.confirmSubmit]: without
 * a live admission bound to the exact destination and field set,
 * browse_submit returns a needs-approval receipt naming the destination.
 * The approval UI calls [BrowserSession.admitSubmit]; that wiring is the
 * same seam as ScreenControlSession.admit (M1c/M1d).
 */
class AndroidBrowserExecutor(
    private val context: Context,
    private val delegate: MobileActionExecutor,
    private val session: BrowserSession = BrowserSession(),
    private val bridge: BrowserBridge = UnavailableBrowserBridge,
    private val onDiagnostic: (String) -> Unit = {},
) : MobileActionExecutor, SecretAwareExecutor {

    override fun execute(action: MobileAction): ExecutionResult = when (action) {
        is MobileAction.BrowseOpen -> browseOpen(action)
        is MobileAction.BrowseRead -> browseRead()
        is MobileAction.BrowseClick -> browseClick(action)
        is MobileAction.BrowseBack -> browseBack()
        is MobileAction.BrowseForward -> browseForward()
        is MobileAction.BrowseFill -> browseFill(action)
        is MobileAction.BrowseSubmit -> browseSubmit(action)
        is MobileAction.BrowseHandoff -> browseHandoff()
        is MobileAction.BrowseLogin -> browseLogin(action)
        else -> delegate.execute(action)
    }

    /** Reconcile a manual takeover before dispatch; non-null stops dispatch. */
    private fun reconcileTakeover(verb: String): ExecutionResult? {
        if (!session.isPausedForTakeover()) return null
        // The user was interacting: re-extract before comparing, so a DOM
        // the user changed is detected even without a page load.
        runCatching { bridge.refreshSnapshot() }
        return when (session.resumeTakeover(bridge.currentUrl(), bridge.snapshotFingerprint())) {
            TakeoverResume.Unchanged -> null
            TakeoverResume.NoPage -> null
            TakeoverResume.Changed -> {
                onDiagnostic("browse_$verb result=takeover_changed")
                reconcileSnapshot()
                ExecutionResult(
                    false,
                    "The page changed while you were using the browser, so I re-read it " +
                        "and dropped the old targets. Call browse_read for the fresh page before continuing."
                )
            }
        }
    }

    /** Pull the bridge's latest snapshot into the session when it is new. */
    private fun reconcileSnapshot(): BrowserPage? = reconcileSnapshot(bridge.snapshot())

    /**
     * Pull [snapshot] into the session when it is new. The mutating path
     * passes the freshly extracted snapshot; reads reconcile the cached one.
     */
    private fun reconcileSnapshot(snapshot: BrowserPageSnapshot?): BrowserPage? {
        snapshot ?: return session.currentPage()
        val current = session.currentPage()
        if (current != null && current.url == snapshot.url &&
            session.fingerprintFor(current.pageToken) == snapshot.contentFingerprint
        ) {
            // Same URL and same contents: the page the model saw is still live.
            return current
        }
        return if (current != null && current.url == snapshot.url) {
            // Same URL, changed contents (SPA DOM replacement, takeover):
            // never reuse the stale page. The token rotates so approvals and
            // fills bound to the old DOM die; history is untouched because
            // the user did not navigate.
            session.adoptSnapshot(
                url = snapshot.url,
                title = snapshot.title,
                textExcerpt = snapshot.textExcerpt,
                links = snapshot.links,
                forms = snapshot.forms,
                fingerprint = snapshot.contentFingerprint
            )
        } else {
            val page = session.openPage(
                url = snapshot.url,
                title = snapshot.title,
                textExcerpt = snapshot.textExcerpt,
                links = snapshot.links,
                forms = snapshot.forms
            )
            session.noteFingerprint(snapshot.contentFingerprint)
            page
        }
    }

    /**
     * Mutating dispatches reconcile against a freshly extracted snapshot:
     * the approval gate must see the DOM it is about to act on.
     *
     * Fails closed: when the backend cannot re-extract (missing activity,
     * timeout), this returns null and the dispatch refuses — acting on the
     * cached snapshot would let a stale approval hit a replaced document.
     * The dispatch-time token check then validates the approved target
     * identity against the freshly reconciled page, atomically with the
     * dispatch decision: a document that no longer matches refuses.
     */
    private fun freshPage(): BrowserPage? {
        val fresh = runCatching { bridge.refreshSnapshot() }.getOrNull() ?: return null
        return reconcileSnapshot(fresh)
    }

    /** The backend could not re-read the live page: refuse the dispatch. */
    private fun refreshFailed(verb: String): ExecutionResult {
        onDiagnostic("browse_$verb result=refresh_failed")
        return ExecutionResult(
            false,
            "The browser couldn't re-read the live page, so nothing was done. " +
                "Call browse_read for the fresh page before continuing."
        )
    }

    /**
     * Pre-journal credential boundary: a browse_fill whose target field is
     * secret (or not yet known to the session) names its text argument, so
     * the pipeline journals the redacted copy and the secret never lands
     * on disk.
     *
     * The classification binds to the request's page token — the snapshot
     * the request was written against. Field ids are recycled across
     * documents, so a stale request (its token no longer matches the cached
     * page) must redact even when the NEW document's same-id field is
     * non-secret. Dispatch rejects the stale token afterwards, but the
     * journal write happens first — so fail closed here.
     */
    override fun secretArgumentKeys(request: ActionRequest): Set<String> {
        if (request.name != "browse_fill") return emptySet()
        val fieldId = request.arguments["field"] ?: return setOf("text")
        val page = session.currentPage() ?: return setOf("text")
        if (request.arguments["token"] != page.pageToken) return setOf("text")
        val field = page.findField(fieldId)
        // Redact unless the session positively knows the field is not secret.
        return if (field?.secret != false) setOf("text") else emptySet()
    }

    private fun bridgeUnavailable(verb: String): ExecutionResult {
        onDiagnostic("browse_$verb result=unavailable")
        return ExecutionResult(
            false,
            "The internal browser isn't available right now, so I couldn't $verb."
        )
    }

    private fun browseOpen(action: MobileAction.BrowseOpen): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("open")
        reconcileTakeover("open")?.let { return it }
        if (!bridge.open(action.url)) {
            onDiagnostic("browse_open result=rejected url=${action.url.take(80)}")
            return ExecutionResult(false, "I couldn't open ${action.url}.")
        }
        // The load is handed off; the snapshot arrives asynchronously and the
        // next browse_read reconciles it. Report the handoff, never a read.
        val host = BrowserNavigationPolicy.hostOf(action.url) ?: action.url
        onDiagnostic("browse_open result=submitted host=$host")
        return ExecutionResult(true, "Opening $host in the internal browser. Call browse_read for the page.")
    }

    private fun browseRead(): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("read")
        reconcileTakeover("read")?.let { return it }
        val page = reconcileSnapshot()
            ?: return ExecutionResult(false, "No page is open in the internal browser yet.")
        onDiagnostic("browse_read result=page url=${page.url.take(80)} links=${page.links.size} forms=${page.forms.size}")
        return ExecutionResult(true, page.compactText())
    }

    private fun browseClick(action: MobileAction.BrowseClick): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("click")
        reconcileTakeover("click")?.let { return it }
        // The click dispatches against the live DOM, not the last read.
        // A refresh that cannot re-extract refuses: never click on stale data.
        freshPage() ?: return refreshFailed("click")
        return when (val outcome = session.clickLink(action.linkId, action.token)) {
            is ClickOutcome.Navigating -> {
                if (!bridge.clickLink(action.linkId)) {
                    onDiagnostic("browse_click result=bridge_rejected")
                    ExecutionResult(false, "The browser couldn't follow that link.")
                } else {
                    onDiagnostic("browse_click result=submitted url=${outcome.url.take(80)}")
                    val host = BrowserNavigationPolicy.hostOf(outcome.url) ?: outcome.url
                    ExecutionResult(
                        true,
                        "Following the link to $host. Call browse_read for the new page."
                    )
                }
            }
            ClickOutcome.StaleToken ->
                ExecutionResult(false, "That page changed, so the link target expired. Call browse_read for fresh targets.")
            ClickOutcome.NoSuchLink ->
                ExecutionResult(false, "There's no link ${action.linkId} on the current page. Call browse_read for fresh targets.")
            ClickOutcome.NoPage ->
                ExecutionResult(false, "No page is open in the internal browser yet.")
            ClickOutcome.Paused ->
                ExecutionResult(false, "Paused while you're using the browser.")
            is ClickOutcome.RejectedUrl ->
                ExecutionResult(ExecutionResult.Outcome.REJECTED_VALIDATION, outcome.reason)
        }
    }

    private fun browseBack(): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("back")
        reconcileTakeover("back")?.let { return it }
        val page = session.goBack()
            ?: return ExecutionResult(false, "There's no earlier page in the browser history.")
        bridge.open(page.url)
        onDiagnostic("browse_back result=page url=${page.url.take(80)}")
        return ExecutionResult(true, "Went back to ${page.title.ifBlank { page.url }}.")
    }

    private fun browseForward(): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("forward")
        reconcileTakeover("forward")?.let { return it }
        val page = session.goForward()
            ?: return ExecutionResult(false, "There's no later page in the browser history.")
        bridge.open(page.url)
        onDiagnostic("browse_forward result=page url=${page.url.take(80)}")
        return ExecutionResult(true, "Went forward to ${page.title.ifBlank { page.url }}.")
    }

    private fun browseFill(action: MobileAction.BrowseFill): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("fill")
        reconcileTakeover("fill")?.let { return it }
        // The fill dispatches against the live DOM, not the last read.
        // A refresh that cannot re-extract refuses: never fill on stale data.
        freshPage() ?: return refreshFailed("fill")
        return when (val outcome = session.fillField(action.fieldId, action.text, action.token)) {
            is FillOutcome.Filled -> {
                if (!bridge.fillField(action.fieldId, action.text)) {
                    onDiagnostic("browse_fill result=bridge_rejected")
                    ExecutionResult(false, "The browser couldn't fill that field.")
                } else {
                    val page = session.currentPage()
                    val field = page?.findField(action.fieldId)
                    val label = if (field?.secret == true) "the password field"
                    else "\"${field?.label?.ifBlank { action.fieldId } ?: action.fieldId}\""
                    // Filling alone submits nothing; secret values are never echoed.
                    onDiagnostic("browse_fill result=filled field=${action.fieldId}")
                    ExecutionResult(true, "Filled $label. Nothing was submitted.")
                }
            }
            FillOutcome.StaleToken ->
                ExecutionResult(false, "That page changed, so the field target expired. Call browse_read for fresh targets.")
            FillOutcome.NoSuchField ->
                ExecutionResult(false, "There's no field ${action.fieldId} on the current page. Call browse_read for fresh targets.")
            FillOutcome.NoPage ->
                ExecutionResult(false, "No page is open in the internal browser yet.")
            FillOutcome.Paused ->
                ExecutionResult(false, "Paused while you're using the browser.")
        }
    }

    private fun browseSubmit(action: MobileAction.BrowseSubmit): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("submit")
        reconcileTakeover("submit")?.let { return it }
        // The approval is verified against the live DOM, not the last read:
        // a same-URL replacement between approval and dispatch rotates the
        // token, so a stale approval can never submit. A refresh that cannot
        // re-extract refuses outright.
        freshPage() ?: return refreshFailed("submit")
        return when (val confirmation = session.confirmSubmit(action.token)) {
            is SubmitConfirmation.Confirmed -> {
                // Bind the approved target to the mutation dispatch: the
                // approved form's id and the document fingerprint the
                // approval saw. The backend validates both in-page before
                // touching the DOM; a replacement between the refresh and
                // the dispatch refuses with zero submission.
                val page = session.currentPage()
                val formId = page?.forms?.firstOrNull()?.id
                val fingerprint = page?.let { session.fingerprintFor(it.pageToken) }
                if (formId == null || fingerprint == null) {
                    onDiagnostic("browse_submit result=unverifiable_target")
                    return ExecutionResult(
                        false,
                        "The browser couldn't verify the approved form, so nothing was submitted."
                    )
                }
                if (!bridge.submitForm(BrowserSubmitTarget(formId, fingerprint))) {
                    onDiagnostic("browse_submit result=bridge_rejected")
                    ExecutionResult(false, "The browser couldn't submit the form.")
                } else {
                    // The receipt names the destination and field summary;
                    // secret values are never echoed.
                    onDiagnostic("browse_submit result=submitted dest=${confirmation.destination.take(80)}")
                    ExecutionResult(true, "Submitted the form (${confirmation.summary}).")
                }
            }
            is SubmitConfirmation.NeedsApproval -> {
                val proposal = confirmation.proposal
                val dest = proposal.destHostNote
                    ?: BrowserNavigationPolicy.hostOf(proposal.destination).toString()
                onDiagnostic("browse_submit result=needs_approval dest=${proposal.destination.take(80)}")
                ExecutionResult(
                    false,
                    "Submitting this form sends ${proposal.fieldNames.joinToString()} " +
                        "to $dest. Approve the submission to continue; nothing was sent."
                )
            }
            SubmitConfirmation.StaleToken ->
                ExecutionResult(false, "That page changed, so the submission expired. Call browse_read for the fresh page.")
            SubmitConfirmation.NoForm ->
                ExecutionResult(false, "This page has no form to submit.")
            SubmitConfirmation.NoPage ->
                ExecutionResult(false, "No page is open in the internal browser yet.")
            SubmitConfirmation.Paused ->
                ExecutionResult(false, "Paused while you're using the browser.")
        }
    }

    private fun browseHandoff(): ExecutionResult {
        val url = session.currentPage()?.url ?: bridge.currentUrl()
            ?: return ExecutionResult(false, "No page is open to hand off.")
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val host = BrowserNavigationPolicy.hostOf(url) ?: url
            onDiagnostic("browse_handoff result=submitted host=$host")
            ExecutionResult(true, "Opening $host in your browser.")
        } catch (_: android.content.ActivityNotFoundException) {
            ExecutionResult(false, "No browser app is available to open the page.")
        }
    }

    private fun browseLogin(action: MobileAction.BrowseLogin): ExecutionResult {
        if (!bridge.isAvailable()) return bridgeUnavailable("login")
        reconcileTakeover("login")?.let { return it }
        // The login handoff targets the live DOM, not the last read.
        // A refresh that cannot re-extract refuses: never hand off stale data.
        freshPage() ?: return refreshFailed("login")
        val page = session.currentPage()
            ?: return ExecutionResult(false, "No page is open in the internal browser yet.")
        if (page.pageToken != action.token) {
            return ExecutionResult(
                false,
                "That page changed, so the login target expired. Call browse_read for the fresh page."
            )
        }
        val host = BrowserNavigationPolicy.hostOf(page.url)
        return when (val decision = BrowserCredentialPolicy.decide(host, page.hasLoginForm())) {
            is CredentialHandoffDecision.Rejected -> ExecutionResult(false, decision.reason)
            is CredentialHandoffDecision.Allowed -> when (bridge.requestCredentialFill(decision.host)) {
                CredentialFillOutcome.FILLED -> {
                    onDiagnostic("browse_login result=filled host=${decision.host}")
                    ExecutionResult(true, "Filled the login for ${decision.host}.")
                }
                CredentialFillOutcome.NO_CREDENTIALS -> ExecutionResult(
                    // Neutral: the backend cannot tell "no login saved" from
                    // "the user dismissed the prompt" — both leave the field
                    // empty, so neither may be claimed.
                    false, "The password manager didn't fill a login for ${decision.host}."
                )
                CredentialFillOutcome.CANCELLED -> ExecutionResult(
                    false, "The password-manager fill was cancelled; nothing was filled."
                )
                CredentialFillOutcome.UNAVAILABLE -> ExecutionResult(
                    false, "The password manager isn't available right now."
                )
            }
        }
    }

    companion object {
        /** Honest unavailable backend: every call reports it instead of claiming effects. */
        val UnavailableBrowserBridge: BrowserBridge = object : BrowserBridge {
            override fun isAvailable(): Boolean = false
            override fun open(url: String): Boolean = false
            override fun snapshot(): BrowserPageSnapshot? = null
            override fun snapshotFingerprint(): String? = null
            override fun clickLink(id: String): Boolean = false
            override fun goBack(): Boolean = false
            override fun goForward(): Boolean = false
            override fun fillField(id: String, text: String): Boolean = false
            override fun submitForm(target: BrowserSubmitTarget): Boolean = false
            override fun requestCredentialFill(host: String): CredentialFillOutcome =
                CredentialFillOutcome.UNAVAILABLE
            override fun currentUrl(): String? = null
        }
    }
}
