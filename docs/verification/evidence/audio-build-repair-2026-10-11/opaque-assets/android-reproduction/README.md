# AGP gzip asset regression reproduction

Independent investigation, 2026-10-11. The app pins Android Gradle Plugin 8.7.3.
Its published Gradle and builder POMs pin sdk-common 31.7.3.

## Finding

The responsible stage is AGP asset merging, specifically AssetItem.create and
MergedAssetWriter.AssetWorkAction.run in sdk-common 31.7.3. AssetItem recognizes
a case-insensitive final `gz` extension and removes it from the merged asset name.
MergedAssetWriter then inflates that file with GZIPInputStream. A final `.bin`
extension takes its byte-copy path. This preserves compatibility with old AAPT
behavior, as explained by the source comments.

Direct AAPT2 8.7.3-12006047 linking does not perform that transformation. The
reproduction runs the actual published AGP classes, then the actual AAPT2 binary;
it is not a Python imitation of Android packaging. The Java harness uses
reflection only to access package-private AssetItem/AssetFile and the private
AssetWorkParameters constructor. The published AssetWorkAction executes unchanged.

## Reproduce

Requires a Java 21 runtime with source-file launch support and Python 3.10+.
Acquire the three JARs listed in tool-inputs.json from their official Maven URLs,
and extract `aapt2` from the official Linux JAR listed there into the same tools
directory. Make the extracted binary executable. The script verifies all exact
input SHA-256 values before running them. No third-party binaries are included.

    python reproduce_android_gzip_assets.py --tools-dir /path/to/tools --out /path/to/result

To exercise prepared vendor assets as well, add:

    --qnn-assets /path/to/prepared/assets/whisper-qnn-runtime

This directory is the output of the repository's existing pinned-AAR preparation
script. The optional stage does not fetch vendor artifacts or weights.

## Recorded result

Synthetic content: `.so.gz` changes from 130 gzip bytes into `.so` with 26,624
plain bytes during AGP merge. `.so.gz.bin` retains all 130 original gzip bytes,
name and SHA-256 through merge plus AAPT2 linking.

All five actual prepared QNN `.so.gz.bin` assets also retained their complete
names and bytes through those stages. The total was 9,306,959 gzip bytes,
representing 31,814,372 ELF bytes. Their decompressed sizes and SHA-256 matched the
prepared manifest; independent repository `check_elf` verification confirmed the
original architecture/16 KB conditions. Source/binary identifiers are recorded in
source-inputs.json and tool-inputs.json; actual outputs are in result/receipt.json
and reproduction.log. No tool or vendor binaries, APKs, or weights are included.

## Review

Reviewed producer suffix, generated manifest asset names, Kotlin runtime suffix,
and stronger APK exact-inventory checks. The five original hashes, sizes,
gzip decoding, ELF architecture/page checks, native dependency policy and
optional-library startup exclusions remain intact. Independently reran all
14 targeted packaging/native helper tests and `git diff --check`; all passed.
The exact inventory now rejects extra entries and duplicates in addition to raw
`.so` or legacy `.so.gz` names.

This is a focused asset-stage proof, not a full Gradle release build, Android
sandbox pass, physical-device test, or NPU inference/performance claim. The
normal exact-revision release gates remain required.
