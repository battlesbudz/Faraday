// Copyright (c) 2024-2026, Daily; portions derived from Hugging Face Transformers.
// Modified for Jarvis: bounded C++ mixed-radix FFT port. BSD-2-Clause / Apache-2.0.
// See third_party/smart-turn/NOTICE.md and pinned independent reference goldens.
#include "whisper_features.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace jarvis::smartturn {
namespace {
constexpr double kPi = 3.14159265358979323846;
double Mel(double hz) {
  return hz < 1000 ? 3 * hz / 200 : 15 + std::log(hz / 1000) * 27 / std::log(6.4);
}
double Hertz(double mel) {
  return mel < 15 ? 200 * mel / 3 : 1000 * std::exp(std::log(6.4) * (mel - 15) / 27);
}
void CheckCancelled(const std::atomic<bool>* flag) {
  if (flag && flag->load(std::memory_order_relaxed)) throw std::runtime_error("cancelled");
}
}  // namespace
WhisperFeatures::WhisperFeatures() {
  for (size_t i = 0; i < 400; ++i) {
    window_[i] = 0.5 - 0.5 * std::cos(2 * kPi * i / 400);
    roots_[i] = std::polar(1.0, -2 * kPi * i / 400);
  }
  std::array<double, 82> hz{};
  for (size_t i = 0; i < hz.size(); ++i) hz[i] = Hertz(Mel(8000) * i / 81);
  for (size_t m = 0; m < 80; ++m) {
    for (size_t bin = 0; bin <= 200; ++bin) {
      const double f = bin * 40.0;
      const double triangle = std::max(0.0, std::min((f - hz[m]) / (hz[m + 1] - hz[m]),
                                                   (hz[m + 2] - f) / (hz[m + 2] - hz[m + 1])));
      if (triangle > 0) filters_[m].push_back({bin, triangle * 2 / (hz[m + 2] - hz[m])});
    }
  }
}
// Cooley-Tukey decomposition 400 = 4 * 4 * 5 * 5. Root table is fixed-size;
// scratch is reused only after each recursive child's output has completed.
void WhisperFeatures::Fft(const std::complex<double>* in, size_t stride,
                          std::complex<double>* out, size_t n) {
  if (n == 1) { out[0] = *in; return; }
  const size_t radix = n % 4 == 0 ? 4 : 5;
  const size_t sub = n / radix;
  for (size_t j = 0; j < radix; ++j) Fft(in + j * stride, stride * radix, out + j * sub, sub);
  for (size_t k = 0; k < n; ++k) {
    std::complex<double> sum{};
    for (size_t j = 0; j < radix; ++j) sum += out[j * sub + k % sub] * roots_[(j * k * (400 / n)) % 400];
    scratch_[k] = sum;
  }
  std::copy_n(scratch_.begin(), n, out);
}
std::vector<float> WhisperFeatures::Compute(const float* samples, size_t count,
                                            const std::atomic<bool>* cancelled) {
  if (!samples || count == 0 || count > kSamples) throw std::invalid_argument("invalid bounded audio");
  CheckCancelled(cancelled);
  std::vector<float> x(kSamples, 0.0f);
  double sum = 0;
  for (size_t i = 0; i < count; ++i) {
    const float value = samples[i];
    if (!std::isfinite(value) || value < -1 || value > 1) throw std::invalid_argument("invalid PCM sample");
    x[kSamples - count + i] = value;
    sum += value;
  }
  // Reference normalizes padded float32 first, then promotes STFT to float64.
  // Accumulate in double to avoid architecture-dependent float reduction order;
  // round at the same arithmetic boundaries and enforce tolerance with goldens.
  const float mean = static_cast<float>(sum / kSamples);
  double squares = 0;
  for (float value : x) { const float delta = value - mean; squares += static_cast<float>(delta * delta); }
  const float variance = static_cast<float>(squares / kSamples);
  const float scale = std::sqrt(variance + 1e-7f);
  for (float& value : x) value = (value - mean) / scale;
  std::vector<float> features(kFeatures);
  std::array<std::complex<double>, 400> frame{}, spectrum{};
  std::array<double, 201> power{};
  float maximum = -std::numeric_limits<float>::infinity();
  // Centered reflect padding, periodic Hann, hop 160; omit trailing frame 800.
  for (size_t t = 0; t < 800; ++t) {
    CheckCancelled(cancelled);
    for (int j = 0; j < 400; ++j) {
      int index = static_cast<int>(t * 160) + j - 200;
      if (index < 0) index = -index;
      if (index >= static_cast<int>(kSamples)) index = 2 * static_cast<int>(kSamples) - 2 - index;
      frame[j] = static_cast<double>(x[index]) * window_[j];
    }
    Fft(frame.data(), 1, spectrum.data(), 400);
    for (size_t bin = 0; bin <= 200; ++bin) power[bin] = std::norm(spectrum[bin]);
    for (size_t m = 0; m < 80; ++m) {
      double energy = 0;
      for (const auto& weight : filters_[m]) energy += weight.weight * power[weight.bin];
      const float log = static_cast<float>(std::log10(std::max(1e-10, energy)));
      features[m * 800 + t] = log;
      maximum = std::max(maximum, log);
    }
  }
  // Whole-window normalization/global clamp preclude naive rolling feature reuse.
  for (float& value : features) value = (std::max(value, maximum - 8.0f) + 4.0f) / 4.0f;
  return features;
}
}  // namespace jarvis::smartturn
