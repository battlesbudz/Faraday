# LFM3-Omni device proof

Run these steps on Justin's target phone, using the exact integrated build under review. Do one step at a time. Record the PASS observation verbatim before moving on. On FAIL, stop and report the step number, the observed text/state, and the build identifier; do not continue on a broken state.

1. [ ] **Install the build.** Install the provided APK and launch Faraday.
   - **PASS:** Faraday opens to its normal conversation screen without a crash, install warning, or model-runtime error.
   - **FAIL:** Installation is rejected, launch crashes, or an error/toast reports that the app or model runtime could not start. Report the visible error and build identifier.

2. [ ] **Download LFM3-Omni-2B completely.** In the model browser, download the Omni entry and wait for completion. Confirm the four model payloads are present and verified: `LFM3-Omni-2B_int8.litertlm`, `LFM3-Omni-2B_depth_frame2_f32.tflite`, `LFM3-Omni-2B_frame_embed_f32.tflite`, and `LFM3-Omni-2B_codec_decoder_f12_f32.tflite`. Confirm the required `codec_window_f32.npy` companion is also present if the build packages it separately.
   - **PASS:** The model browser marks Omni downloaded/ready, reports no missing or checksum-failed companion, and the download does not remain in a partial/in-progress state.
   - **FAIL:** Any payload is missing, partial, rejected, or shown as ready despite a missing companion. Report the exact filename and displayed state.

3. [ ] **Select Omni.** Select LFM3-Omni-2B as the active model.
   - **PASS:** The model browser and conversation header both identify LFM3-Omni-2B as selected, with no load error.
   - **FAIL:** Selection reverts, a different model is shown, or the runtime reports a load/space/memory error. Report the displayed model and exact error.

4. [ ] **Start a voice call.** Open a new voice call from the selected Omni conversation.
   - **PASS:** The call screen opens, microphone capture is active, and the call remains open without a crash or fallback to Whisper/Piper/Gemma.
   - **FAIL:** The call does not open, capture is unavailable, or the app reports/falls back to another model or speech pipeline. Report the visible state/error.

5. [ ] **Ask one spoken question.** Say: “What is two plus two?” and wait for the spoken answer.
   - **PASS:** The call produces audible speech answering “four” (allowing ordinary wording), and the answer is visible in the call transcript. Record the diagnostics `runtimePath` and `firstSoundLatencyMs` for this turn.
   - **FAIL:** No audible answer, silence only, unrelated answer, transcript/audio mismatch, or a call error. Report the transcript, whether any sound played, and the two diagnostic fields (or that either is absent).

6. [ ] **Interrupt during the answer.** During the next spoken answer, speak over it: “Stop. What is three plus five?” before the answer finishes.
   - **PASS:** The current audio stops, the interruption is captured without its first words being clipped, and the assistant answers “eight” (allowing ordinary wording). Diagnostics contain a `bargeIn` event for the accepted interruption and its stop latency.
   - **FAIL:** Playback continues over the user, stops on its own voice without user speech, the interruption is clipped/lost, or the answer does not address the interruption. Report which occurred and the corresponding `bargeIn` diagnostic event (or its absence).

7. [ ] **Run one voice tool request.** Ask by voice for one available, safe tool action that has no external side effect (for example, a read-only device/status lookup); follow any normal approval prompt.
   - **PASS:** The call shows the normal approval flow if required, completes exactly one tool request, and speaks an answer grounded in that tool's returned result. Report the tool name and result shown.
   - **FAIL:** No tool call is made, a tool is spoken as ordinary text, an approval gate is bypassed, the tool runs more than once, or the spoken answer does not use its result. Report the request, gate state, and tool result/diagnostics.

8. [ ] **End the call.** Use End Call and wait for teardown to settle.
   - **PASS:** The call closes, playback and microphone capture stop, and the conversation remains available without a stuck-call indicator.
   - **FAIL:** Audio/capture continues, the UI remains in-call, the app crashes, or call teardown reports an error. Report the visible state and last call diagnostic event.

9. [ ] **Start a new conversation and verify clean state.** Create a new conversation while Omni remains selected; inspect its empty transcript, then ask: “What did I ask you before this conversation?”
   - **PASS:** The new transcript starts empty, the new turn is recorded under the new conversation, and the assistant does not recall the prior call's questions or tool result. Report both conversation identifiers and the answer.
   - **FAIL:** Prior transcript/tool state appears in the new conversation, the answer relies on the previous call, or the runtime cannot start a fresh turn. Report both conversation identifiers and the visible carried-over state.

Attach the exported per-conversation Copy Diagnostics text for the Omni call. Include any rollover records if the call exercised a context rollover; absence of rollover is expected for this short proof.
