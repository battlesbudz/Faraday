# G4E2BWR1 runtime format

All binary integers are unsigned big-endian on disk. Supported sizes and offsets
are below `Long.MAX_VALUE`, and the Java implementation rejects negative values.
No FlatBuffer parser, ML library, or JSON parser is needed at reconstruction time.

## Header: 124 bytes

| Bytes | Field |
|---|---|
| 0..7 | ASCII `G4E2BWR1` |
| 8..15 | Full official source size, uint64 |
| 16..23 | Exact output size, uint64 |
| 24..55 | Full official source SHA-256, 32 raw bytes |
| 56..87 | Exact output SHA-256, 32 raw bytes |
| 88..119 | Compressed structural literal asset SHA-256, 32 raw bytes |
| 120..123 | Number of copy records, uint32 |

## Each copy record: 56 bytes

| Bytes | Field |
|---|---|
| 0..7 | Destination offset, uint64 |
| 8..15 | Absolute official bundle offset, uint64 |
| 16..23 | Payload length, uint64 |
| 24..55 | SHA-256 of copied payload bytes, 32 raw bytes |

The current file is exactly `124 + 1537 * 56 = 86196` bytes. Extra data is rejected.
Records are ordered by destination offset and never overlap. A source interval
must lie entirely inside exactly one pinned model section:

- encoder: offset 1,393,082,368; length 94,052,280
- adapter: offset 1,487,142,912; length 9,441,244
- EOA: offset 1,496,596,480; length 6,772

The full source is 2,588,147,712 bytes, so 32-bit source offsets must not be used
as a general file-size representation. Source copy offsets happen to be below
2 GiB in this specific bundle, but the source and format require 64-bit I/O.

## Structural literal stream

`structural-literals.bin.gz` contains one deterministic gzip member. After
inflation, it holds the output bytes outside the copy spans, concatenated in
output order. Its exact uncompressed length is 1,068,632 bytes. It includes no
placeholder payload vectors and no source-carried weight/calibration values.

Algorithm:

1. Authenticate the two asset hashes against constants in reviewed application
   source, then validate the recipe header and all bounds.
2. Open the full source as one read-only seekable handle and verify its size and
   complete SHA-256. Keep that handle for subsequent copy reads.
3. Create a temporary file in the trusted final output directory. Set output
   cursor to zero; open the gzip literal stream.
4. For each record, append `record.destination - cursor` literal bytes. Seek the
   source handle, copy exactly `record.length` bytes, and verify that span's SHA.
   Set cursor to `record.destination + record.length`.
5. Append `output_size - cursor` remaining literal bytes. Require immediate
   literal EOF; verify complete output SHA and length.
6. Flush/fsync; recheck cancellation; atomically rename into an absent target.
   On every failure or cancellation, remove the temporary output.

A hash inside a recipe is not an authenticity anchor. The reassembler separately
pins the recipe and literal asset SHA-256 in code. Updating those constants
requires regenerating and reviewing the complete semantic proof. Never accept
an arbitrary remote recipe or output hash supplied alongside untrusted assets.

## Semantic provenance

The binary recipe is compact; `semantic-copy-map.json` contains its auditable
counterpart. Each record names the source section and `buffers[i].data` or
`subgraphs[g].tensors[t].quantization.FIELD` path, tensor owners, exact range,
hash, and identity/crop transformation. No byte-search diff tool is used.

The authoring script excludes every known parameter-bearing Buffer.Data and
quantization vector from the literal stream. It rejects unknown payload forms,
and verifies all novel buffer constants constructively. It also checks that
no original nonstructural parameter or quantization payload was dropped.
This is a pinned semantic audit, not a legal determination about derivative
artifacts and not a generic arbitrary-FlatBuffer sanitizer.

## Android integration boundary

The supplied Java primitive intentionally uses standard Java I/O, SHA-256,
gzip, `BooleanSupplier`, and `java.nio.file.Files.move`. Its intended minimum
Android API is 26, with the app's normal Java 8 language/desugaring toolchain.
Only host Java compilation/execution was checked here. Confirm Android
bootclasspath support, D8/R8 packaging, asset loading, private file handling,
ART behavior, cancellation, and fsync/atomic rename on the actual target.

Do not reassemble on the main/UI thread. Do not map/read the entire official
bundle into a Java byte array. Keep the official bundle and derived file out of
logs, telemetry, crash attachments, source archives, and APK assets unless the
specific distribution has been separately authorized and reviewed.
