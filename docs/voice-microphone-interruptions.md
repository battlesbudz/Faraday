# Voice microphone interruption behavior

VoiceCallService owns the recording monitor; JarvisRuntime owns voice execution with
application context. Activity destruction does not cancel voice jobs or close models.
The same call/context and existing playback operation survive microphone interruptions.

## Takeover and automatic rearm

Recording callbacks run on a dedicated handler thread. When another recording,
recorder silencing, communication mode, microphone mute, or a dictation request is
observed, Jarvis latches microphone priority and stops its AudioRecord directly.
ASR/TTS cleanup is not on the critical path for stopping hardware capture. The read
job remains the sole release owner and discards incomplete command audio.

Recorder registration, start, stop, release, and ownership snapshots share a lock.
The monitor counts currently recording Jarvis inputs, rather than subtracting an
independently changing allocation counter. Android anonymizes other app identities;
external counts remain observations, not proof of which app requested the mic.

The monitor checks recording state every two seconds and on recording changes,
without creating or starting a recorder. External activity holds priority indefinitely.
Rearm requires our recorder to be fully released and the microphone to remain clear
for at least three seconds. Poll scheduling can make the observed delay longer.
A failed short attempt or unused button reservation also receives this quiet interval.
A new request or a brief recorder restart resets it.

Passive mode resumes wake detection. An active call retains its ID and context and
resumes command capture without another wake phrase; inactivity timing starts after
availability returns. Generation/playback is not rerun: AudioTrack pauses without
flushing, and the existing bounded queues resume. No tool action is repeated.
Explicit Pause and Stop are still authoritative.

## Keyboard microphone-button requests

The previous accessibility component name is restored so an enabled helper can be
reconnected by Android. Check Voice settings → Enable keyboard microphone handoff
if Android disabled it when the previous APK removed the service.

The helper receives only click events. It checks the clicked control's accessibility
label/resource ID in the currently selected input method. Recognized microphone
controls request the same priority latch immediately. It does not inspect window
roots, typed text, keyboard visibility, or ordinary keyboard keys. Opening Messenger
or leaving the keyboard on screen does not itself reserve the microphone.

The initial matcher covers explicit English microphone/voice-input/voice-typing
labels and specific microphone resource IDs. Controls with different labels/IDs,
controls hosted outside the selected keyboard, or keyboards that emit no accessible
click may only use the recording-callback path. Click delivery is not an Android
advance audio-ownership handshake; first-attempt success must be tested on the phone.
Do not infer success solely from a passing policy test or expand this to arbitrary taps.

Diagnostics distinguish `keyboard_microphone_button`, `capture_detected_contention`,
`recording_state`, immediate `capture_priority_stop`, final `capture_released`, and
held/free recording snapshots. Control labels, IDs and typed text are never logged.

## Verification

JVM regressions cover the reported 60 ms external attempt / 153 ms Jarvis release,
long recordings, recorder gaps, teardown delays, unused requests, immediate hardware
stop before runtime notification, acquisition exclusion, ownership snapshot ordering,
and rejection of ordinary typing / other apps / editable fields by the button matcher.
Android CI builds/tests debug and signed release APKs and checks native packaging.

Phone acceptance (required to establish the actual keyboard handoff):
- Start a call, open Messenger and type normally: Jarvis stays active.
- Tap keyboard dictation once: it receives speech without a microphone-in-use error.
  Diagnostics should show the button request or recording-state request and hardware stop.
- Dictate for over 20 seconds: Jarvis must not reopen capture during dictation.
- Stop dictation, leave the keyboard visible, and wait: the same call resumes.
- Repeat in passive mode, during playback, with Messenger voice messages, after screen
  locking, and after Activity recreation. Repeat several handoffs in the same session.
- Pause/Stop during the handoff: completion of dictation must not override that choice.

Empty streaming and batch ASR results remain a separate investigation.
