// Whisper ASR frontend port. See tests/README.md and third_party/smart-turn/NOTICE.md.
#pragma once

#include <array>
#include <atomic>
#include <complex>
#include <cstddef>
#include <vector>

namespace jarvis::whisperqnn {
inline constexpr size_t kSamples = 480000;
inline constexpr size_t kFeatures = 80 * 3000;

// One instance per serialized inference worker. The FFT scratch is instance-owned;
// callers must not invoke Compute concurrently on the same instance.
class WhisperFeatures {
 public:
  WhisperFeatures();

  // Mono 16 kHz FP32 PCM in [-1, 1], right-zero-padded to exactly 30 seconds.
  // No waveform mean/variance normalization, resampling, or truncation occurs.
  // Returns contiguous FP32 [80, 3000], indexed as mel * 3000 + frame.
  // Throws invalid_argument for null/empty/oversize/nonfinite/out-of-range input,
  // or runtime_error("cancelled") for cooperative cancellation. Does not publish
  // a partial result. The instance remains reusable after either exception.
  std::vector<float> Compute(const float* samples, size_t count,
                             const std::atomic<bool>* cancelled = nullptr);

 private:
  void Fft(const std::complex<double>* input, size_t stride,
           std::complex<double>* output, size_t n);

  std::array<double, 400> window_;
  std::array<std::complex<double>, 400> roots_;
  std::array<std::complex<double>, 400> scratch_;
  struct MelWeight {
    size_t bin;
    double weight;
  };
  std::array<std::vector<MelWeight>, 80> filters_;
};
}  // namespace jarvis::whisperqnn
