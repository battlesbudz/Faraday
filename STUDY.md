# Memory Unit 4 study: entity and temporal projection

## What the current system can answer

The canonical memory ledger is `SQLiteMemoryStore`: it persists `MemoryRecord`
rows plus separate, content-free erase tombstones. A record includes content,
category/type, timestamps, review state, expiry, and an explicit
`correctsMemoryId`; embeddings and wiki pages are rebuildable retrieval
projections. `MemoryOs` accepts a correction only against an approved record.
Approving it marks that target `SUPERSEDED`; deleting any member erases its full
correction lineage and records opaque event tombstones.

Unit 2 hybrid recall searches approved, non-expired rows through lexical,
semantic-vector and wiki candidate sources, fuses ranks, and splits a profile
slice from searched results. Superseded rows are deliberately ineligible, so a
normal recall query is biased toward current facts. That is appropriate for
context injection, but it leaves three useful questions without a direct
answering structure:

* “What do I know about Maya?” needs record-to-entity links and aggregation
  across facts whose wording may differ.
* “When did I move?” needs the correction chain and its source timestamps in
  chronological order, including the superseded value.
* “What did I used to say about my address?” needs an explicit distinction
  between the active correction and retained historical versions.

Similarity search can nominate related snippets, but it does not establish
identity or temporal succession. Although the ledger already records approved
correction edges and timestamps, hybrid recall does not expose them as a
history query. Expiry and erase semantics also mean an index must be derived
from the current snapshot; it must not become another authority.

## Prototype boundary

`MemoryEntityTemporalGraph` builds an ephemeral entity/link and correction
timeline view from a caller-supplied `MemorySnapshot`. It indexes only approved
current facts for entity answers and uses approved correction descendants to
explain older versions. It skips expired and restricted/secret content. Since
the graph receives a snapshot after deletion, erased records are absent and
tombstones supply no content from which a node could be reconstructed.

The extractor is deliberately conservative and English/proper-name based; it is
a testable prototype, not general NER or production-grade entity resolution.
The `MemoryEntityTemporalPrototype.ENABLED` flag is false, and no production
caller, persistence, retrieval ranking, embedding, dream pass or UI is wired to
it. Fixture questions pin the intended minimum behavior: “What do I know about
Maya?”, “When did I move?”, and “What was my old address?”.
