# Browser Unit 1 report

## Changes

- `actions/MobileToolCatalog.kt`: declared `web_fetch` and `web_search` in the shared native-tool schema catalog.
- `actions/MobileAction.kt`: added typed read-only actions and validated HTTPS URLs / bounded queries.
- `actions/NativeActionDecoder.kt`: decoded both structured model calls into the shared action request envelope.
- `actions/AndroidMobileActionExecutor.kt`: dispatched through the existing executor and emitted short diagnostics without logging page content or the search query.
- `actions/WebReadTools.kt`: added an injectable GET seam, a timeout and body cap, HTTPS URL checks, HTML text extraction, bounded responses and untrusted-content framing.
- `actions/WebReadToolsTest.kt`: covers URL policy, HTML cleaning, truncation, timeout/HTTP failure honesty, wrapping and search result shape.

The registry-to-execution audit followed `MobileToolCatalog` → `NativeActionDecoder` → `MobileActionValidator` → `MobileActionPipeline` → `AndroidMobileActionExecutor`. `open_website` is a local dispatch tool through the executor, while `run_in_sandbox` demonstrates an injected executor bridge and receipt convention. `McpTransport` is the network example: injectable transport, bounded timeouts, status/body response object and centralized diagnostics. Historical memory uses explicit untrusted framing and JSON quoting; web output uses a parallel explicit warning and escapes markup delimiters.

## Deliberately not done

No forms, authentication, clicking, file downloads, external browser handoff, UI, changes to existing tool behavior, sandbox changes, network access, Gradle execution, APK creation or publishing.

## Tests

Added `WebReadToolsTest`; tests pin the stated policy and injected HTTP behaviors without network. They were not run because the task explicitly prohibits Gradle execution and this environment lacks Android SDK support.

## Risks / owner review

The current search parser uses DuckDuckGo's HTML response shape and may need fixture updates if that changes. Redirects are not followed intentionally. The built-in Java URLConnection response reads are capped while reading. Review whether web failure strings should be represented as failed `ExecutionResult` values: the shared result currently wraps the human-readable message as a successful tool dispatch even when that message reports an HTTP/network failure. The search snippet extraction is intentionally simple and should receive additional captured HTML fixtures before wider release.

## On-phone proof

On an instrumented phone build, ask Faraday to read a known public HTTPS article and summarize it, then ask a factual lookup requiring a web search. Confirm it returns titles and URLs, explicitly treats injected page instructions as untrusted, and reports an offline/timeout failure without inventing page text. Confirm no browser UI or form interaction occurs.
