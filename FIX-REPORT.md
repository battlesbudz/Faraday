# PR #26 catalog parity fix

## Root cause

`MobileToolCatalogTest.catalogDrivesEveryLiteRtSchema` forces
`BrowserRuntimeGate.wired = false`, then asserts that the model-visible
catalog has exactly the 15 tool names listed in the test. The new
`web_fetch` and `web_search` entries were unconditional in `MobileToolCatalog.entries`,
so `MobileToolCatalog.all()` returned 17 names while the test's unwired-runtime
contract expected 15. `MobileActionToolDefinitions` builds LiteRT schemas from
`all()`, so catalog and schema generation were otherwise in lockstep; the
failure was that web tools had no runtime-readiness gate. `NativeActionDecoder`
and `MobileToolCatalog.find` already knew both tools, and the production
`AndroidMobileActionExecutor` already dispatched them through `WebReadTools`.

The existing `AudioToolGuard` intentionally excludes browser/navigation and
network tools in direct-audio mode. That policy remains unchanged. The new web
tools retain HTTPS-only fetch validation, bounded requests/results, and
untrusted-content wrapping.

## Fix

Added `MobileToolCatalog.WebReadRuntimeGate` and `WEB_READ_TOOL_NAMES`. The
catalog continues to resolve both tools for strict decoding and journaled
lookups, but omits their schemas from `all()` until the web-read dispatch path
is wired. `JarvisRuntime` opens this gate alongside the existing browser gate;
its production action executor includes `WebReadTools`. Thus the unwired catalog
matches the existing exact-name invariant and the live runtime exposes matching
catalog entries and LiteRT schemas for both web tools.

## Files changed

- `app/src/main/java/com/battlesbudz/jarvis/v2/actions/MobileToolCatalog.kt`
- `app/src/main/java/com/battlesbudz/jarvis/v2/JarvisRuntime.kt`
- `FIX-REPORT.md`

## Verification and expected CI result

Ran `git diff --check` successfully. Per task instructions, Gradle and Android
builds were not run. CI should now pass
`MobileToolCatalogTest > catalogDrivesEveryLiteRtSchema`; the reported
`:app:testReleaseUnitTest` result should be 680 tests completed with 0 failures.
