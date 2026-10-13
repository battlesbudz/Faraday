// Host-only probe. No Android, QNN, model files, or private audio required.
#include "whisper_features.h"

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
using jarvis::whisperqnn::WhisperFeatures;
using jarvis::whisperqnn::kFeatures;
using jarvis::whisperqnn::kSamples;

void Require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

void Reject(WhisperFeatures& frontend, const float* audio, size_t count) {
  bool rejected = false;
  try {
    frontend.Compute(audio, count);
  } catch (const std::invalid_argument&) {
    rejected = true;
  }
  Require(rejected, "invalid audio was not rejected with invalid_argument");
}

void SelfTest() {
  WhisperFeatures frontend;
  const float zero = 0;
  Reject(frontend, nullptr, 1);
  Reject(frontend, &zero, 0);
  Reject(frontend, &zero, kSamples + 1);  // Must reject before reading beyond zero.
  for (const float value : {std::numeric_limits<float>::quiet_NaN(),
                           std::numeric_limits<float>::infinity(),
                           -std::numeric_limits<float>::infinity(),
                           std::nextafter(1.0f, 2.0f),
                           std::nextafter(-1.0f, -2.0f)}) {
    Reject(frontend, &value, 1);
  }
  std::vector<float> audio(kSamples, 0.0f);
  audio.back() = std::numeric_limits<float>::quiet_NaN();
  Reject(frontend, audio.data(), audio.size());
  audio.back() = 0;

  std::atomic<bool> cancelled{true};
  bool observed = false;
  try {
    frontend.Compute(&zero, 1, &cancelled);
  } catch (const std::runtime_error& error) {
    observed = std::string(error.what()) == "cancelled";
  }
  Require(observed, "pre-cancelled call was not cancelled");

  // Request cancellation from another thread while a full frontend is running.
  cancelled.store(false, std::memory_order_relaxed);
  std::thread canceller([&cancelled] {
    std::this_thread::sleep_for(std::chrono::milliseconds(3));
    cancelled.store(true, std::memory_order_relaxed);
  });
  observed = false;
  try {
    frontend.Compute(audio.data(), audio.size(), &cancelled);
  } catch (const std::runtime_error& error) {
    observed = std::string(error.what()) == "cancelled";
  } catch (...) {
    canceller.join();
    throw;
  }
  canceller.join();
  Require(observed, "in-flight call ignored cancellation");

  // Cancellation/validation failures must leave this same instance reusable.
  cancelled.store(false, std::memory_order_relaxed);
  const auto silence = frontend.Compute(&zero, 1, &cancelled);
  Require(silence.size() == kFeatures, "wrong feature count");
  for (float value : silence) Require(value == -1.5f, "wrong silence value");
  const float endpoints[] = {-1.0f, 1.0f};
  const auto first = frontend.Compute(endpoints, 2);
  const auto second = frontend.Compute(endpoints, 2);
  Require(first == second, "state leaked between calls");
  for (float value : first) Require(std::isfinite(value), "nonfinite output");
  std::cout << "PASS validation, pre-cancellation, in-flight cancellation, reuse\n";
}
}  // namespace

int main(int argc, char** argv) {
  try {
    if (argc == 2 && std::string(argv[1]) == "--self-test") {
      SelfTest();
      return 0;
    }
    if (argc != 3) throw std::invalid_argument("expected input.f32 output.f32 or --self-test");
    std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
    if (!input) throw std::runtime_error("cannot open input");
    const auto bytes = input.tellg();
    if (bytes <= 0 || bytes % sizeof(float) != 0 ||
        bytes > static_cast<std::streamoff>(kSamples * sizeof(float))) {
      throw std::invalid_argument("invalid input byte count");
    }
    input.seekg(0);
    std::vector<float> audio(static_cast<size_t>(bytes) / sizeof(float));
    if (!input.read(reinterpret_cast<char*>(audio.data()), bytes)) {
      throw std::runtime_error("cannot read input");
    }
    WhisperFeatures frontend;
    const auto features = frontend.Compute(audio.data(), audio.size());
    std::ofstream output(argv[2], std::ios::binary | std::ios::trunc);
    if (!output.write(reinterpret_cast<const char*>(features.data()),
                      static_cast<std::streamsize>(features.size() * sizeof(float)))) {
      throw std::runtime_error("cannot write output");
    }
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
