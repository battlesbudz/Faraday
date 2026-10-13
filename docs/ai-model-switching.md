> Qwen update: [15 selectable LiteRT-LM Qwen models](qwen-model-selection.md), using LiteRT-LM 0.16.0. See capabilities and pending Fold 6 acceptance there.

# Switch the local Gemma model

Open Voice settings → AI model after ending the Jarvis session. Select Gemma-4-E4B-it, then use Download and Install or choose `gemma-4-E4B-it.litertlm` from Downloads. The same selector is available during initial setup and after a failed model test. Setup checks the file and initializes the selected model before enabling calls.

E2B remains the default for existing installations. Both files remain installed separately. Selecting E2B again reuses its existing file and validation. This is a catalog of supported LiteRT-LM models, not a loader for arbitrary GGUF or incompatible architectures. Imported files must have the selected catalog filename; explicit imports retain the existing fingerprint-and-native-probe validation policy.

## E4B source

Pinned artifact: https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/blob/1fc8912676889ed3aeec478c92c1e239bed08928/gemma-4-E4B-it.litertlm

SHA-256: `f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc`.
The source lists a 3.65 GB download. Installation also needs temporary download/copy space. Actual runtime memory and latency on the Fold 6 require phone testing; fitting the file on disk does not establish that the complete voice pipeline fits in RAM.

## Runtime behavior

- Persist selection in `model_setup`; unrecognized saved IDs fall back to E2B, while unknown worker requests fail explicitly.
- Release the idle native engine and reset native conversation state before saving selection. Block switching during sessions, inference, model operations, and benchmarks. The transcript is retained.
- Route setup, text/vision/audio inference, smoke tests and Gemma benchmarks through the selected model. Record the model ID in benchmark rows and selection diagnostics.
- Give each model its own file, native cache directory, integrity fingerprint, smoke-test result and attempted-test marker. Migrate the old global success flag only for E2B.
- Persist the test-attempt marker before native initialization. After a crash or failed test, do not automatically retry loading that model on every launch; keep manual retry and switching available.
- Pin WorkManager requests to a model ID and observe ongoing setup after Activity recreation. No second model is intentionally kept resident during switching.

## Validation

Catalog unit tests cover default migration, selection restoration, unknown IDs, separate files and pinned download checksums. The GitHub Android workflow runs the release unit tests and builds the signed APK.

Phone acceptance tests (pending):
1. Upgrade with a validated E2B installation: no forced E2B re-download or setup regression.
2. End the session, select E4B, download/import and complete its model test; restart and confirm E4B remains selected.
3. Test conversation, a tool request, image input and both ASR-assisted and direct audio input; compare benchmark rows by `model_id`.
4. Switch back to E2B and confirm reuse of the installed file and normal wake/call behavior.
5. Attempt switching during a call/benchmark/download; rotate during setup and confirm the choice remains locked while work runs.
6. Test failed/corrupt import and interrupt native initialization. Reopen setup, select E2B and verify recovery without repeated automatic E4B initialization.
