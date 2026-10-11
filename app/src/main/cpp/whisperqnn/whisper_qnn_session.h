#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
namespace jarvis::whisperqnn {
// Only constant diagnostics cross JNI, never ORT errors, paths, PCM or text.
class Failure : public std::runtime_error {
 public:
  explicit Failure(const char* code) : std::runtime_error(code) {}
};
class Session {
 public:
  Session(const std::string& directory, const std::string& library_directory,
          const std::string& dsp_directory);
  ~Session();
  Session(const Session&) = delete;
  Session& operator=(const Session&) = delete;
  std::vector<int32_t> Decode(const float* samples, size_t count);
  // Terminal latch: caller closes/recreates after cancel. A queued Decode cannot
  // reset cancellation. Close must revoke the handle before cancellation.
  void Cancel() noexcept;
 private:
  class Impl;
  std::unique_ptr<Impl> impl_;
};
}  // namespace jarvis::whisperqnn
