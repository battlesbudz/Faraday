# Smart Turn third-party notices

This support document was reconstructed after the 9 October 2026 workspace
rollback and receives fresh review. It is not the missing original notice.

- Smart Turn source: pipecat-ai/smart-turn at
  4786657e242dfe77dd138699ac564ee074a2a543. Copyright (c) 2024–2025, Daily.
  BSD 2-Clause; see smart-turn-BSD-2-Clause.txt.
- Model: pipecat-ai/smart-turn-v3 at Hugging Face revision
  f766f81d3cfdf7737ac64aad813d91bbfd56bf93. Its pinned model card declares
  BSD 2-Clause. The model repository's LICENSE endpoint is absent; the upstream
  project's BSD text is retained above. Model weights are not distributed here.
- Reference frontend: Pipecat at 7597e0c2f84fc05a31dd636daa9d91bb8405e9ff,
  src/pipecat/audio/turn/smart_turn/_whisper_features.py.
  Copyright (c) 2024–2026, Daily. BSD 2-Clause; see pipecat-BSD-2-Clause.txt.
  The unmodified reference includes portions derived from Hugging Face
  Transformers, Copyright 2022 The HuggingFace Inc. team; Copyright 2023 The
  HuggingFace Inc. team and the librosa & torchaudio authors, Apache License 2.0.
  See Apache-2.0.txt and the reference file's complete attribution header.
- Jarvis's C++ frontend is a port of the pinned reference math and retains its
  notices. It uses normalized, left-padded/recent eight-second 16 kHz input,
  Hann-windowed 400-point FFT, Slaney filters and the global log-mel clamp.
- Existing ONNX Runtime 1.27.1 and Sherpa runtime licenses remain in the existing
  distribution. This adds no replacement or second native runtime.
- Public LibriSpeech test input retains its existing CC-BY-4.0 attribution at
  app/src/test/resources/recorded-audio/manifest.json. Synthetic fixtures contain
  no user audio. Test fixtures and model weights are not production audio assets.
