# Faraday main integration — 12 October 2026

Existing PR #6 integrates the committed audio-pr2 work into main. Integration
head `1e192fa90dab6ac3fd705d4180de8cd73af1fefb` merges main's
`bfc1601cf50f73009ad5f84ede90df0e01d41d92` history into released audio head
`d9676cba23951e8db32f201e8a6338a8a8da5e5a`. Its only content difference from
that audio baseline is main's README, preserved byte-for-byte. Proposed README,
icon and uncommitted native experiments are excluded. Main is unchanged until
the reviewed candidate passes the full gate and is merged.

## Build 1295: non-relocatable native cache

[Build 1295](https://github.com/battlesbudz/Faraday/actions/runs/38300248358)
tested merge `6f8b43ce669fdf5279689c5f9b983a6eea51d7e7`. The
[signed APK job](https://github.com/battlesbudz/Faraday/actions/runs/38300248358/job/114992721683)
failed in `:app:buildSherpa` before release JVM tests or APK assembly completed.
CMake rejected a restored `CMakeCache.txt` generated under
`/home/runner/work/Jarvis-OS-V2/Jarvis-OS-V2` because the renamed repository's
checkout is `/home/runner/work/Faraday/Faraday`.

The existing native cache key included OS and source hashes but omitted the
repository name. GitHub retained the old cache across the rename. Retrying the
unchanged key would restore the same incompatible absolute paths.

The bounded infrastructure repair includes `github.repository` in the shared
speech-runtime cache key and keeps the existing OS/source-hash binding. There
is no restore-key fallback to the old namespace. This rebuilds the native cache
for Faraday without changing application code, native source, dependencies,
compiler flags, test assertions, release gates or time limits.

`test_workflow_native_cache.py` checks the repository/OS/source bindings and
rejects an unscoped fallback. It fails against the original workflow and passes
with the corrected key. The architecture guard and both complete Python helper
suites must pass before publication. A new exact-revision full release gate is
still required; this source-level check is not APK or device verification.

The failed run and its logs remain evidence. Physical audio, OEM behavior,
Snapdragon acceleration, latency and thermal performance remain separate device
qualification requirements.

## Build 1295: stale diagnostic repository identity

The same run's
[native quality job](https://github.com/battlesbudz/Faraday/actions/runs/38300248358/job/114992721721)
completed native probe compilation, then the optional diagnostic failed during
argument admission with `identity_unavailable`. Its status context was null and
no child started. Full quality admission subsequently rejected the missing
diagnostic cleanup receipt, reporting `orchestration_failure` at `verify_build`
before any model lane ran. The cleanup failure remains a failure, not a pass.
Weight-free quality evidence is retained in artifact `11772966471`, ZIP SHA-256
`d99805e7676695c7b3d12cbc6f042f13e1c0b88e9f2028736b70af19c96d6eb9`.

Two diagnostic validators still required the exact old repository name,
`battlesbudz/Jarvis-OS-V2`. GitHub confirms the renamed `battlesbudz/Faraday`
repository retains numeric ID `1355144508`. The bounded repair changes those
two expected-name literals and their synthetic fixtures to the current name.
The validators continue to accept exactly one repository name; the old name,
other owners, other repositories and prefix/suffix lookalikes are rejected.
The existing same-repository PR-head check, run/attempt/source/digest bindings,
resource budgets, cleanup latch, private supervisor receipt and all quality
acceptance checks remain intact. No numeric-ID field or schema migration is
introduced; the existing guard schema uses the exact repository name.

The two new regressions fail against the old validators and pass after the
rename correction. The recipe manifest is refreshed only for changed, reviewed
Python guards and fixtures. The upstream/license policy, its canonical digest,
native source, model inputs, reference values and acceptance contract are unchanged.
Diagnostic, quality-orchestration, SDK/release-binding and full helper suites
must pass locally, followed by the complete exact-revision hosted gate. These
synthetic tests do not constitute real-model or Android verification.
