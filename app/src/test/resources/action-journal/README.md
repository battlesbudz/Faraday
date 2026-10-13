# Cross-version journal fixtures

These synthetic fixtures were serialized by the unchanged original Kotlin writers
and decoded by the unchanged Muse build-1067 reader, not handwritten to match the
compatibility implementation. No user/device data is included.

- `schema1-original.json`: b69c2fdbd67423360ca31b5feed3db0d83e510a5
- `schema2-legacy.json`: c199a6ceb9e4a73f8496c3f3c93c8b1923fca273
- `schema2-muse-source-access.json` and `schema2-muse-native-compatible.json`:
  71452fccc8f4b0553f279c1b15566313bc3d81d3
- `schema3-muse-build1067.json` and `schema3-muse-native-compatible.json`:
  b29b19bfa12495b537bea691ada124f4501e08fc

Repository: https://github.com/battlesbudz/Jarvis-OS-V2

The full Muse fixtures contain media actions supported by the integrated common
codec. All six fixtures must survive migration and compatible writes. The
native-compatible variants retain phone
permissions, denied/revoked other-source records, approvals, grants and receipts.
The schema-3 positive fixture also retains a genuine workflow, waiting occurrence,
completed-step IDs, step results, resume time/path and workflow receipts. Native
compatibility does not imply that this branch runs those workflows.

`schema2-upgrade-terminal-receipt.json` reproduces the exact field shape written
by Build 1086's `UpgradeFixtureTest.kt:74–80` during actual replacement-install
verification (fixed synthetic timestamp). This legacy terminal-only schema-2
packet intentionally omits the later optional authority/ownership fields and
active-question key. It has no groups, approvals, grants or events. It is
source-derived; unlike the six fixtures above, it is not an original codec-writer
output. Active or partially annotated variants must be rejected without a write.
