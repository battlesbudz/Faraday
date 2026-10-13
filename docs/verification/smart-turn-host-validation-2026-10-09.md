# Smart Turn host model validation — 9 October 2026

The unchanged production C++ Session matched the independent official frontend
and pinned model on all eight existing public/synthetic fixtures. Returned
probabilities were float32 bit-identical; the maximum frontend error was
2.384185791015625e-7 within the unchanged 2e-6 fixture tolerance. No probability
tolerance was introduced.

The measured local source commit `165ffbb46af313d51e3947a2e70cab3eaef3a543`
has the same tree (`bd90286517b045508d2b2b778eb89322fac5920d`) as published
[`43cdf8ccd5a9090119e94c27c8de6af5c61f7c1f`](https://github.com/battlesbudz/Jarvis-OS-V2/commit/43cdf8ccd5a9090119e94c27c8de6af5c61f7c1f).
The historical harness retains its exact local-commit guard; it is not an
unrestricted runner for later revisions. Native/session/frontend identities
are recorded independently of the Git metadata.

See the [results and coverage limits](evidence/smart-turn-host-2026-10-09/RESULTS.md),
[original receipt](evidence/smart-turn-host-2026-10-09/receipt.json),
[independent result review](evidence/smart-turn-host-2026-10-09/independent-result-review.json)
and [cleanup receipt](evidence/smart-turn-host-2026-10-09/cleanup.json).
The folder contains the reviewed harness and metadata only. Downloaded model
weights, runtime libraries, compiled probes and generated arrays were removed
after review and are not included.

Observed host frontend plus inference cost was 64.82–77.11 ms for these eight
calls. Initialization was not separately timed. This is bounded host session
evidence; Android/JNI/R8 linkage, phone latency, resource contention and turn-end
calibration remain separate checks. Smart Turn remains off by default and has
no authority to end a turn.
