package com.battlesbudz.jarvis.v2.actions

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.robolectric.RobolectricTestRunner

/**
 * Jerry's review (build 1196): the submit dispatch queried
 * `[data-jarvis-id="form0"]`, but the snapshot extractor never stamped the
 * FORM element — it only emitted `form0` in the snapshot JSON. The real
 * submission JavaScript could therefore never find the approved form, while
 * the fake-bridge happy path (which checks the snapshot's form list) kept
 * passing.
 *
 * This regression runs the ACTUAL extraction JavaScript
 * ([BrowserActivity.SNAPSHOT_JS]) and the ACTUAL submit dispatch JavaScript
 * ([WebViewBrowserBridge.submitDispatchJs]) together inside a minimal DOM
 * shim: the extracted form must be submittable, and a replaced document
 * must be refused with zero submission. The browser gate stays closed —
 * this tests the JavaScript contract, not the catalog.
 */
@RunWith(RobolectricTestRunner::class)
class BrowserSubmitJsTest {

    private lateinit var cx: Context
    private lateinit var scope: ScriptableObject

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
        cx.evaluateString(scope, LOGIN_PAGE, "login-page", 1, null)
    }

    @After
    fun tearDown() {
        Context.exit()
    }

    private fun evalJs(js: String): Any? = cx.evaluateString(scope, js, "test", 1, null)

    @Test
    fun extractedFormIsStampedAndSubmits() {
        // The ACTUAL extraction JavaScript, as shipped in BrowserWebView.
        val json = evalJs("JSON.stringify(" + BrowserActivity.SNAPSHOT_JS + ")") as String
        val root = JSONObject(json)
        assertEquals("https://example.com/login", root.getString("url"))
        val forms = root.getJSONArray("forms")
        assertEquals(1, forms.length())
        val form = forms.getJSONObject(0)
        assertEquals("form0", form.getString("id"))
        assertEquals("POST", form.getString("method"))
        val fields = form.getJSONArray("fields")
        assertEquals(2, fields.length())
        assertEquals("f0", fields.getJSONObject(0).getString("id"))
        assertEquals("f1", fields.getJSONObject(1).getString("id"))
        assertTrue(fields.getJSONObject(1).getBoolean("secret"))
        val fingerprint = root.getString("fingerprint")
        assertTrue("extraction must fingerprint the stamped DOM", fingerprint.isNotEmpty())

        // The stamp landed on the actual FORM element — the pre-fix bug was
        // that only the JSON carried the id while the DOM had no stamp.
        assertEquals(
            true,
            evalJs("""document.querySelector('[data-jarvis-id="form0"]') === __form""")
        )
        assertEquals(
            "FORM",
            evalJs("""document.querySelector('[data-jarvis-id="form0"]').tagName""")
        )

        // The ACTUAL submit dispatch JavaScript submits the extracted form:
        // the stamped id resolves and the in-page fingerprint matches the
        // approved one, atomically, in a single evaluation.
        val dispatch = WebViewBrowserBridge.submitDispatchJs("form0", JSONObject.quote(fingerprint))
        assertEquals(true, evalJs(dispatch))
        assertEquals(true, evalJs("__submitBtn.clicked"))
    }

    @Test
    fun changedDocumentRefusesWithZeroSubmission() {
        val fingerprint = (JSONObject(
            evalJs("JSON.stringify(" + BrowserActivity.SNAPSHOT_JS + ")") as String
        )).getString("fingerprint")
        // The DOM changes after the approval (an attribute mutates): the
        // in-page fingerprint no longer matches the approved one, so the
        // atomic approved-target/fingerprint check refuses.
        evalJs("__submitBtn.setAttribute('disabled', 'true')")
        val dispatch = WebViewBrowserBridge.submitDispatchJs("form0", JSONObject.quote(fingerprint))
        assertEquals(false, evalJs(dispatch))
        assertEquals("zero submission on a replaced document", false, evalJs("__submitBtn.clicked"))
    }

    @Test
    fun unstampedFormIsNeverFound() {
        // The pre-fix failure mode, pinned: the page was extracted but the
        // FORM element carries no stamp, so the real dispatch cannot find
        // the approved form and refuses instead of submitting blindly.
        evalJs(LOGIN_PAGE) // rebuild the page; no extraction stamps it
        val dispatch = WebViewBrowserBridge.submitDispatchJs("form0", JSONObject.quote("fp-approved"))
        assertEquals(false, evalJs(dispatch))
        assertEquals("zero submission when the target is absent", false, evalJs("__submitBtn.clicked"))
    }

    companion object {
        /**
         * Minimal DOM: only what SNAPSHOT_JS and the submit dispatch use
         * (querySelector(All) with simple selectors, set/getAttribute,
         * innerText/value/href/action/method/type/name/placeholder/tagName,
         * click/submit, and a deterministic outerHTML for the fingerprint).
         */
        private val DOM_SHIM = """
function __matches(el, sel) {
  sel = sel.replace(/^\s+|\s+${'$'}/g, '');
  var m;
  if (sel.charAt(0) === '[') {
    m = /^\[([\w-]+)(="([^"]*)")?\]${'$'}/.exec(sel);
    if (!m) return false;
    var v = el.getAttribute(m[1]);
    if (v === null || v === undefined) return false;
    return m[3] === undefined || v === m[3];
  }
  m = /^([A-Za-z0-9]+)(\[([\w-]+)(="([^"]*)")?\])?${'$'}/.exec(sel);
  if (!m) return false;
  if (el.tagName !== m[1].toUpperCase()) return false;
  if (m[3] === undefined) return true;
  var v2 = el.getAttribute(m[3]);
  if (v2 === null || v2 === undefined) return false;
  return m[5] === undefined || v2 === m[5];
}
function __selectAll(root, sel) {
  var out = [];
  var parts = sel.split(',');
  function walk(el) {
    for (var i = 0; i < parts.length; i++) {
      if (__matches(el, parts[i])) { out.push(el); break; }
    }
    for (var c = 0; c < el.childNodes.length; c++) walk(el.childNodes[c]);
  }
  walk(root);
  return out;
}
function __outerHTML(el) {
  var s = '<' + el.tagName.toLowerCase();
  var names = [];
  for (var k in el._attrs) names.push(k);
  names.sort();
  for (var i = 0; i < names.length; i++) s += ' ' + names[i] + '="' + el._attrs[names[i]] + '"';
  if (el.tagName === 'INPUT' || el.tagName === 'BR' || el.tagName === 'IMG') return s + '>';
  s += '>';
  for (var c = 0; c < el.childNodes.length; c++) s += __outerHTML(el.childNodes[c]);
  return s + '</' + el.tagName.toLowerCase() + '>';
}
function __mkEl(tag, attrs, kids) {
  var el = {
    tagName: tag.toUpperCase(),
    _attrs: {},
    childNodes: kids || [],
    innerText: '',
    value: '',
    clicked: false,
    submitted: false,
    getAttribute: function(n) { return (n in this._attrs) ? this._attrs[n] : null; },
    setAttribute: function(n, v) { this._attrs[n] = String(v); },
    querySelectorAll: function(sel) { return __selectAll(this, sel); },
    querySelector: function(sel) { var r = __selectAll(this, sel); return r.length ? r[0] : null; },
    click: function() { this.clicked = true; },
    submit: function() { this.submitted = true; }
  };
  for (var k in attrs) { el._attrs[k] = String(attrs[k]); el[k] = String(attrs[k]); }
  if (el.tagName === 'INPUT' && !('type' in el._attrs)) { el._attrs['type'] = 'text'; el.type = 'text'; }
  // Lazy: the fingerprint reads outerHTML after stamping, so it must
  // reflect the current attributes.
  Object.defineProperty(el, 'outerHTML', { get: function() { return __outerHTML(this); }, enumerable: true });
  return el;
}
"""

        /** A small login page fixture, rebuilt by re-evaluating this script. */
        private val LOGIN_PAGE = """
var __email = __mkEl('input', {type: 'email', name: 'email', 'aria-label': 'Email'}, []);
var __pass = __mkEl('input', {type: 'password', name: 'password', placeholder: 'Password'}, []);
var __submitBtn = __mkEl('button', {}, []);
__submitBtn.innerText = 'Sign in';
var __form = __mkEl('form', {action: 'https://example.com/session', method: 'post'}, [__email, __pass, __submitBtn]);
var __homeLink = __mkEl('a', {href: 'https://example.com/'}, []);
__homeLink.innerText = 'Home';
var __body = __mkEl('body', {}, [__homeLink, __form]);
__body.innerText = 'Sign in to Example';
var __head = __mkEl('head', {}, []);
var __html = __mkEl('html', {}, [__head, __body]);
var document = {
  title: 'Example login',
  documentElement: __html,
  body: __body,
  querySelectorAll: function(sel) { return __selectAll(this.documentElement, sel); },
  querySelector: function(sel) { var r = __selectAll(this.documentElement, sel); return r.length ? r[0] : null; }
};
var location = { href: 'https://example.com/login' };
"""
    }
}
