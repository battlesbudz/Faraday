package com.battlesbudz.jarvis.v2.actions

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

/**
 * Credential-fill completion must be truthful: FILLED is reported only for
 * an actual empty-to-filled transition on the exact field the request
 * focused, with the approved host and the live document bound through the
 * whole flow. This runs the ACTUAL credential JavaScript
 * ([WebViewBrowserBridge.credentialFocusJs] /
 * [WebViewBrowserBridge.credentialEmptyJs] /
 * [WebViewBrowserBridge.credentialFilledJs]) together with the production
 * flow ([WebViewBrowserBridge.requestCredentialFill]) inside a minimal DOM
 * shim, with a scripted live host / document fingerprint / page state.
 *
 * Every test proves the same invariant: anything short of a real,
 * attributable platform fill is NO_CREDENTIALS — never FILLED. The browser
 * gate stays closed; this tests the completion contract, not the catalog.
 *
 * FILLED here is a neutral receipt — the bound field became nonempty during
 * the request — not proof the platform password manager performed the fill.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserCredentialFillJsTest {

    private lateinit var cx: Context
    private lateinit var scope: ScriptableObject
    private lateinit var bridge: WebViewBrowserBridge
    private var liveHost: String? = "example.com"
    private var liveFingerprint: String? = "fp-1"
    private var evalCalls = 0

    // Deterministic clock for the fill-wait loop: Robolectric's paused looper
    // freezes SystemClock.uptimeMillis while Thread.sleep does not advance the
    // simulated clock, so the production wait loop would poll forever on a
    // timeout-only path (dismissed prompt, replaced field). Advancing a fake
    // clock on every wait keeps the production timeout logic exact.
    private var fakeClockMs = 0L
    private val fakeClock: () -> Long = { fakeClockMs }
    private val fakeSleeper: (Long) -> Unit = { fakeClockMs += it }

    @Before
    fun setUp() {
        cx = Context.enter()
        cx.languageVersion = Context.VERSION_ES6
        // Interpreted mode: Rhino 1.8.0's optimizer hits a
        // jdk.dynalink MissingResourceException under this JDK/Robolectric
        // sandbox (ExceptionInInitializerError). Interpretation is plenty
        // for a small DOM shim.
        cx.optimizationLevel = -1
        scope = cx.initStandardObjects()
        cx.evaluateString(scope, DOM_SHIM, "dom-shim", 1, null)
        bridge = WebViewBrowserBridge(RuntimeEnvironment.getApplication())
        liveHost = "example.com"
        liveFingerprint = "fp-1"
        evalCalls = 0
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun evalJsBoolean(js: String): Boolean? {
        evalCalls++
        return cx.evaluateString(scope, js, "cred", 1, null) as? Boolean
    }

    private fun fill(host: String = "example.com", timeoutMs: Long = 1_500L): CredentialFillOutcome =
        bridge.requestCredentialFill(
            host = host,
            liveHostMatches = { requested -> liveHost == requested },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = timeoutMs,
            clock = fakeClock,
            sleeper = fakeSleeper
        )

    // -- JavaScript contract --------

    @Test fun focusJsStampsExactlyOneTarget() {
        assertEquals("nothing filled before focus", false,
            evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        assertEquals(true, evalJsBoolean(WebViewBrowserBridge.credentialFocusJs()))
        assertEquals("focus must land on the password field", true,
            evalJsBoolean("__pass.focused"))
        assertEquals("the stamp lands on the focused element", true,
            evalJsBoolean("__pass.getAttribute('data-jarvis-cred-target') === '1'"))
    }

    @Test fun filledJsRequiresStampAndValue() {
        // A value on an unstamped field is not attributable to any request.
        cx.evaluateString(scope, "__pass.value = 'preset';", "preset", 1, null)
        assertEquals("unstamped value is not a fill", false,
            evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        // Stamping alone is not a fill either.
        evalJsBoolean(WebViewBrowserBridge.credentialFocusJs())
        assertEquals(true, evalJsBoolean(WebViewBrowserBridge.credentialFilledJs()))
        assertEquals(false, evalJsBoolean(WebViewBrowserBridge.credentialEmptyJs()))
    }

    // -- Flow: honest completion --------

    @Test fun mismatchedHostRefusesWithoutTouchingThePage() {
        // The live page is a different host than the approved one: refuse
        // before any in-page evaluation runs.
        liveHost = "other.example"
        val outcome = fill(host = "example.com")
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertEquals("a host mismatch must not touch the page", 0, evalCalls)
    }

    @Test fun preexistingPasswordValueNeverReportsFilled() {
        // The field already held a value before the request: there is no
        // empty-to-filled transition, so FILLED must never be reported —
        // the pre-fix code returned FILLED for exactly this state.
        cx.evaluateString(scope, "__pass.value = 'already-there';", "preset", 1, null)
        val outcome = fill()
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must read the pre-state", evalCalls >= 2)
    }

    @Test fun dismissedPromptReportsNoCredentials() {
        // The password manager never fills (no saved login, or the user
        // dismissed the prompt): the field stays empty through the
        // deadline, so the outcome stays neutral — never FILLED.
        val outcome = fill(timeoutMs = 1_200L)
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
    }

    @Test fun actualEmptyToFilledTransitionReportsFilled() {
        // Happy path through the real JavaScript: the password manager
        // lands the fill after the pre-state read, and the stamped target
        // transitions empty -> filled.
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    cx.evaluateString(scope, "__pass.value = 'manager-filled';", "fill", 1, null)
                }
                r
            },
            waitTimeoutMs = 2_000L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals(CredentialFillOutcome.FILLED, outcome)
    }

    @Test fun navigationMidFlowAbortsTheFill() {
        // The user navigates away after the pre-state read: the host
        // re-check flips mid-flow and the request aborts instead of
        // attributing a fill to the approved host.
        var checks = 0
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { requested ->
                checks++
                if (checks > 2) false else liveHost == requested
            },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = 1_500L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must re-check the host mid-flow", checks > 2)
    }

    @Test fun documentReplacementMidFlowAbortsTheFill() {
        // The document is replaced mid-flow (same host, new fingerprint):
        // the document binding fails and the request aborts with zero
        // attribution.
        var fpReads = 0
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = {
                fpReads++
                if (fpReads > 2) "fp-replaced" else liveFingerprint
            },
            evalBoolean = ::evalJsBoolean,
            waitTimeoutMs = 1_500L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals(CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the flow must re-check the document mid-flow", fpReads > 2)
    }

    @Test fun replacedFieldTargetNeverAttributesAFill() {
        // Mid-flow the DOM swaps the focused field for a lookalike that
        // already carries a value — but the swap drops the focus stamp.
        // The stamped completion check must not attribute the lookalike's
        // value to this request.
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { liveHost == it },
            documentFingerprintNow = { liveFingerprint },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    cx.evaluateString(scope, "__replaceFieldWithValuedLookalike();", "swap", 1, null)
                }
                r
            },
            waitTimeoutMs = 1_500L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals("a value on an unstamped replacement field is not this request's fill",
            CredentialFillOutcome.NO_CREDENTIALS, outcome)
    }

    // -- Production live bindings (J3) --------

    @Test fun liveHostJsReadsTheActualPageNotACache() {
        // The production host binding evaluates location.hostname in the
        // actual page — never a cached snapshot URL. Simulating a
        // navigation (mutating only the live page, touching no cache) must
        // flip the check.
        val js = WebViewBrowserBridge.liveHostMatchesJs("example.com")
        assertEquals(true, cx.evaluateString(scope, js, "host", 1, null))
        cx.evaluateString(scope,
            "location.hostname = 'evil.example'; location.host = 'evil.example';", "nav", 1, null)
        assertEquals("a live navigation must fail the host binding",
            false, cx.evaluateString(scope, js, "host", 1, null))
    }

    @Test fun liveHostJsMatchesCaseInsensitively() {
        cx.evaluateString(scope, "location.hostname = 'Example.COM';", "case", 1, null)
        assertEquals(true, cx.evaluateString(scope,
            WebViewBrowserBridge.liveHostMatchesJs("example.com"), "host", 1, null))
    }

    @Test fun liveDocumentFingerprintRecomputesOverTheActualDom() {
        // The production document binding recomputes the fingerprint over
        // the live DOM with the extractor's exact algorithm — never the
        // cached snapshot. Replacing the DOM (touching no cache) must
        // rotate the fingerprint.
        val js = WebViewBrowserBridge.liveDocumentFingerprintJs()
        val before = cx.evaluateString(scope, js, "fp", 1, null) as String
        assertTrue("fingerprint must be a hash string", before.isNotEmpty())
        cx.evaluateString(scope,
            "document.documentElement.outerHTML = '<html><body>totally replaced</body></html>';",
            "dom", 1, null)
        val after = cx.evaluateString(scope, js, "fp", 1, null) as String
        assertNotEquals("a live DOM replacement must rotate the fingerprint", before, after)
    }

    @Test fun flowWithLiveBindingsAbortsOnActualNavigation() {
        // End-to-end through the production live-binding JS: the flow's
        // host lambda runs liveHostMatchesJs in the actual page, so a
        // navigation after focus aborts — the stale "example.com" approval
        // cannot leak onto the new page.
        var navigated = false
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { requested ->
                cx.evaluateString(scope, WebViewBrowserBridge.liveHostMatchesJs(requested),
                    "h", 1, null) as Boolean
            },
            documentFingerprintNow = {
                cx.evaluateString(scope, WebViewBrowserBridge.liveDocumentFingerprintJs(),
                    "fp", 1, null) as String
            },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (!navigated && js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    navigated = true
                    cx.evaluateString(scope,
                        "location.hostname = 'evil.example'; location.host = 'evil.example';",
                        "nav", 1, null)
                }
                r
            },
            waitTimeoutMs = 1_500L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals("a live navigation mid-flow must abort the fill",
            CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the navigation must have happened after focus", navigated)
    }

    @Test fun flowWithLiveBindingsAbortsOnActualDomReplacement() {
        // Same, for a DOM replacement: the live fingerprint rotates
        // mid-flow and the document binding fails.
        var replaced = false
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { requested ->
                cx.evaluateString(scope, WebViewBrowserBridge.liveHostMatchesJs(requested),
                    "h", 1, null) as Boolean
            },
            documentFingerprintNow = {
                cx.evaluateString(scope, WebViewBrowserBridge.liveDocumentFingerprintJs(),
                    "fp", 1, null) as String
            },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (!replaced && js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    replaced = true
                    cx.evaluateString(scope,
                        "document.documentElement.outerHTML = '<html><body>replaced</body></html>';",
                        "dom", 1, null)
                }
                r
            },
            waitTimeoutMs = 1_500L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals("a live DOM replacement mid-flow must abort the fill",
            CredentialFillOutcome.NO_CREDENTIALS, outcome)
        assertTrue("the replacement must have happened after focus", replaced)
    }

    @Test fun flowSurvivesItsOwnFocusStamp() {
        // The focus stamp (setAttribute data-jarvis-cred-target) changes the
        // live outerHTML — the DOM shim renders the actual elements, so the
        // fingerprint rotates on stamping exactly like a real page. The
        // document baseline must therefore be captured AFTER the stamp
        // lands: the pre-fix order captured it before focusing and rejected
        // the flow's own stamping as a document change (NO_CREDENTIALS on
        // the first re-check), breaking the happy path in production.
        val outcome = bridge.requestCredentialFill(
            host = "example.com",
            liveHostMatches = { requested ->
                cx.evaluateString(scope, WebViewBrowserBridge.liveHostMatchesJs(requested),
                    "h", 1, null) as Boolean
            },
            documentFingerprintNow = {
                cx.evaluateString(scope, WebViewBrowserBridge.liveDocumentFingerprintJs(),
                    "fp", 1, null) as String
            },
            evalBoolean = { js ->
                val r = evalJsBoolean(js)
                if (js == WebViewBrowserBridge.credentialEmptyJs() && r == true) {
                    cx.evaluateString(scope, "__pass.value = 'manager-filled';", "fill", 1, null)
                }
                r
            },
            waitTimeoutMs = 2_000L,
            clock = fakeClock,
            sleeper = fakeSleeper
        )
        assertEquals("the flow's own focus stamp must not invalidate the document binding",
            CredentialFillOutcome.FILLED, outcome)
    }

    companion object {
        /**
         * Minimal DOM: only what the credential JavaScript uses
         * (querySelector with tag + [attr="value"] selectors, focus,
         * set/getAttribute, value). documentElement.outerHTML renders the
         * live elements, so setAttribute (the focus stamp) rotates the
         * fingerprint exactly like a real page; assigning outerHTML
         * installs a static override for DOM-replacement simulations.
         */
        private val DOM_SHIM = """
function __matches(el, sel) {
  var tag = sel.split('[')[0];
  if (el.tagName.toLowerCase() !== tag) return false;
  var re = /\[([\w-]+)(?:="([^"]*)")?\]/g, m;
  while ((m = re.exec(sel)) !== null) {
    var v = el.getAttribute(m[1]);
    if (v === null || v === undefined) return false;
    if (m[2] !== undefined && v !== m[2]) return false;
  }
  return true;
}
function __mkInput(attrs) {
  var el = {
    tagName: 'INPUT',
    _attrs: {},
    value: '',
    focused: false,
    getAttribute: function(n) { return (n in this._attrs) ? this._attrs[n] : null; },
    setAttribute: function(n, v) { this._attrs[n] = String(v); },
    focus: function() { this.focused = true; }
  };
  for (var k in attrs) { el._attrs[k] = String(attrs[k]); el[k] = String(attrs[k]); }
  return el;
}
var __pass = __mkInput({type: 'password', name: 'password'});
var __page = [__pass];
// The live DOM rendered from the actual elements: setAttribute (the
// focus stamp) changes the rendered markup, exactly like a real page's
// outerHTML. Assigning documentElement.outerHTML still installs a static
// override, so DOM-replacement tests keep working.
var __outerHtmlOverride = null;
function __renderOuterHTML() {
  if (__outerHtmlOverride !== null) return __outerHtmlOverride;
  var html = '<html><body>';
  for (var i = 0; i < __page.length; i++) {
    var el = __page[i];
    var attrs = '';
    for (var k in el._attrs) { attrs += ' ' + k + '="' + el._attrs[k] + '"'; }
    html += '<' + el.tagName.toLowerCase() + attrs + '>';
  }
  return html + '</body></html>';
}
var document = {
  querySelector: function(sel) {
    for (var i = 0; i < __page.length; i++) {
      if (__matches(__page[i], sel)) return __page[i];
    }
    return null;
  },
  // Live page identity for the production host/document bindings: tests
  // mutate these to simulate a navigation or DOM replacement WITHOUT
  // touching any cache, proving the production JS reads the actual page.
  documentElement: {}
};
Object.defineProperty(document.documentElement, 'outerHTML', {
  get: function() { return __renderOuterHTML(); },
  set: function(v) { __outerHtmlOverride = String(v); },
  configurable: true
});
var location = { host: 'example.com', hostname: 'example.com', href: 'https://example.com/login' };
function __replaceFieldWithValuedLookalike() {
  // The DOM swaps the focused field for a lookalike that already carries
  // a value — the swap drops the focus stamp.
  var evil = __mkInput({type: 'password', name: 'password'});
  evil.value = 'attacker-set';
  __page = [evil];
}
"""
    }
}
