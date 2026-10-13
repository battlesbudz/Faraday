# Passive Jarvis sessions

> Historical reference: Kokoro and Paul were removed on 16 September 2026. Their commands, setup steps and experiment plans below are superseded by the [supported stack](supported-model-stack.md) and current pipeline. Retained measurements are historical evidence.

Current microphone ownership, background lifetime, and keyboard handoff behavior is
documented in [voice microphone interruptions](voice-microphone-interruptions.md).
That document supersedes the historical handoff and Activity-lifetime notes below.

Start Jarvis session in the visible app and allow microphone/notification permissions. The APK bundles the hash-verified 52 KB microWakeWord Hey Jarvis v2 model; no wake model download is needed. Moonshine remains the call transcriber; the retired Zipformer ASR selector is not restored.

The microphone feeds only the local keyword spotter while the UI/notification says “Waiting for Hey Jarvis”. No transcript, WAV, or Gemma request is created from passive audio. Say “Hey Jarvis”, wait for the short readiness beep and “Voice Call is listening — speak now”, then speak. The existing call pipeline uses Moonshine and in-memory WAV audio for Gemma, and speaks with the selected Kokoro/Pocket Paul voice. Goodbye or 20 seconds without recognized speech ends the saved call and returns to passive listening. Stop Jarvis session or the notification's Stop session action releases the microphone and stops the foreground service.

The session continues after Home and screen locking. Android's microphone privacy indicator appears while capture is active; the ongoing notification distinguishes preparation, passive listening, active listening, speech, and microphone sharing pauses. Swiping away the task, destroying its runtime Activity, force-stop, or process death ends this session; it does not silently restart microphone capture. Activity recreation resilience remains separate work.

Jarvis uses non-private VOICE_RECOGNITION capture. It checks active recordings and Android's silenced state, releases capture when another client appears, discards interrupted call audio, and waits for microphone availability before returning to passive listening. Reasoning/playback is interrupted if another app begins recording. It cannot hear the wake phrase while yielded. Device behavior must be verified with keyboard dictation, voice messages, phone calls and microphone privacy controls.

## Assistant setup

Use Set Jarvis as default assistant, open Digital assistant app in Android Settings, and select Jarvis. The button remains available during a session and reports whether the assistant role is held when Settings returns. Devices without the voice-input settings screen fall back to Default apps settings. Jarvis declares a VoiceInteractionService, session service and a real local recognition service. Validated app-launch commands can use the selected, system-bound assistant service. If it is not active, the existing notification fallback remains. Launch acknowledgement says “Opening”, not “Opened”; Android/OEM behavior still needs device validation. No accessibility or overlay permission is requested, and this does not grant privileged hardware hotword or concurrent recording access.

## Phone acceptance checks

1. Start session, press Home, speak unrelated sentences. No call is created and no answer is spoken.
2. Say Hey Jarvis, wait for the readiness beep, ask the battery percentage. Hear a reply without reopening Jarvis. Continue a second turn normally.
3. Say goodbye. Say unrelated speech, then Hey Jarvis again. Only the second wake starts a new saved call.
4. Use keyboard dictation and a voice-message recorder while passive, while Jarvis is listening, and while it speaks. The other app must receive audio; Jarvis must yield, discard partial input and return to passive mode after release.
5. Stop the session from the notification. The Jarvis mic indicator must disappear and Hey Jarvis must do nothing.
6. Select Jarvis as default assistant, start a session, press Home, wake Jarvis and ask Open Facebook. Verify Facebook visibly opens; a spoken launch acknowledgement alone is not proof.
7. Copy diagnostics. The report contains only the most recent call, retaining action records separately from audio events.

Passive diagnostics include microphone RMS, peak, and maximum detection score over each complete one-second window without storing audio or transcription. Cancellation and Activity destruction are recorded as call events. Preparation is labeled Preparing rather than Thinking; the full current state is displayed below the controls. A wake match alone does not prove command capture or app launch succeeded.

## microWakeWord

The native frontend and streaming TFLite Micro engine follow Home Assistant Android's implementation. See [provenance and license notices](microwakeword-notices.md). The previous Sherpa keyword models are removed from app storage; Sherpa remains for Kokoro/Pocket Paul and Silero. Wake audio is PCM16 mono at 16 kHz and is never transcribed or saved before detection. The UI reports microphone warm-up before the detector's initial suppression period finishes, then Waiting for Hey Jarvis.

Use **Test wake word** with the session stopped for a 30-second microphone-only test. It needs no Gemma, ASR, or TTS inference. After warm-up, say Hey Jarvis. A detection beeps and displays a passed result; otherwise Copy diagnostics includes actual input RMS/peak and model scores. Stop, leaving the voice screen, timeout, or errors releases the test microphone. Keep this short diagnostic test visible; the ordinary Jarvis session remains the background listening mode.

The new detector does not resolve the Android battery limitation or make microphone sharing simultaneous. Validate Home, screen locking, repeated wake after goodbye, microphone yielding, and notification stop on the actual phone.

## Native validation

Build the same frontend and engine on a host with `cmake -S app/src/main/cpp/microwakeword -B /tmp/mww-build` followed by `cmake --build /tmp/mww-build -j4`. Run `microwakeword_test <model.tflite> <16-kHz-mono-PCM16-file>`. Use at least four seconds of ambient audio before the first phrase for the initial warm-up. Android uses the same C++ files through JNI.

Initial generated speech checks recognized all three positive variants (Hey Jarvis, Hey comma Jarvis, and Hey Jarvis followed by a battery question). Ordinary greeting, Hey Google, and Jarvis without Hey were rejected. The synthetic Hey Travis clip falsely triggered (peak averaged score 0.978824); similar-name rejection and real-phone sensitivity remain device acceptance checks. No threshold changes were made to hide that limitation. These results do not establish phone microphone routing or real-world accuracy.


## Microphone handoff and voice controls (September 8 follow-up)

Android dictation requests routed to Jarvis's RecognitionService now claim priority
before acquiring the recorder. Wake/call capture releases its recorder, waits while
dictation owns priority, then returns to passive listening after recognition cleanup.
Other recognition engines continue to use Android recording/silencing detection.
Pause microphone is also available in the ongoing notification for keyboard providers
that refuse to request recording while another recorder is open.

The screen exposes Stop reply (keep the current call and rearm command capture), End
conversation (save and return to Hey Jarvis), Pause/Resume microphone, and Stop session.
The waveform uses measured input levels while listening. Setup, wake testing, voice
comparison and latest-call diagnostics are in Voice settings. The screen scrolls on
compact displays and large text. Typed runtime phase/status survives screen navigation;
Activity recreation is still a separate runtime-ownership limitation.

Phone acceptance: with Jarvis selected as assistant, arm Hey Jarvis, switch to a text
field and dictate through the keyboard; confirm text entry, then wake Jarvis again.
Repeat with the keyboard's own recognition provider. During a long reply tap Stop reply
and give a correction without another wake word. End a conversation and wake again.
Pause from the notification: verify the microphone indicator turns off once cleanup
finishes, then resume. Check the folded screen and large text. EYE VUE is a separate PR.


## Keyboard providers that refuse concurrent capture; fragmented hardware reads

Enable Voice settings → Enable keyboard microphone handoff → Jarvis keyboard
microphone handoff in Android Accessibility. This optional service checks interactive
window types, never window roots, typed text or event text. Jarvis releases capture
while any keyboard window is visible, including before the provider starts recording.
Closing the keyboard resumes passive wake listening after a 750 ms stability interval;
screen-off clears the keyboard hold. Android recording/silencing checks still apply.
Manual notification Pause mic remains available without enabling the helper.

AudioRecord nonblocking reads are assembled into complete 100 ms PCM chunks before
entering the 64-entry queue (6.4 seconds at 16 kHz mono PCM16), preserving every byte
across fragmented reads. A genuine overflow discards the incomplete command, saves
an interrupted call, and retries passive listening after cleanup, at most twice per
session without a successful turn. It never submits truncated audio to tools.

Phone checks: open keyboard while Hey Jarvis is armed, verify Jarvis shows keyboard
pause, dictate, close keyboard, then wake Jarvis. Repeat while a call is listening and
after several app launches. Lock the screen with the keyboard open and verify wake
listening returns. Disable the helper and verify manual pause still releases capture.


## Audible listening modes

Command capture readiness uses a louder two-pulse acknowledgement at cue gain 100
(previously 65 with a brief beep). A distinct lower cue sounds once an ended call has
returned to a ready wake detector: goodbye, inactivity, End conversation, or return
after a microphone interruption. It does not sound after ordinary response turns,
on initial session startup, or while waiting for another app to release the microphone.
Both cues use the existing media volume, serialize playback and release on cancellation.
Phone check with the screen off: Hey Jarvis → bright acknowledgement → conversation →
goodbye (or 20 seconds without recognized speech) → lower return-to-wake cue.


## Dictation-end rearm follow-up

Keyboard visibility and microphone reservation are now separate states. The helper
monitors Android recording callbacks plus a 250 ms poll while the Jarvis session is
armed, subtracting Jarvis's own recorders. Once dictation has stopped for 750 ms, it
releases the reservation even if the keyboard remains visible. A subsequent keyboard
tap reserves another attempt. Short recording gaps and panel replacements are debounced.
No text or node content is inspected. No polling continues after the Jarvis session stops.

Latest-call diagnostics now include a bounded 12-event keyboard handoff trace, including
visible, holding and external-recording transitions. This also works before any wake.
Manual Pause remains manual; this fix does not override an explicit user pause.

Phone acceptance: start Hey Jarvis, use dictation in another app, stop dictation but
leave its keyboard on screen, and wait for the detector to rearm without opening Jarvis.
Say Hey Jarvis, then test a second keyboard dictation on that same keyboard. If rearm
fails, copy diagnostics so holding/recording state can distinguish it from a lifecycle issue.


## Voice repetition guard

General voice replies now pass a phrase gate before TTS, captions, or voice-call
checkpoint publication. It rejects normalized echoes of the current user message,
exact/near-duplicate sentences from the latest assistant reply, and sentences repeated
within the current answer. Ordinary speech is released sentence by sentence; a detected
repeat triggers at most one read-only rewrite with the latest question and dialogue.
Rewrite tool calls are never executed. If no fresh answer survives, a short honest
fallback is used. Prompt instructions also emphasize answering follow-up decisions
rather than recapping the original idea. The old 32-character check could not catch
the long emotion-feature replies reported in diagnostics.

Verified phone-action result paths remain authoritative. Lexical matching does not
promise to detect every paraphrase or prohibit reuse of necessary topic words/facts.
The guard records suppression and rewrite diagnostics in the latest-call log.
Acceptance: discuss an idea, ask whether to implement it now, then narrow the question;
verify each reply answers the changed question without replaying the earlier paragraph.
