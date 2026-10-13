package com.battlesbudz.jarvis.v2.actions

import kotlin.collections.ArrayDeque
import java.util.UUID

/**
 * M4 browser tasks: the JVM-pure internal-browser session model.
 *
 * The session owns navigation history, rotating page tokens (stale pages can
 * never dispatch), form-fill state, the form-submission approval gate, source
 * provenance for cited answers, and manual-takeover pause/resume. The Android
 * WebView backend implements [BrowserBridge] and feeds page snapshots in;
 * all policy decisions here stay unit-testable with no Android dependency.
 *
 * Security invariants:
 * - Tokens rotate on every navigation; a token from an older page is stale
 *   and every mutating call fails closed on it.
 * - Filling a field never submits; submission requires a separate admitted
 *   proposal bound to the exact destination and field set (D11).
 * - Credential values never enter this model: the password-manager handoff
 *   carries only the host, and the platform fills without reporting back.
 * - Manual takeover pauses automation; resuming onto a changed page
 *   invalidates the token and clears fills.
 */

// ---- Page model ----

/** A clickable link or button on the page, addressable as l0, l1, ... */
data class BrowserLink(val id: String, val label: String, val url: String)

/** A form field, addressable as f0, f1, ... */
data class BrowserField(
    val id: String,
    val label: String,
    val kind: FieldKind,
    val secret: Boolean = false
)

enum class FieldKind { TEXT, PASSWORD, EMAIL, NUMBER, CHECKBOX, SELECT, TEXTAREA, SUBMIT, UNKNOWN }

data class BrowserForm(
    val id: String,
    val actionUrl: String?,
    val method: String,
    val fields: List<BrowserField>,
    val submitLabel: String?
)

/** Where a page snapshot came from; every read answer cites it. */
data class BrowserProvenance(val sourceUrl: String, val fetchedAtMs: Long) {
    /** Short human citation, e.g. "Source: example.com — https://example.com/a". */
    fun cite(): String {
        val host = BrowserNavigationPolicy.hostOf(sourceUrl) ?: sourceUrl
        return "Source: $host — $sourceUrl"
    }
}

data class BrowserPage(
    val url: String,
    val title: String,
    val textExcerpt: String,
    val links: List<BrowserLink>,
    val forms: List<BrowserForm>,
    val provenance: BrowserProvenance,
    val pageToken: String
) {
    /** Compact model-visible rendering with stable IDs and the page token. */
    fun compactText(): String = buildString {
        appendLine("Page: ${title.ifBlank { "(no title)" }}")
        appendLine("URL: $url")
        if (textExcerpt.isNotBlank()) appendLine("Text: ${textExcerpt.take(1200)}")
        links.take(40).forEach { appendLine("[${it.id}] ${it.label.ifBlank { it.url }}") }
        forms.forEach { form ->
            appendLine("Form ${form.id} (${form.method}, action ${form.actionUrl ?: "(same page)"}):")
            form.fields.forEach { field ->
                val kind = if (field.secret) "password" else field.kind.name.lowercase()
                appendLine("  (${field.id}) ${field.label.ifBlank { kind }} [$kind]")
            }
        }
        appendLine("page_token: $pageToken")
        appendLine(provenance.cite())
    }

    fun findLink(id: String): BrowserLink? = links.firstOrNull { it.id == id }
    fun findField(id: String): BrowserField? = forms.flatMap { it.fields }.firstOrNull { it.id == id }
    fun hasLoginForm(): Boolean =
        forms.any { form -> form.fields.any { it.secret } }
}

// ---- Navigation policy ----

/** Where a form submission would go, resolved against the page it came from. */
sealed interface SubmissionDestination {
    data class SameHost(val url: String) : SubmissionDestination
    data class CrossHost(val url: String, val pageHost: String, val destHost: String) : SubmissionDestination
    data object Invalid : SubmissionDestination
}

/**
 * URL/host policy for the internal browser. Mirrors the open_website rules:
 * https required (http only for loopback), dangerous schemes rejected, host
 * must be shaped like a real host.
 */
object BrowserNavigationPolicy {
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("data:") || lower.startsWith("intent:")
        ) return null
        val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed
        else "https://$trimmed"
        val scheme = withScheme.substringBefore("://").lowercase()
        val hostPort = withScheme.substringAfter("://").substringBefore("/").lowercase()
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore("]") + "]"
        else hostPort.substringBefore(":")
        if (host.startsWith(".") || host.endsWith(".")) return null
        val loopback = isLoopbackHost(host)
        if (!loopback && '.' !in host) return null
        if (scheme == "http" && !loopback) return null
        if (scheme != "http" && scheme != "https") return null
        return withScheme
    }

    fun hostOf(url: String): String? {
        val host = url.substringAfter("://", "").substringBefore("/").lowercase()
        return host.takeIf { it.isNotEmpty() && '.' in it }
    }

    fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]"

    /** Resolve a form action against the page URL; cross-host posts are flagged. */
    fun checkSubmission(pageUrl: String, actionUrl: String?): SubmissionDestination {
        val pageHost = hostOf(pageUrl) ?: return SubmissionDestination.Invalid
        val dest = actionUrl?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }
            ?: normalizeUrl(pageUrl) ?: return SubmissionDestination.Invalid
        val destHost = hostOf(dest) ?: return SubmissionDestination.Invalid
        return if (destHost == pageHost) SubmissionDestination.SameHost(dest)
        else SubmissionDestination.CrossHost(dest, pageHost, destHost)
    }
}

// ---- Credential handoff policy ----

/**
 * The password-manager handoff never carries credentials: the tool only names
 * the host, the platform fills, and only the outcome comes back. No secret
 * ever enters a prompt, log, receipt or memory.
 */
sealed interface CredentialHandoffDecision {
    data class Allowed(val host: String) : CredentialHandoffDecision
    data class Rejected(val reason: String) : CredentialHandoffDecision
}

object BrowserCredentialPolicy {
    fun decide(host: String?, hasLoginForm: Boolean): CredentialHandoffDecision {
        if (host.isNullOrBlank()) return CredentialHandoffDecision.Rejected(
            "No page is open, so there is nothing to fill credentials into.")
        if (!hasLoginForm) return CredentialHandoffDecision.Rejected(
            "The current page has no login form to fill.")
        return CredentialHandoffDecision.Allowed(host)
    }
}

// ---- Session ----

sealed interface FillOutcome {
    data class Filled(val fieldId: String) : FillOutcome
    data object StaleToken : FillOutcome
    data object NoSuchField : FillOutcome
    data object NoPage : FillOutcome
    data object Paused : FillOutcome
}

sealed interface ClickOutcome {
    data class Navigating(val url: String) : ClickOutcome
    data object StaleToken : ClickOutcome
    data object NoSuchLink : ClickOutcome
    data object NoPage : ClickOutcome
    data object Paused : ClickOutcome
    data class RejectedUrl(val reason: String) : ClickOutcome
}

/** The exact submission a browse_submit would perform; approval binds to this. */
data class SubmitProposal(
    val destination: String,
    val crossHost: Boolean,
    val destHostNote: String?,
    val fieldNames: List<String>,
    val filledCount: Int,
    val pageToken: String
)

sealed interface SubmitConfirmation {
    /** The admission matches the current page, token and fills: submit it. */
    data class Confirmed(val destination: String, val summary: String) : SubmitConfirmation
    /** No valid admission: the caller must ask the user first. */
    data class NeedsApproval(val proposal: SubmitProposal) : SubmitConfirmation
    data object StaleToken : SubmitConfirmation
    data object NoPage : SubmitConfirmation
    data object NoForm : SubmitConfirmation
    data object Paused : SubmitConfirmation
}

sealed interface TakeoverResume {
    data object Unchanged : TakeoverResume
    data object Changed : TakeoverResume
    data object NoPage : TakeoverResume
}

/**
 * One internal-browser session. Not thread-safe; the Android owner confines
 * it to a single thread like the rest of the action pipeline.
 */
class BrowserSession(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { UUID.randomUUID().toString().replace("-", "").take(16) }
) {
    private val backStack = ArrayDeque<BrowserPage>()
    private val forwardStack = ArrayDeque<BrowserPage>()
    private var current: BrowserPage? = null
    private val fills = mutableMapOf<String, String>()
    private var pausedForTakeover = false
    private var admission: SubmissionAdmission? = null

    private data class SubmissionAdmission(
        val destination: String,
        val fieldNames: Set<String>,
        val filledFieldIds: Set<String>,
        val pageToken: String
    )

    /** A bridge page snapshot becomes the current page; history and token rotate. */
    fun openPage(url: String, title: String, textExcerpt: String,
                 links: List<BrowserLink>, forms: List<BrowserForm>): BrowserPage {
        current?.let { backStack.addLast(it) }
        if (backStack.size > 50) backStack.removeFirst()
        forwardStack.clear()
        fills.clear()
        admission = null
        pausedForTakeover = false
        val page = BrowserPage(
            url = url, title = title, textExcerpt = textExcerpt,
            links = links, forms = forms,
            provenance = BrowserProvenance(url, clock()),
            pageToken = newToken()
        )
        current = page
        return page
    }

    fun currentPage(): BrowserPage? = current

    fun goBack(): BrowserPage? {
        val page = current ?: return null
        val prev = backStack.removeLastOrNull() ?: return null
        forwardStack.addLast(page)
        fills.clear()
        admission = null
        val tokened = prev.copy(pageToken = newToken())
        current = tokened
        return tokened
    }

    fun goForward(): BrowserPage? {
        val page = current ?: return null
        val next = forwardStack.removeLastOrNull() ?: return null
        backStack.addLast(page)
        fills.clear()
        admission = null
        val tokened = next.copy(pageToken = newToken())
        current = tokened
        return tokened
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()
    fun canGoForward(): Boolean = forwardStack.isNotEmpty()

    private fun checkToken(token: String): BrowserPage? {
        val page = current ?: return null
        return if (!pausedForTakeover && page.pageToken == token) page else null
    }

    fun fillField(fieldId: String, text: String, token: String): FillOutcome {
        if (pausedForTakeover) return FillOutcome.Paused
        val page = current ?: return FillOutcome.NoPage
        if (page.pageToken != token) return FillOutcome.StaleToken
        val field = page.findField(fieldId) ?: return FillOutcome.NoSuchField
        if (field.kind == FieldKind.SUBMIT) return FillOutcome.NoSuchField
        fills[fieldId] = text
        // Any fill change invalidates a prior submission admission: the
        // approval bound to the old content, and changed content needs a
        // fresh approval (same rule as screen-approval changed targets).
        admission = null
        return FillOutcome.Filled(fieldId)
    }

    fun filledFieldIds(): Set<String> = fills.keys.toSet()

    /** Field labels for the approval summary; secret values are never exposed. */
    fun fillSummary(): List<String> {
        val page = current ?: return emptyList()
        return fills.keys.mapNotNull { id ->
            page.findField(id)?.let { field ->
                if (field.secret) "${field.label.ifBlank { "password" }}: ••••••••"
                else "${field.label.ifBlank { field.id }}: ${fills.getValue(id).take(60)}"
            }
        }
    }

    fun clickLink(linkId: String, token: String): ClickOutcome {
        if (pausedForTakeover) return ClickOutcome.Paused
        val page = current ?: return ClickOutcome.NoPage
        if (page.pageToken != token) return ClickOutcome.StaleToken
        val link = page.findLink(linkId) ?: return ClickOutcome.NoSuchLink
        val normalized = BrowserNavigationPolicy.normalizeUrl(link.url)
            ?: return ClickOutcome.RejectedUrl("That link points somewhere the browser will not open.")
        return ClickOutcome.Navigating(normalized)
    }

    /**
     * Build the exact submission proposal for the current fills. The proposal
     * is what the user approves; it names the destination and fields but
     * never carries secret values.
     */
    fun proposeSubmit(token: String): SubmitProposal? {
        if (pausedForTakeover) return null
        val page = current ?: return null
        if (page.pageToken != token) return null
        val form = page.forms.firstOrNull() ?: return null
        val check = BrowserNavigationPolicy.checkSubmission(page.url, form.actionUrl)
        val destination = when (check) {
            is SubmissionDestination.SameHost -> check.url
            is SubmissionDestination.CrossHost -> check.url
            SubmissionDestination.Invalid -> return null
        }
        val crossHost = check is SubmissionDestination.CrossHost
        val destHostNote = (check as? SubmissionDestination.CrossHost)?.let {
            "posts to ${it.destHost} (this page is ${it.pageHost})"
        }
        return SubmitProposal(
            destination = destination,
            crossHost = crossHost,
            destHostNote = destHostNote,
            fieldNames = form.fields.map { it.label.ifBlank { it.id } },
            filledCount = fills.size,
            pageToken = page.pageToken
        )
    }

    /** Record the user's approval of exactly this proposal (approval-UI seam). */
    fun admitSubmit(proposal: SubmitProposal) {
        admission = SubmissionAdmission(
            destination = proposal.destination,
            fieldNames = proposal.fieldNames.toSet(),
            filledFieldIds = fills.keys.toSet(),
            pageToken = proposal.pageToken
        )
    }

    /**
     * D11 gate: confirm the submission only when a live admission matches the
     * current page, token, destination and fill set. The admission is
     * one-shot: it is consumed whether the submit proceeds or not.
     */
    fun confirmSubmit(token: String): SubmitConfirmation {
        if (pausedForTakeover) return SubmitConfirmation.Paused
        val page = current ?: return SubmitConfirmation.NoPage
        if (page.pageToken != token) return SubmitConfirmation.StaleToken
        if (page.forms.isEmpty()) return SubmitConfirmation.NoForm
        val proposal = proposeSubmit(token) ?: return SubmitConfirmation.StaleToken
        val live = admission
        admission = null
        if (live == null || live.destination != proposal.destination ||
            live.fieldNames != proposal.fieldNames.toSet() ||
            live.filledFieldIds != fills.keys.toSet() ||
            live.pageToken != proposal.pageToken
        ) {
            return SubmitConfirmation.NeedsApproval(proposal)
        }
        val summary = if (proposal.crossHost && proposal.destHostNote != null) {
            "${proposal.filledCount} filled field(s); ${proposal.destHostNote}"
        } else {
            "${proposal.filledCount} filled field(s) to ${BrowserNavigationPolicy.hostOf(proposal.destination)}"
        }
        return SubmitConfirmation.Confirmed(proposal.destination, summary)
    }

    /**
     * The user takes over the browser (login, 2FA, CAPTCHA): automation
     * pauses. On return, [resumeTakeover] compares the live page against the
     * session; a changed page rotates the token and drops fills so nothing
     * dispatches against a screen the model never saw.
     */
    fun pauseForTakeover() {
        pausedForTakeover = true
    }

    fun isPausedForTakeover(): Boolean = pausedForTakeover

    fun resumeTakeover(liveUrl: String?, contentFingerprint: String?): TakeoverResume {
        val page = current ?: return TakeoverResume.NoPage
        pausedForTakeover = false
        val normalizedLive = liveUrl?.let { BrowserNavigationPolicy.normalizeUrl(it) }
        if (normalizedLive == null || normalizedLive != page.url) {
            rotateAfterExternalChange()
            return TakeoverResume.Changed
        }
        // Fingerprint is supplied by the backend (cheap DOM hash); a mismatch
        // means the page changed under the user even though the URL matches.
        val liveFingerprint = contentFingerprint ?: return TakeoverResume.Unchanged
        val known = pageFingerprints[page.pageToken]
        if (known != null && known != liveFingerprint) {
            rotateAfterExternalChange()
            return TakeoverResume.Changed
        }
        return TakeoverResume.Unchanged
    }

    private val pageFingerprints = mutableMapOf<String, String>()

    /** The backend reports the content fingerprint alongside each snapshot. */
    fun noteFingerprint(fingerprint: String) {
        current?.let { pageFingerprints[it.pageToken] = fingerprint }
    }

    /** The noted content fingerprint for [token], or null when unknown. */
    fun fingerprintFor(token: String): String? = pageFingerprints[token]

    /**
     * Adopt a fresh snapshot for the current navigation entry: same URL, new
     * DOM (SPA replacement, takeover resume). The token rotates — approvals
     * and fills bound to the old DOM die — but history is untouched, because
     * the user did not navigate. Prefer this over [openPage] when the URL
     * did not change.
     */
    fun adoptSnapshot(url: String, title: String, textExcerpt: String,
                      links: List<BrowserLink>, forms: List<BrowserForm>,
                      fingerprint: String?): BrowserPage {
        fills.clear()
        admission = null
        pausedForTakeover = false
        val page = BrowserPage(
            url = url, title = title, textExcerpt = textExcerpt,
            links = links, forms = forms,
            provenance = BrowserProvenance(url, clock()),
            pageToken = newToken()
        )
        current = page
        if (fingerprint != null) pageFingerprints[page.pageToken] = fingerprint
        else pageFingerprints.remove(page.pageToken)
        return page
    }

    private fun rotateAfterExternalChange() {
        val page = current ?: return
        pageFingerprints.remove(page.pageToken)
        fills.clear()
        admission = null
        current = page.copy(pageToken = newToken())
    }

    fun close() {
        backStack.clear()
        forwardStack.clear()
        current = null
        fills.clear()
        admission = null
        pausedForTakeover = false
        pageFingerprints.clear()
    }
}
