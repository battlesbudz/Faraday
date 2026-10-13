# Pipeline benchmark evidence

This feature records observed Jarvis pipeline work and exports reproducible JSON/CSV evidence. It does not invent model performance, infer word accuracy from fluent output, or turn an emulator pass into physical microphone validation. Results belong to the recorded build, source commit, phone, models, runtime, backend, settings and environment.

## Acceptance boundaries

| Check | Coverage | Failure case |
| --- | --- | --- |
| Missing timings/tokens remain unknown; real zero remains zero | JVM | An absent event must not become a zero-latency sample |
| Capture turns and native submissions have distinct identifiers | JVM and live integration | A retry, draft or audio transcription must not inflate answer throughput |
| Cancelled, errored, rejected and no-speech attempts remain visible | JVM and live integration | Failed attempts must not silently disappear from benchmark denominators |
| Exports retain build/source/device/model/configuration provenance | JVM and Android integration | Results from different backends/phones must not be presented as one comparable result |
| WER/CER need a supplied verified reference | JVM and explicit user review | A transcript without ground truth has unknown accuracy |
| Metrics export excludes PCM, prompts and transcript/reference text by default | JVM and Android integration | Sharing metrics must not share the conversation |
| Existing chat/voice and action behavior remains intact | Release Android journeys and phone testing | Benchmark collection must not block microphone or generation workers |
| Noisy recognition improves on actual speech | Physical device and real weights only | Unit tests or synthetic state machines cannot verify restaurant recognition |

## Measurement contract

All timestamps within a turn use its named monotonic clock. Epoch time identifies the capture/export, and is never subtracted from a monotonic event. A missing, backwards or invalid duration is unknown. Acknowledgement/filler speech is excluded from first-answer events. Audio playback uses the playback-head proxy; it does not prove when sound reached the user's ears. Overlapping stages must not be added into a supposed total.

| Metric | Start → end / denominator | Interpretation |
| --- | --- | --- |
| Turn total | Turn start → terminal event | Includes listening time; compare separately from response latency |
| Capture readiness | Capture request → ready | Microphone/model startup, distinct from spoken input duration |
| ASR first partial | Speech start → first partial | First available partial, not final transcript correctness |
| ASR finalization | Finalization start → finalization complete | Wall time including queue/recovery; separate worker final-decode counter records executing recognizer work |
| ASR endpoint delay | Last detected speech → finalized transcript | Includes endpoint policy, scheduling and finalization |
| ASR real-time factor | Executing recognizer API work / cumulative PCM submitted to measured work | Excludes queue waits/model loading; Whisper overlapping windows and explicit recoveries each count submitted PCM; scopes differ from Moonshine native feed/flush work |
| Recognition queue/backlog | Observed queued audio work | Queue pressure, not word confidence |
| Endpoint → first answer text | Finalized recognition → first answer text | Includes retrieval/context/model work after recognition |
| Endpoint → first answer text ready | Finalized recognition → answer text ready before UI publication | Separate readiness event; does not claim the UI had already displayed text |
| Endpoint → first answer playback | Finalized recognition → first answer playback | Includes TTS/playback scheduling and excludes filler |
| Speech end → answer playback | Last detected speech → first answer playback | End-to-end voice response latency; end of detected speech is an acoustic proxy |
| App model TTFT | App inference measurement creation → first nonempty native raw text | Includes lazy conversation preparation. `submit_to_first_raw_text_ms` separately starts at SDK send entry; both differ from first control/tool callback |
| Native submission duration | Inside the SDK submission call | Not a direct audio-encoder or prefill measurement |
| Generation span | App measurement creation → terminal generation event | Preserve complete/cancelled/error outcome |
| Decode span | First actual text → generation end | Callback chunk boundaries do not establish native token boundaries |
| Exact decode throughput | (Native output tokens − 1) / (generation end − native first output token) | Requires genuine native token count **and** native first-token timing; first text callback may contain multiple tokens |
| Estimated output tokens/s | Explicit tokenizer-free estimate and its recorded span | Must retain the estimated label; callbacks are not tokens |
| TTS first PCM / playback | First answer speech text → PCM ready / playback | Backend/scheduling stages, distinct from model TTFT |
| TTS real-time factor | Busy synthesis work / generated audio duration | Excludes loading and playback; values below 1 mean synthesis outran real time |
| Playback starvation | Observed starvation duration | Separate from underrun count; a terminal drain underrun need not mean an audible mid-answer gap |
| Word/character error rate | Edit errors / verified reference words/characters | Reference-backed ASR accuracy, not whole-task correctness |
| Tool executor result | Actual typed executor receipt | Execution success does not prove the intended request was recognized correctly |
| Human task/intent/factuality verdict | Explicit reviewed PASS/FAIL/NOT_EVALUATED | Separately labeled human assessment; not automatically scored model accuracy |

Completed LiteRT-LM 0.16 Conversation submissions collect SDK `getBenchmarkInfo()` only after successful checked idle, with last-completed-prefill/decode counts and rates when exposed. The SDK field named TTFT is a **first-recorded-prefill plus first-recorded-decode average-token proxy**, not measured current-turn first-token latency; a separately prefilled fixed policy can supply its prefill origin. New records leave legacy `native_ttft_ms` null and export `sdk_first_prefill_decode_ttft_proxy_ms` with explicit scope. SDK count/rate-derived executor-prefill and whole-decode durations are labelled derived and never subtracted from app clocks to invent queue, tokenization or multimodal work. Raw incremental Session paths and cancelled/error outcomes keep native counters/rates/durations unavailable. Existing reply-footer character estimates keep their estimated label; callback counts never become token counts. See [Gemma processing measurements](gemma-processing-measurements-2026-10-11.md) for clock origins, safe alignment and coverage.

Acoustic dBFS, clipping, near-silent frames, VAD speech/non-speech energy contrast and noise-floor estimates describe captured samples. VAD contrast is **not** an SNR measurement or proof that the speaker was Justin; nearby people can also be classified as speech. Hardware audio preprocessing and microphone selection must be reported as observed configuration/support, rather than guaranteed denoising or speaker isolation.

## Statistics and comparison

JSON schema `jarvis.pipeline.benchmark`, version 2 (reads version 1), carries all-attempt and completed-turn summaries plus raw text-free turn/submission records. Each distribution reports total eligible rows, observed `n`, missing, minimum, maximum, mean, sample standard deviation and nearest-rank p50/p90/p95/p99. Missing samples never become zero. A p95 with fewer than 20 observations and p99 with fewer than 100 are explicitly marked as small-sample exploratory values. One run is not evidence of a stable tail latency.

Comparable groups separate channel, source/build, manufacturer/model, Android/API/ABI, selected model identity, runtime version, backend, model checksum when available, settings, power-saving state, explicit quiet/noisy environment and ASR/TTS warmth. Native-submission groups additionally separate purpose, model, warm/cold/unknown and terminal outcome. Dynamic battery/thermal observations remain on each row; inspect/filter them before making performance comparisons. An unknown field remains unknown rather than being inferred from a duration. The summary over all groups is descriptive and must not replace a result for one comparable setup.

Reserved correlation metadata (`parent_task_ids`, `reply_id`, `utterance_id`, `returned_utterance_id`, `result_utterance_id`, `result_captured_at_epoch_ms`, `linked_reply_turn_id`) is retained in each raw row's configuration, but excluded from group configuration and identity. A unique utterance/task link must not create a one-sample group. Every other configuration key, including capture scope/profile and model/backend settings, continues to separate comparisons.

Accuracy normalization is `nfkc-root-lower-alnum-whitespace-v1`: Unicode NFKC, locale-independent lower case, non-letter/non-number boundaries become spaces, repeated whitespace collapses. WER uses the resulting words. CER uses normalized Unicode code points excluding whitespace. Edit-distance ties prefer substitution, deletion, insertion. This policy is recorded so that another evaluator can reproduce the counts. The scorer rejects oversized input rather than silently truncating it.

WER and CER may exceed 100% when many extra words/characters are inserted. An empty reference has undefined per-item WER/CER; false-positive words/characters are reported separately. Corpus WER/CER use summed edit errors divided by summed reference units, including empty-reference insertions when the overall denominator is nonzero. Do not average per-turn percentages when reference lengths differ. Human quality verdicts have their own evaluated/unevaluated counts.

## Physical-device benchmark procedure

1. Pin one APK/source commit and record phone, Android version, model assets/runtime/backend, voice settings, battery and thermal status. Separate cold model initialization from reused/warm sessions; do not classify unknown startup as warm.
2. Use an explicit test corpus with verified reference text. Include ordinary questions, proper names, corrections, short follow-ups, ordered actions, silence and background-only speech. Retain the corpus identity/version and reference provenance; do not reconstruct what was spoken from a broken ASR transcript.
3. Run matched quiet and noisy conditions for both Whisper and Moonshine. Keep phone position/microphone route and intended utterances comparable. Background speakers need their own negative cases; louder input alone does not prove that the intended speaker was isolated.
4. Measure full turns, response latency and each native model submission separately. Repeat enough cases for the percentile being claimed, retain failed/cancelled attempts, and report evaluated accuracy sample counts alongside latency sample counts. Avoid treating a single chat as a controlled benchmark.
5. Add ASR reference text through explicit review, plus separate task/intent/factuality verdicts where appropriate. An accurate transcript can still produce an incorrect lookup, factual answer or action. Do not equate word matching with successful whole-task behavior.
6. Export JSON/CSV, preserve the release/run receipt and the original text-free report, and filter to one comparable setup. Publish the metric definition, sample count, conditions and limitations with any claim.

Restaurant speech improvement needs new phone recordings/tests with actual model weights. JVM normalization, export/statistics tests and release emulator journeys verify their own logic/integration boundaries; they cannot establish that Taco Bell noise is fixed.

In Voice Call's development diagnostics, open **Pipeline benchmarks**. Review the environment label per sample, add a verified reference for the temporarily retained original ASR, then score it. Add independent task/intent/factuality verdicts and export JSON or CSV. Hypotheses are intentionally absent after process restart or erasure; score within the current session. Records persist without conversation text. Accepted executor work, follow-up/interruption capture and report TTS have separate linked samples. The voice listener's scope label prevents its listening/pump duration from being mistaken for one accepted task's latency. Follow-up ASR fields describe its final capture attempt; retry/readiness observations are separate, and preconfirmation keyword/natural probes remain in interruption diagnostics rather than its final-ASR work counter.

## Resume evidence

Use measured, attributable results only. A supported claim names the tested Fold 6 configuration, recorded build/source, actual models/backends, conditions, sample count, p50/p95 response latency, and WER/CER against the verified corpus. Keep estimates explicit, for example “estimated decode throughput,” and distinguish end-to-end speech latency from model TTFT. Until enough physical-device benchmark records exist, describe the implemented benchmark instrumentation and evaluation methodology without claiming an accuracy improvement or publishing synthetic performance values.

## Conversation exports and durable retention

Tap the measurements beneath an assistant reply to open the full benchmark screen scoped to that conversation, with the selected reply expanded. Saved Voice Call details also provide a call-scoped benchmark button. **Copy JSON/CSV**, **Save JSON/CSV**, and **Share JSON/CSV** export the current scope; **Show all** switches to the complete retained archive. The existing Voice Call diagnostics Pipeline benchmarks entry remains available. No new conversation-menu button is required. Older imported records lacking conversation linkage remain in the all-records view.

Schema 2 records conversation/call/turn/submission identities, metric statuses (`observed`, `estimated`, `native`, `playback_proxy`, `unavailable`) and exported definitions. Absent metrics include an unavailable reason and stay null. CSV carries the status mapping too. The report remains redacted: no prompts, transcripts, supplied references, or microphone PCM. Reviewed WER/CER uses original Whisper/Moonshine text retained temporarily in process; Gemma's final caption is a separate transcription submission and is never mislabeled as Whisper accuracy.

The previous global 500-row/2-MiB rolling archive migrates to atomic per-attempt records under a 90-day retention policy. Records are correlated by conversation rather than silently displaced by activity in another conversation. Capacity is 128 MiB or 100,000 attempts; capacity refuses new records with a visible warning instead of evicting retained records. Disk failure keeps current observations in memory and displays an export-before-close warning. Explicit reset removes retained observations; migration completion prevents old data reappearing after reset. Expiry removes old records, so export before 90 days for permanent build comparisons.

Resource observations are bounded snapshots at turn start and finish: process CPU work, PSS, Java/native heap, battery and thermal state when supported, and the observation overhead itself. Process CPU includes concurrent work; PSS snapshots do not establish peak allocation, model-only CPU, energy use, or continuous thermal history. Audio encoder internals and genuine acoustic onset remain unavailable unless the underlying runtime/device exposes them. Stage clocks cover capture/endpoint, caption processing, context/memory, lookup/tools, model load/preparation/prefill, native submissions, TTS PCM/playback/queue gaps and cancellation/recovery. The final Gemma caption submission and its duration are separated from answer generation so it cannot inflate answer speed.

For mode comparisons, hold device/model/backend/capture route and utterance corpus fixed, then compare Moonshine text, Whisper text, Gemma audio with Whisper captions, and Gemma audio without captions. Keep caption work and the post-answer final transcription separately visible; compare speech-end to first answer playback alongside app send-to-first-raw-text timing. Whisper's overlapping live windows are bounded incremental display updates, not a reason to wait 30 seconds before answering.

Captions-off direct-audio trials skip recognizer preparation and all natural/keyword transcription verification probes as well as caption decoding. Keyword/VAD controls remain; their work is not falsely labeled ASR. The setting and interruption scope are recorded on each attempt.

## Visible conversation export and output totals

The conversation screen provides **Copy metrics** for one-tap copying of the current conversation's full redacted JSON report, even after a call ends, and **Metrics** for viewing/saving/sharing JSON or CSV. Reply footers retain the reply-focused entry. Copy snapshots the selected conversation; it never exports another thread's samples or silently truncates a report. Reports above the clipboard size limit direct the user to save/share instead.

JSON includes `replyMetrics` with per-saved-response TTFT, speech-end-to-playback proxy, estimated throughput and total estimated visible output tokens. Missing timings remain null, including older replies. Output totals use ceil(visible response UTF-16 character count / 4), including saved executor receipt text; they are labeled `~N tokens total`. They are not exact model token counts. Native input/output token counts remain separately attributable to each recorded SDK submission in JSON/CSV; caption, repair and tool submissions are not silently combined into a message count. Interrupted replies retain their visible-output estimate and incomplete status.

The [October 2 phone screenshot baseline](../benchmarks/2026-10-02/README.md) retains seven observed replies and original evidence for future comparisons. Build/model/input-route provenance is unknown for those screenshots, so this is descriptive history, not a controlled comparison.

Clipboard JSON uses compact serialization without dropping fields or samples; saved/shared JSON stays formatted. A five-attempt build-886 export supplied by Justin was 450,099 formatted bytes versus 269,840 compact bytes, demonstrating why copy should not spend the clipboard limit on whitespace.

### Build 1242 copy-parts dialog discovery repair

[Build 1242](https://github.com/battlesbudz/Jarvis-OS-V2/actions/runs/37948639302)
at `5e79bc22056eba7d9f80b5a512d6abf86138e388` passed 1904 release JVM tests,
both signed APK builds and the packaged native-page audit. Its API 30 and API 35
main instrumentation runs each executed 81 tests with exactly one failure:
test45 could not discover `pipeline_benchmark_part_copy`. Retained failure
hierarchies show the whole-report dialog and the visible “Copy part 1” button,
but no resource IDs anywhere in that separate dialog semantics tree.

The repair enables `testTagsAsResourceId` at `BenchmarkExportDialog`'s own
semantics root, matching the other benchmark dialogs. Activity-screen semantics
are not inherited across the dialog window. The existing test45 copy-part,
clipboard bounds, all-turn content, pending-picker ownership, cancellation,
repeated save/share and privacy assertions remain unchanged. No timeout, test
count or acceptance rule is relaxed. The failed run remains failed; actual
resource-ID discovery and all subsequent export assertions must pass in the
next exact-head full Android matrix. A visible button in a failure screenshot
alone does not establish a successful clipboard/save/share journey.
