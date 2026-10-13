# Fixed Memory control navigation correction — 9 October 2026

This is a release-instrumentation search-strategy correction. Exact Android
execution remains required; it does not establish a passing APK or resolve the
whole Fold suite budget deficit.

## Evidence and bounded scope

Build 1239's retained Fold trace contains full-display vertical swipes while
looking for controls that do not scroll with Memory's content. The source in
`ui/MemoryScreen.kt` establishes their ownership:

- Header Add (`memory_new`), header Back (`memory_back`), search
  (`memory_search_input`), and bottom Chat/Voice navigation are siblings of the
  weight-1 body, outside its lists.
- Review/History tabs are in the body branch's non-scrolling Column, above and
  separate from the `WikiHome`, `ReviewList`, and `HistoryList` scrolling content.
  Search/page/detail branches can hide these tabs; swiping cannot reveal them.

Only these existing acquisitions change:

- test27: tap `memory_nav_chat` and `memory_nav_voice`; observe `memory_back`
  before the existing system Back. Observation does not tap the header Back.
- test24: Add, Review, and History taps; search-field text acquisition and
  readiness. Its exclusively used `searchMemory` helper still scrolls for the
  result content, retaining the same search assertion.

The ordinary generic `scrollTo`, `enabled`, `clickEnabled`, `enterText`, modal
and horizontal-chip helpers remain unchanged. Body/article/list controls and
all assertions, evidence captures, scenario lists, production source, workflow
budgets and assertion timeouts remain unchanged. No timing threshold was raised.

The recorded six test27 invalid-search envelopes total 24.138 seconds; the nine
additional test24 fixed-target envelopes total 28.532 seconds. Their 52.670-second
sum is an observed envelope, not promised savings. No per-swipe hierarchy proves
why a selector was initially absent. Removing a wrong gesture does not establish
that the control will appear within its intended deadline or eliminate the
approximately 117-second projected Fold deficit.

## Observer and action contract

The narrowly named observer uses one monotonic 15-second preparation/admission
deadline. It refreshes the accessibility cache on API 34+, reacquires the exact
selector, requires enabled/default-display/nonempty 24-pixel-inset bounds,
preserves ordinary idle semantics, and requires the full existing 300 ms
stability interval. Each potentially blocking observation caps ordinary implicit
idle waiting by remaining time, restores the prior setting in `finally`, and
checks elapsed time after returning. It does not use the zero-idle wrapper or
any scrolling fallback. An insufficient full stability interval fails.

After diagnostic status sends, a final fresh lookup checks the same node/window
identity, enabled/default-display state, safe bounds and unchanged geometry.
UiAutomator 2.3.0's public `UiObject2.equals` refreshes both underlying
accessibility nodes and compares their identity; its blocking work is subject
to the same post-return deadline check. Bounds are sampled after this identity
and enabled refresh. Replaced, missing, stale, disabled, moving or unsafe
observations restart observation only, within the same deadline.

For a tap, a final deadline guard admits exactly one `UiDevice.click` at the
sampled center, asserts its boolean result, records pre/post elapsed diagnostics,
and preserves the ordinary post-click idle wait. No retry encloses injection.
Unlike `UiObject2.click`, coordinate injection does not introduce an additional
hidden accessibility idle/refresh after that guard. This is a dispatch-admission
contract: synchronous Android input injection cannot be interrupted at 15 seconds.
No pre-dispatch logging occurs after the final fresh observation.

Search text retains `UiObject2.setText`, its one idempotent stale-node retry,
and conditional keyboard dismissal. Both acquisition attempts share one
deadline. The setter's implicit idle wait is capped/restored, and a late return
fails without another assignment. Synchronous Android internals can still cross
the API-admission boundary before returning; no hard interruption is claimed.

Diagnostics retain selector, elapsed time, attempts, initial/fresh bounds,
display size, state and zero search swipes. Tap receipts include admission time,
return time and injection result. An absent control fails honestly rather than
restoring the previous over-deadline blind scrolling.

## Local proof and remaining gate

The retained local evidence executes the exact unchanged helper block and cache
refresh helper against explicitly controlled Android/UiAutomator/clock models.
All 61 checks pass. They cover delayed appearance; stale replacement; disabled,
moving and unsafe targets; fresh/default-display transitions; display shrink;
blocking lookup/getter/equality/idle/diagnostic expiry; full 300 ms stability;
post-diagnostic disappearance and node/window changes; zero/single injection;
failed/uncertain/long injection; configured-idle restoration; search assignment,
one stale retry, expiry and conditional keyboard Back. These models prove helper
control flow, not real accessibility, window delivery, gesture targeting or
Android latency.
Eight separately compiled negative controls are rejected: shortened stability,
ignored fresh-enabled state, ignored stable bounds, omitted final identity,
expired tap admission, skipped cache refresh, duplicate injection, and skipped
final lookup. These are deliberate model-boundary mutations, not Android runs.

The exact helper plus exact cache-refresh helper also compiles against the real
retained Android 14 framework and UiAutomator 2.3.0 API jars with Kotlin 2.3.21.
That focused compilation is not full Compose test-class compilation, minified
APK construction or runtime Android verification. Architecture checks and all
102 general/82 verification Python checks pass.

A source normalization audit removes only the new helper and reverses the
specified fixed-control substitutions; the entire remaining test source is
byte-for-byte equal to base `18ef4d118c2e5ce44a795871a2bb430d3c7c4a29`.
The exact-revision hosted release/JVM/native/APK and five-profile Android gates,
including all existing test24/test27 assertions and evidence, remain required.
Physical audio, model inference and device-performance coverage remain separate.
