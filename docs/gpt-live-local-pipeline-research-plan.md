# GPT-Live-Inspired Local Voice Pipeline Research Plan

Date: 2026-10-04
Branch: audio-pr2
Scope: offline-first Jarvis OS V2 voice path, primarily validated on the Galaxy Z Fold 6.

## Objective

Tune Jarvis toward the user-visible behavior of GPT-Live-1 without claiming architectural parity with OpenAI's proprietary model. Optimize recognition reliability, perceived turn latency, continuous listening, natural endpointing, barge-in, backchannel handling, durable task state, and long-session stability.

## Published GPT-Live findings worth copying

OpenAI describes GPT-Live as:
- full duplex: it listens while speaking;
- continuous rather than rigid turn-by-turn;
- able to decide whether to listen, speak, pause, interrupt, or delegate;
- split between a latency-critical conversational path and asynchronous deeper reasoning/tools;
- able to keep backend work alive when speech is interrupted;
- capable of semantic rather than silence-only endpointing;
- able to expose transcripts, response text, and keyword biasing;
- stateful across a live session;
- evaluated with granular telemetry and shadow testing;
- prompted with a short conversational prompt while detailed procedures live outside the voice path.

Published reference points include 80.1% on Full Duplex Bench v1.5 and 0.798 s reported turn-taking latency. These are north-star references, not claims about Jarvis.

Sources:
- OpenAI: "How we built a realtime system for responsive voice AI in six months"
- OpenAI: "Build more natural voice experiences with GPT-Live-1 in the API"
- OpenAI API guides: GPT-Live, prompting, conversations, realtime VAD, realtime transcription
- OpenAI GPT-Live-1 model documentation

## Current Jarvis baseline

Jarvis already implements system-level full duplex: a call-owned microphone retains capture while Piper plays a reply, with bounded Moonshine/Whisper speech verification for natural barge-in and retained interruption audio. ASR, Gemma/LiteRT-LM and Piper remain separate stages. This is an implemented application-level capability; route reliability, acoustic echo rejection and latency still require matched device evidence. See the [current duplex contract](verification/voice-audio-and-metrics.md#system-level-full-duplex), including the captions-off direct-Gemma keyword path.

README phone measurements currently report:
- median TTFT: 740 ms;
- median first spoken word: 3.6 s;
- average generation: 32.8 tokens/s.

That gap suggests endpointing, orchestration, TTS startup/buffering, and serial stage boundaries deserve at least as much attention as Gemma generation speed.

## Target architecture

Preserve the existing capture/playback overlap and call-owned microphone handoff. Extend and validate it within the existing Pause/End, route and resource-ownership rules.

Mic -> AEC/noise handling -> acoustic VAD + streaming ASR -> partial transcript -> semantic turn/interruption classifier.

The classifier should distinguish:
- keep listening;
- completed turn;
- backchannel;
- interruption;
- new command;
- noise/background speech.

Final or sufficiently stable requests feed a warm Gemma session. Gemma output should stream into phrase/sentence chunking and then TTS as soon as a safe speakable chunk exists. Tools, Memory OS retrieval, and longer reasoning should run on a separate durable task lane so interrupting speech does not automatically destroy work.

Maintain two conversation views:
- speculative: mutable partial transcript/timing for UI and routing;
- authoritative: committed transcript/task record after sufficient evidence.

## Research hypotheses

### 1. Concurrency beats another model swap
Profile serialization before replacing models. Retain microphone capture, schedule bounded recognition without starving playback, keep Gemma warm, prepare routing from stable partials, stream generation to TTS, and minimize buffering.

### 2. Semantic endpointing improves latency and naturalness
Combine acoustic VAD with a cheap semantic completion classifier. Test FAST, BALANCED, and PATIENT profiles. Measure premature endpoints and unnecessary post-speech waiting.

### 3. Context-biased ASR improves real-world accuracy
Build a bounded bias package from recent transcript, active task/app, tool names, installed apps, permitted contacts, Memory OS retrieval, recent entities, and user vocabulary. Measure whether it improves entity accuracy without causing false substitutions.

### 4. Barge-in requires classification
During TTS, continuously process microphone input after echo suppression. Do not treat every speech-like event as interruption. Distinguish backchannels such as "yeah" from commands such as "stop" or "no, I meant...".

### 5. Playback, generation, tools, and conversation need separate state
A barge-in may stop playback and generation while a running read/tool task continues unless the new user intent cancels it.

## Phase 0: frozen baseline

Create a locked reproducible corpus:
- quiet close-talk and far-field;
- restaurant/crowd noise;
- TV/background speaker;
- vehicle noise;
- music;
- Piper self-echo;
- user barge-in while Piper speaks;
- backchannels while Piper speaks;
- deliberate mid-sentence pauses;
- disfluencies/restarts;
- proper nouns, app/contact names, numbers and alphanumerics.

Record exact build SHA, models, settings and audio configuration.

Metrics:
- WER/CER;
- semantic and named-entity error rate;
- command-intent accuracy;
- first useful/stable ASR partial;
- endpoint latency and premature endpoint rate;
- TTFT;
- first TTS PCM;
- first audible response;
- interruption detection and playback-stop latency;
- false interruption rate;
- thermal and sustained-session degradation.

Report p50/p90/p95, not averages alone.

## Phase 1: complete critical-path instrumentation

Timestamp with a monotonic clock:
mic first frame, speech start, first/stable ASR partial, speech end, endpoint decision, final transcript, routing start/end, Gemma submit/TTFT, first speakable text chunk, TTS start/first PCM, playback queue/first-audible estimate, barge-in speech start/decision/playback stop, generation cancel, tool start/end.

Derive every stage contribution to speech-end -> first-audible latency.

## Phase 2: ASR experiments

Compare Moonshine and Whisper under identical audio while varying:
- chunk/window size;
- overlap;
- prefix retention;
- endpoint delay;
- noise suppression;
- AEC;
- contextual vocabulary;
- recent-transcript context;
- finalization policy.

Do not choose by WER alone. Use an accuracy/latency/energy Pareto comparison.

## Phase 3: semantic endpointing

Use acoustic VAD as evidence, not the sole endpoint authority. Add a lightweight semantic completion classifier using partial transcript, pause duration, punctuation/last-token features and confidence.

Measure premature endpoint rate and delay after genuinely completed speech. Avoid putting full Gemma generation in the blocking endpoint path if a smaller classifier works.

## Phase 4: continuous capture and echo control

Validate the already-implemented capture/playback overlap across supported routes. Measure microphone continuity and bounded recognition availability during TTS, rather than assuming continuous unrestricted ASR decoding. Evaluate Android AcousticEchoCanceler and NoiseSuppressor where supported. Test Piper playback with no user speech and require zero tool executions/self-trigger commands on the fixed echo corpus while retaining intentional barge-in.

## Phase 5: interruption/backchannel classifier

Label examples of acknowledgements, stop/wait commands, corrections, new questions, coughs, other speakers, TV speech, and Piper leakage.

Start with the cheapest reliable approach: rules + duration + ASR text. Escalate to a small learned classifier only if benchmark evidence requires it.

Track interruption recall, false interruption rate, playback-stop latency, and backchannel preservation.

## Phase 6: pipeline concurrency

Profile current serialization, then test:
- resident/warm Gemma session;
- cached stable prompt/context prefix;
- safe early routing from stable ASR partials;
- speculative inference only when cancelable;
- streaming Gemma text into phrase segmentation;
- incremental TTS;
- small PCM playback buffer.

Never speculatively execute irreversible tool actions.

## Phase 7: conversational lane vs task lane

Conversation lane: microphone, ASR, endpointing, immediate Gemma response, TTS.

Task lane: tools, Memory OS retrieval, expensive reasoning/search, long-running work.

Task results return asynchronously to conversation state. Preserve task IDs through speech interruption.

## Phase 8: prompt/context reduction

Measure exact prompt size per turn. Keep a small stable persona/conversation prompt. Retrieve only relevant history/memory and tool schemas. Keep diagnostics and long procedures out of every Gemma prompt.

Compare TTFT plus repetition/hallucination rates before and after.

## Phase 9: long-session reliability

Run scripted 5, 15, 30, and 60 minute sessions. Measure memory growth, thermal throttling, ASR drift, context growth, TTFT/tokens-per-second/first-spoken-word drift, duplicate responses, task leakage, and call-state recovery.

Prefer bounded live context plus Memory OS retrieval over indefinitely expanding prompts.

## Phase 10: shadow evaluation

Before a new endpoint/interruption policy controls production behavior, run it over captured/live audio in read-only shadow mode where practical. Log its decisions while the current policy remains authoritative. Promote only after corpus results and Fold 6 trials agree.

## Performance targets

Immediate:
- median clear-command speech-end -> endpoint <= 300 ms;
- median interruption speech-start -> playback stop <= 250 ms;
- zero tool execution from the fixed Piper self-echo corpus;
- no WER regression versus the current best configuration.

Near term:
- median speech-end -> first audible response <= 1.5 s for short local turns;
- p95 <= 2.5 s;
- >=95% intentional interruption recall;
- <=2% false interruption rate on the labeled non-interruption corpus;
- materially lower noisy-environment WER than baseline.

Research north star:
- approach the published 0.798 s GPT-Live-1 turn-taking latency on short locally answerable commands without sacrificing accuracy;
- sustain full-duplex capture/interruption behavior for 30-minute sessions without material latency drift;
- achieve statistically preferred conversational flow versus the frozen Jarvis baseline.

## Experimental discipline

For every optimization:
1. state the hypothesis;
2. preserve the baseline;
3. change one primary variable;
4. run the locked corpus;
5. compare p50/p90/p95 plus error metrics;
6. inspect scenario-specific regressions;
7. validate on the Fold 6;
8. keep the change only when evidence supports it.

Benchmark JSON should include commit/build, device, ASR/Gemma/TTS model and runtime, audio settings, experiment flags, thermal/battery state when available, raw turn metrics, and aggregates.

## Priority

1. Finish latency instrumentation.
2. Freeze the benchmark corpus/baseline.
3. Fix self-echo and prove continuous capture.
4. Test contextual ASR.
5. Implement semantic endpointing.
6. Implement interruption/backchannel classification.
7. Optimize concurrent generation/TTS.
8. Separate conversational/task lanes.
9. Minimize prompt/context.
10. Run long-session and shadow evaluations.

## Important limitation

GPT-Live-1 weights and enough implementation detail to reproduce its model locally have not been released. Jarvis should not claim to implement GPT-Live or have architectural parity.

The reproducible research contribution is applying the published engineering principles—continuous media flow, semantic interaction decisions, stateful low-latency processing, asynchronous delegation, contextual recognition, speculative/final state separation, and rigorous end-to-end evaluation—to an offline Android stack.

## Definition of success

Success means Jarvis is demonstrably better than its own frozen baseline: lower recognition errors, fewer premature responses, faster audible replies, reliable natural barge-in, no self-triggering, durable task state through interruptions, stable long sessions, and exported evidence for every claimed improvement.
