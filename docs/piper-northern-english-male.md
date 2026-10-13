# Piper Northern English Male

Selectable voice: **Piper — Northern English Male** (`piper_northern_english_male_medium`).
Uses Sherpa VITS with speaker 0, 22,050 Hz output, bundled espeak data and normal 1x playback by default. Calls use four synthesis threads; experimental pace profiles are ignored. Piper is the only speech-output model; Kokoro and Paul are removed.

Initial setup (or first preparation if needed) downloads the checksum-pinned 67,210,490-byte upstream archive; subsequent use is offline, with no account or key. Installation verifies every archived file size and retains MODEL_CARD. Dataset attribution: OpenSLR 83, CC BY-SA 4.0 International, http://www.openslr.org/83/.

## Phone check
Open the voice comparison screen, choose Piper — Northern English Male, and run the selected voice test at 1x. The selected voice is also used for the next voice call. Listen for accent consistency and pauses, then copy the whole suite report. Hardware performance and listening quality require a phone test; host compilation does not establish them.

The accepted Piper voice now replaces all retired speech engines. See [upgrade behavior](supported-model-stack.md).

## Longer passages and natural pauses (follow-up to build 689)

Device suite `cc2a7f77-d0dc-45cf-b4f7-ea13cf1d56e6` completed six runs without measured playback starvation; story synthesis work was about 1.5 s for 26.8 s of audio. Justin still heard phrase-boundary accent/prosody shifts and sentences crowded together. These are listening reports, not conclusions from RTF.

Piper now defaults to collecting complete sentences toward 320 characters, bounded at 640 characters per native generation. Short replies below the target are released together when Gemma finishes. Saved legacy opening profiles no longer affect calls. No timer guesses half of an unfinished answer. Waiting for more Gemma text can increase startup latency.

In this mode Sherpa VITS uses `maxNumSentences=0`: its `Process` joins the frontend token vectors and invokes the VITS model once for the bounded passage. The previous `maxNumSentences=1` silently split even multi-sentence submissions internally. Model reuse keys include this setting. Frontend punctuation/BOS/EOS tokens remain untouched; one invocation does not guarantee constant accent or prosody. Source: pinned sherpa-onnx v1.13.7 `sherpa-onnx/csrc/offline-tts-vits-impl.h`.

Piper's generation `silenceScale` is restored from 0.2 to 1.0 for all calls, preserving model-generated pauses at 1x playback. No fixed silence is inserted between individual words or sentences. Piper filler caches are versioned so previously compressed clips are not reused.

### Phone test
1. Select **Piper — Northern English Male**, **1.0×**, and **Piper · longer passages**.
2. Tap **Test selected voice**. The six-run suite uses the same short/paragraph/story inputs as before. Copy **whole suite** once afterward.
3. Optionally select **Piper · wait for full reply** and test again. This waits for Gemma's full text; inputs up to 640 characters are one generation. Longer text is still bounded into passages after completion.
4. Choose the preferred mode and tap **Apply to voice calls**. Ask a short question and then a longer one. Listen for sentence spacing, accent consistency, missing words, and first-response delay.

Diagnostics identify `piper_whole_passages_max640_v1`, actual submission count in `phrases`, and per-passage character counts in live synthesis traces.  Tests cover short-reply preservation, story grouping, bounded long/run-on input without text loss, native sentence batching, and natural-pause configuration. Device acceptance remains open.

See [current settings and diagnostics](current-diagnostics.md). The faster-opening experiment and profile matrix are retired; upgrades use the fixed natural-passage policy.
