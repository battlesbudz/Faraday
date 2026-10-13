# Per-reply latency

Assistant chat messages and saved Voice Call replies carry their own immutable
latency record. Tap the compact footer to expand measurements or copy only that
reply's timings. Old messages without timing metadata remain readable without
invented measurements. The record persists with the message, outside model input.

The summary reports GPU / Gemma 4 E2B, first-generation TTFT, estimated token speed
and count, and total reply processing. Multiple model calls are shown as separate
passes, including tool responses, verification, reference retries and repetition
repair. Prepared voice generations are explicitly marked; their generation time
occurred before the final request and is not charged again as live processing.

Expanded details include model load, lookup, first delivered text, model passes,
and remaining processing (context, tools, checks and scheduling). These are model
and app clocks, not an instrumented screen-render measurement. Voice records add
last detected speech to first non-silent reply audio, speech text to PCM/playback,
and estimated supply gaps after playback completes. This excludes the cached
filler. Voice synthesis overlaps generation, so the durations must not be summed.
The live voice screen retains its orb/captions; reply details are available in the
saved call transcript.

A 0.25-second model TTFT is a target, not a measured result of this change. Compare
the same warm model, prompt, context, modalities, tool definitions and device
conditions. TTFT excludes voice endpointing, recognition, loading and TTS. Tokens
and token speed in Jarvis are estimates because the current SDK returns text
fragments rather than exact token IDs.

Local verification: focused JVM tests cover missing values, JSON persistence,
prepared/multiple passes and late voice updates attached by reply ID. Full Android
compilation is checked by the audio-pr2 Android CI workflow; this local environment
cannot resolve the Kotlin 2.3.0 Gradle plugin. Publication to audio-pr2 was explicitly
authorized after the initial local review.
