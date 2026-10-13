> **Historical call-display design record.** The earlier separate circular-screen
> and Moonshine-only descriptions below are superseded. Current UI uses the shared
> conversation transcript with a floating voice overlay, selectable recognition,
> and selectable direct Gemma audio with optional display-only Whisper captions.
> Browsing the chat retains the call; explicit End terminates it. See the
> [current audio contract](verification/voice-audio-and-metrics.md),
> [`ConversationScreen`](../app/src/main/java/com/battlesbudz/jarvis/v2/ui/ConversationScreen.kt)
> and [`VoiceNavigationPolicy`](../app/src/main/java/com/battlesbudz/jarvis/v2/voice/VoiceNavigationPolicy.kt).
> Historical designs and measurements below remain unchanged.

# Live voice display

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

The call screen has a circular waveform and a rolling caption, rather than the
full response transcript. The ASR menu is removed; Moonshine is the sole ASR.
Call histories and Copy diagnostics retain full transcripts and measurements.

Captions use AudioTrack's consumed frame position, not Gemma token arrival or
elapsed wall time. Each synthesized phrase is registered before its PCM is
written. Word positions are estimated using word length inside that phrase,
with a 180 ms audio-time lead. This is approximate timing, not forced alignment.
Future queued phrases are never displayed before playback reaches their start.
The window holds at most 32 words and highlights the newest visible word.

The speaking waveform uses 50 ms RMS envelopes of actual synthesized PCM.
Listening/thinking use a gentle decorative animation, not microphone metering.
Only envelopes for pending phrases and bounded recent text are retained; no
additional recordings or inference models are stored. Updates run every 40 ms.
The monitor is cancelled/joined before AudioTrack is released.

Validation: playback-clock tests cover unreached phrases, a stalled clock, and
bounded context. Android CI covers debug/release tests and builds. Device testing
should include short battery replies, long stories, ending mid-speech, and both
Kokoro and Pocket Paul; Bluetooth adds route latency not measured acoustically here.
