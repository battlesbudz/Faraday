#pragma once
#include "whisper_qnn_session.h"
#include "onnxruntime_cxx_api.h"
#include <atomic>
#include <memory>
#include <mutex>
namespace jarvis::whisperqnn {
// Owns the terminal latch and active ORT call independently of the decoder.
class WhisperRunState {
 public:
  std::shared_ptr<Ort::RunOptions> Start() {
    auto options = std::make_shared<Ort::RunOptions>();
    std::lock_guard<std::mutex> guard(lock_);
    Check();
    if (active_) throw Failure("whisper_qnn_decode_busy");
    active_ = options;
    return options;
  }
  void End() noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    active_.reset();
  }
  void Cancel() noexcept {
    cancelled.store(true, std::memory_order_release);
    std::lock_guard<std::mutex> guard(lock_);
    if (active_) {
      // Provider termination may finish later; the latch revokes publication.
      try { active_->SetTerminate(); } catch (...) {}
    }
  }
  void Check() const {
    if (cancelled.load(std::memory_order_acquire)) throw Failure("whisper_qnn_cancelled");
  }
  std::atomic<bool> cancelled{false};
 private:
  std::mutex lock_;
  std::shared_ptr<Ort::RunOptions> active_;
};
}  // namespace jarvis::whisperqnn
