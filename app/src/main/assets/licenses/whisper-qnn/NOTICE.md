# Optional Snapdragon Whisper backend

The app incorporates the QNN2.6.0 plugin and QAIRT2.50.0 precompiled-model runtime
subset for Hexagon v75 as object code, not a standalone SDK. LICENSE.pdf and
NOTICE.txt are copied byte-for-byte from the pinned public Qualcomm runtime AAR.
The plugin is MIT licensed (QNN-Plugin-MIT.txt). OpenAI Whisper and its tokenizer
use the included OpenAI-Whisper-MIT.txt. Downloaded Qualcomm Whisper-Base v0.64.0
model graphs are not part of the APK or Git repository.

The adapted native decoder's Qualcomm BSD3 notice is reproduced in
Native-NOTICE.md. The separate CPU frontend retains Daily/Hugging Face notices
and uses the adjacent smart-turn BSD2 and Apache2 license files.

Sources:
https://huggingface.co/qualcomm/Whisper-Base
https://github.com/onnxruntime/onnxruntime-qnn/tree/v2.6.0
https://github.com/qualcomm/ai-hub-models/tree/v0.64.0/src/qai_hub_models/models/templates/hf_whisper

This optional backend selects an actual QNN NPU and forbids CPU graph fallback.
Unavailable/incompatible devices or failed NPU sessions use an independently
verified CPU Whisper model. A requested option is not acceleration evidence.
