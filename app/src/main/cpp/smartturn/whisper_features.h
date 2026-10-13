// Smart Turn Whisper frontend port. See third_party/smart-turn/NOTICE.md.
#pragma once
#include <array>
#include <atomic>
#include <complex>
#include <cstddef>
#include <vector>

namespace jarvis::smartturn {
constexpr size_t kSamples = 128000;
constexpr size_t kFeatures = 80 * 800;
// Own one instance on the shadow worker. No shared mutable FFT scratch or audio cache.
class WhisperFeatures {
 public:
  WhisperFeatures();
  // Recent 16 kHz mono PCM in [-1, 1], zero-left-padded, normalized over all 8 s.
  // Throws on invalid/nonfinite samples or cooperative cancellation.
  std::vector<float> Compute(const float* samples, size_t count,
                             const std::atomic<bool>* cancelled = nullptr);
 private:
  void Fft(const std::complex<double>* input, size_t stride,
           std::complex<double>* output, size_t n);
  std::array<double, 400> window_;
  std::array<std::complex<double>, 400> roots_;
  std::array<std::complex<double>, 400> scratch_;
  struct MelWeight { size_t bin; double weight; };
  std::array<std::vector<MelWeight>, 80> filters_;
};
}  // namespace jarvis::smartturn
