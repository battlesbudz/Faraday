# Recorded speech regression fixture

`librispeech-1089-134686-0000.wav` is a human audiobook recording from
[LibriSpeech test-clean](https://www.openslr.org/12/), not generated speech.
LibriSpeech is distributed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
Credit: Vassil Panayotov, Guoguo Chen, Daniel Povey and Sanjeev Khudanpur,
*LibriSpeech: An ASR corpus based on public domain audio books*, ICASSP 2015.
The mirror, source checksum, reference transcript, utterance identity, conversion
and resulting checksums are recorded in `manifest.json`.

The JVM tests feed this actual waveform through shipping `AudioTurnCapture`,
`ExternalSpeechGate.completePhrase()` and LiteRT-LM's native `Content` DTOs.
Their injected VAD/recognizer decisions establish byte retention and delivery;
they do not establish speech recognition or Gemma understanding.
`scripts/check_recorded_audio.py` separately executes the exact pinned Whisper
weights on Linux, scores the transcript and boundary words, and
preserves the raw native result. One clean speaker is a bounded regression
check, not a representative population or microphone/noise benchmark.

The independent reference line is preserved in `reference.txt`, extracted from
OpenSLR's original `test-clean.tar.gz`, member
`LibriSpeech/test-clean/1089/134686/1089-134686.trans.txt` (member SHA-256 in the
manifest). A second identified FLAC mirror has the same SHA-256 as Narsil's
`1.flac`, confirming the utterance identity independently of model output.

The 2026-10-03 Linux CPU baseline with the exact pinned weights scored 2/28 word
errors for Whisper and 1/28 for Moonshine on both the original recording and
its one-second quiet extension. Both first and last words were correct.
`flour`/`flower` and Whisper's `fattened`/`faten` remain counted as errors.
The current regression budget keeps one additional interior word error: maximum
3/28 for Whisper. Moonshine's historical 2/28 limit and recorded baseline are
retained here as history; that retired runtime is no longer an active gate.
First and last words must both be correct
regardless of overall WER. Raw text and native output are always retained;
`Mr.`/`mister` normalization is orthographic only and does not affect this clip.
These numbers are fixture calibration, not population accuracy or device latency.
