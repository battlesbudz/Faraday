# Timing detail presentation and API 30 benchmark discovery

## Retained failing candidate

[Build 1292](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/38229044414)
tested PR head `f459db1388d0db18b37b2d1e4b45031ab3a946b1` through merge
`3ddfb52e520318b544bb37774c6aacf165521893`. The API 30 normal profile ran
82 main journeys with one failure: test45's first `benchmark_environment_NOISY`
lookup, before reference scoring and environment persistence assertions.
The prior-APK upgrade and actual Smart Turn JNI/control journey passed.
The missing later layout screenshot in the aggregate report is derivative:
the failed main phase prevented the later layout phase from running.

The [failed receipt](evidence/benchmark-timing-presentation-2026-10-12/build1292-failure-receipt.json),
[report](evidence/benchmark-timing-presentation-2026-10-12/build1292-api30-report.json),
[runner failure](evidence/benchmark-timing-presentation-2026-10-12/build1292-api30-failure.txt)
and [navigation trace](evidence/benchmark-timing-presentation-2026-10-12/build1292-api30-navigation.txt)
retain the exact source/APK/artifact identity. The full artifact contains the
reviewed PNG and XML. The PNG shows the expanded timing rows; the XML was
captured after fixture cleanup and is not the same viewport.

## Diagnosis and bounded correction

The sample header was safely expanded near the viewport bottom. The first
unknown-target discovery dispatched a fast 1,064-pixel stroke and passed the
adjacent controls. Subsequent discovery continued down the detailed metrics,
reversed after two stationary observations and exhausted its original 15-second
deadline after 13 gestures. This supports a harness overshoot diagnosis, not a
claim that the product's NOISY control was broken or manually unreachable.

Only the API 30 NOISY lookup opts into the existing overlapping, held discovery
path already used by adjacent review controls. The shared 15-second deadline,
14-gesture ceiling, fresh safe bounds, stable enabled observation and exactly
one physical tap are unchanged. No journey, assertion, threshold or required
profile is removed or skipped; no release dependency or workflow is changed.

Separately, the 31 new overlap/endpoint/backend-call metrics added avoidable
page length when expanding every sample, even for old records with no edges.
The application now groups only these new fields under an explicit
**Preparation and Smart Turn timing** label and a **Show timing measurements**
button with measured/total counts. Expansion reveals every value and definition,
including unavailable entries. Existing measurements stay visible in their
previous section. The state belongs to the selected card, and never modifies
recorded stages, metrics, retained samples, JSON, CSV or whole-text exports.
This is a presentation improvement; physical manual usability is not established
by the failed-run evidence.

## Verification

The [new source-bound host receipt](evidence/benchmark-timing-presentation-2026-10-12/host-receipt.json)
and [219-test JVM result](evidence/benchmark-timing-presentation-2026-10-12/junit.log)
cover the unchanged capture/Smart Turn closure and the exact, disjoint,
lossless timing-field partition. The architecture boundary check and all
124 plus 101 Python helper tests pass. Initial Smart Turn failure evidence
and the original 218-test receipt remain unchanged in their dated folder.

The existing required Android test45 now opens and closes timing details twice,
checks the collapsed count and expanded controlled value, requires identical
serialized data after both cycles, and checks measured and unavailable timing
fields in exports. It still performs the original environment selection,
reference scoring, human review, unavailable-value, copy/save/share, owner
replacement, persistence and reset assertions. Fresh selectors are created for
each expanded/collapsed label because `BySelector` is mutable.

Local Compose/Android execution is unavailable. These additions require the
full exact-revision signed release gate before the repaired APK can be called
verified. The controlled timing fixture is UI/export evidence, not phone
performance, physical overlap or incremental LLM-audio-prefill evidence.
