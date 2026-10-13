# Decoder, metadata and cancellation checks

Run against the checksum-pinned ORT 1.27.1 host SDK already used by
scripts/build_sherpa.py and JDK JNI headers:

    python3 app/src/main/cpp/whisperqnn/tests/verify_decoder.py \
      --ort-root /path/to/ort-host \
      --jni-include "$JAVA_HOME/include" \
      --receipt /tmp/whisper-qnn-decoder-receipt.json

CXX selects the compiler; CXXFLAGS adds host flags. No downloads occur in the
test. It compiles all three production C++ translation units with warnings as
errors and tests binary16 finite roundtrips, rounding boundaries, all 199 mask
and position steps, forced English transcription/no-timestamp prefix, ordinary
text/EOS-only greedy selection, maximum output bounds, malformed logits, exact
by-name metadata (including reordered declarations), and K/V cache shapes.

A tiny weight-free ONNX Identity graph runs in the existing host ORT to check the
actual metadata adapter and actual RunOptions.SetTerminate behavior. Tests also
cover cancellation before admission, 250 Start/Cancel races, retained JNI handle
lifetime during close, and diagnosed initialization failure when QNN is absent.
The CPU Identity fixture is test-only. Production explicitly selects a QNN NPU
device and forbids ONNX Runtime CPU execution-provider fallback.

The test includes private implementation code to exercise the real adapters.
Do not add tests to the app JNI target; only the three production .cc files in
the parent directory belong there. The host never copies/shifts self caches:
each next cache comes from the graph's named output. Cancellation permanently
invalidates a native session; close and recreate before another utterance.

Host checks do not verify physical HTP execution, the compatibility of the
precompiled context with ORT 1.27.1, Android JNI loading, acoustic accuracy or
device latency. CPU-EP execution success cannot establish NPU execution.

The fake_ep_factory.cc fixture builds a test-only shared library advertising no
NPU and no executable provider. A retained real OrtEnv first demonstrates that
provider registration survives destruction of another Env alias. Three failed
production Session creations then must all report no NPU, proving the rollback
unregisters after discovery failure and does not poison retries. This fixture
must never be packaged or treated as QNN/device inference evidence.
