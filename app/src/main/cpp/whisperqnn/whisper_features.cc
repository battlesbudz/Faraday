// Copyright (c) 2024-2026, Daily; portions derived from Hugging Face Transformers.
// Copyright 2022 The HuggingFace Inc. team.
// Copyright 2023 The HuggingFace Inc. team and the librosa & torchaudio authors.
// Modified for Jarvis: isolated 30-second, unnormalized, right-padded ASR frontend;
// bounded mixed-radix FFT and cooperative cancellation. BSD-2-Clause / Apache-2.0.
// See tests/README.md and third_party/smart-turn/NOTICE.md for sources/licenses.
#include "whisper_features.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace jarvis::whisperqnn {
namespace {
constexpr size_t kFftSize = 400;
constexpr size_t kHopSize = 160;
constexpr size_t kFrames = kSamples / kHopSize;
constexpr size_t kMels = 80;
constexpr double kPi = 3.14159265358979323846;
static_assert(kFeatures == kMels * kFrames);

double Mel(double hz) {
  return hz < 1000 ? 3 * hz / 200 : 15 + std::log(hz / 1000) * 27 / std::log(6.4);
}

double Hertz(double mel) {
  return mel < 15 ? 200 * mel / 3 : 1000 * std::exp(std::log(6.4) * (mel - 15) / 27);
}

void CheckCancelled(const std::atomic<bool>* flag) {
  if (flag && flag->load(std::memory_order_relaxed)) {
    throw std::runtime_error("cancelled");
  }
}
}  // namespace

WhisperFeatures::WhisperFeatures() {
  for (size_t i = 0; i < kFftSize; ++i) {
    window_[i] = 0.5 - 0.5 * std::cos(2 * kPi * i / kFftSize);
    roots_[i] = std::polar(1.0, -2 * kPi * i / kFftSize);
  }
  std::array<double, kMels + 2> hz{};
  for (size_t i = 0; i < hz.size(); ++i) {
    hz[i] = Hertz(Mel(8000) * i / (kMels + 1));
  }
  for (size_t m = 0; m < kMels; ++m) {
    for (size_t bin = 0; bin <= kFftSize / 2; ++bin) {
      const double frequency = bin * 40.0;
      const double triangle = std::max(
          0.0, std::min((frequency - hz[m]) / (hz[m + 1] - hz[m]),
                        (hz[m + 2] - frequency) / (hz[m + 2] - hz[m + 1])));
      if (triangle > 0) {
        filters_[m].push_back({bin, triangle * 2 / (hz[m + 2] - hz[m])});
      }
    }
  }
}

// Cooley-Tukey decomposition 400 = 4 * 4 * 5 * 5. Each child's output has
// completed before the shared instance scratch is reused by its parent.
void WhisperFeatures::Fft(const std::complex<double>* input, size_t stride,
                          std::complex<double>* output, size_t n) {
  if (n == 1) {
    output[0] = *input;
    return;
  }
  const size_t radix = n % 4 == 0 ? 4 : 5;
  const size_t sub = n / radix;
  for (size_t j = 0; j < radix; ++j) {
    Fft(input + j * stride, stride * radix, output + j * sub, sub);
  }
  for (size_t k = 0; k < n; ++k) {
    std::complex<double> sum{};
    for (size_t j = 0; j < radix; ++j) {
      sum += output[j * sub + k % sub] * roots_[(j * k * (kFftSize / n)) % kFftSize];
    }
    scratch_[k] = sum;
  }
  std::copy_n(scratch_.begin(), n, output);
}

std::vector<float> WhisperFeatures::Compute(const float* samples, size_t count,
                                            const std::atomic<bool>* cancelled) {
  if (!samples || count == 0 || count > kSamples) {
    throw std::invalid_argument("invalid bounded audio");
  }
  CheckCancelled(cancelled);
  std::vector<float> audio(kSamples, 0.0f);
  for (size_t i = 0; i < count; ++i) {
    if (i % 4096 == 0) CheckCancelled(cancelled);
    const float value = samples[i];
    if (!std::isfinite(value) || value < -1 || value > 1) {
      throw std::invalid_argument("invalid PCM sample");
    }
    audio[i] = value;
  }

  std::vector<float> features(kFeatures);
  std::array<std::complex<double>, kFftSize> frame{}, spectrum{};
  std::array<double, kFftSize / 2 + 1> power{};
  float maximum = -std::numeric_limits<float>::infinity();
  // Centered reflect padding excludes the boundary sample. Whisper removes the
  // last STFT column: calculate frames 0..2999, not the trailing frame 3000.
  for (size_t t = 0; t < kFrames; ++t) {
    CheckCancelled(cancelled);
    for (int j = 0; j < static_cast<int>(kFftSize); ++j) {
      int index = static_cast<int>(t * kHopSize) + j - static_cast<int>(kFftSize / 2);
      if (index < 0) index = -index;
      if (index >= static_cast<int>(kSamples)) {
        index = 2 * static_cast<int>(kSamples) - 2 - index;
      }
      frame[j] = static_cast<double>(audio[index]) * window_[j];
    }
    Fft(frame.data(), 1, spectrum.data(), kFftSize);
    for (size_t bin = 0; bin < power.size(); ++bin) {
      // HF's NumPy path stores the float64 FFT into complex64 before computing
      // power in float64. Preserve that observable rounding boundary.
      const double real = static_cast<float>(spectrum[bin].real());
      const double imag = static_cast<float>(spectrum[bin].imag());
      power[bin] = real * real + imag * imag;
    }
    for (size_t m = 0; m < kMels; ++m) {
      double energy = 0;
      for (const auto& weight : filters_[m]) {
        energy += weight.weight * power[weight.bin];
      }
      const float log = static_cast<float>(std::log10(std::max(1e-10, energy)));
      features[m * kFrames + t] = log;
      maximum = std::max(maximum, log);
    }
  }
  // One global maximum across all mel bins and retained time frames.
  for (size_t i = 0; i < features.size(); ++i) {
    if (i % 4096 == 0) CheckCancelled(cancelled);
    features[i] = (std::max(features[i], maximum - 8.0f) + 4.0f) / 4.0f;
  }
  CheckCancelled(cancelled);
  return features;
}
}  // namespace jarvis::whisperqnn
