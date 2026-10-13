# Historical Smart Turn library checkpoint

This document is newly reconstructed, not a recovered original receipt.
The earlier author checkpoint was 012d6fb226d97b23e1bafebe607a4d8fded0b1e6;
later wiring was 7ff90e7dc7703117c27a3c0a3fde6df7c0c1062e. Neither lost commit's
historical green status is inherited by a reconstructed candidate.

The exact native/frontend/backend implementation was recovered by matching
surviving GitHub blobs. The final worker and wiring were also recovered with
full content identities. Earlier worker/settings variants were deliberately
excluded. Pinned model/source/runtime identities now live in
[the freshly reviewed manifest](../../third_party/smart-turn/manifest.json).

Before interruption, the official 8,679,182-byte CPU model was actually inspected:
float32 input_features [s6,80,800], float32 logits [s6,1] with sigmoid already
applied, IR 10 / opset 18 and no external tensors. Those are historical observations,
not a fresh model inspection in this recovery phase. No model was downloaded or
executed during recovery. Runtime initialization validates the exact weight hash,
names, types and tensor shapes before observing any microphone snapshot.

See [the fresh recovery contract](smart-turn-recovery-2026-10-09.md) and
[phone wiring](smart-turn-wiring-2026-10-09.md). Actual Android/NDK/16KB/shrunk
linkage and phone speed/cutoff-quality gates remain outstanding.
