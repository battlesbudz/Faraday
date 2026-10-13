# D2 saved-recording host replay

These scripts reproduce the September 18 offline comparison. Use only the bounded,
trusted Jarvis D2 ZIPs (10 seconds, PCM16 mono 16 kHz). They do not measure phone
latency, endpointing, hardware AEC, scheduling, or speaker verification. The new
in-app saved-ZIP replay uses the actual Android adapters and bounded ZIP importer.

Install in a disposable Python virtual environment:

```sh
pip install moonshine-voice==0.1.5 sherpa-onnx==1.13.7 onnxruntime==1.23.2 numpy
```

Use the exact Moonshine SMALL_STREAMING files pinned in `AsrModelStore.kt` and the
Whisper base.en int8 files pinned in `RecognitionModelStore.kt`; verify the model
SHA256 values there before replay. No models or personal audio are committed.

Run each script with these same arguments (substitute your local directories):

```sh
python scripts/d2-replay/raw_bypass.py --input /path/to/zips --moonshine /path/to/moonshine --whisper /path/to/whisper --output /path/to/results
```

Repeat for `raw_native_gate.py`, `call_adapter.py`, and `whisper_control.py`.
Inputs match `*communication_speaker*.zip`. These host scripts read the first
microphone recording in each single-test ZIP; the app handles combined ZIPs too.

The raw scripts compare final-only and periodic updates. The call adapter script
ports bundled Silero framing, CaptureSpeechGate, and the current complete-phrase
ExternalSpeechGate: 1.2 seconds before onset and contiguous frames through the end
of the bounded recording. It does not implement live backlog, endpointing, model
rotation, or recovery. It is an approximation for isolating input gating, not a
replacement for Android acceptance. SDK private stream
handles and forced final update deliberately match the pinned Android 0.1.5
`stopStream` behavior; upgrading the SDK requires revisiting this harness.

Outputs preserve raw ASR results, including hallucinations on silence. Never use
them directly as evidence of an owner word or as permission to interrupt playback.
