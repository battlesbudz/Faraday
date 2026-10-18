// Production registry/JNI close with a weight-free Session stand-in.
#include "../whisper_qnn_jni.cc"
#include <atomic>
#include <cassert>
#include <iostream>
#include <thread>
namespace {
std::atomic<int> destructions{0};
std::atomic<int> cancellations{0};
}
namespace jarvis::whisperqnn {
class Session::Impl {};
Session::Session(const std::string&, const std::string&, const std::string&) {}
Session::~Session() { ++destructions; }
void Session::Cancel() noexcept { ++cancellations; }
std::vector<int32_t> Session::Decode(const float*, size_t) { return {}; }
}
int main() {
  sessions.emplace(1, std::make_shared<Session>("", "", ""));
  auto borrowed = Find(1);
  std::thread closer([] { JNI_METHOD(close)(nullptr, nullptr, 1); });
  closer.join();
  assert(cancellations == 1 && destructions == 0);
  bool rejected = false;
  try { Find(1); } catch (const Failure& error) { rejected = std::string(error.what()) == "whisper_qnn_cancelled"; }
  assert(rejected);
  borrowed.reset();
  assert(destructions == 1);
  JNI_METHOD(close)(nullptr, nullptr, 1);
  JNI_METHOD(cancel)(nullptr, nullptr, 1);
  assert(cancellations == 1 && destructions == 1);
  std::cout << "JNI close revocation and in-flight shared ownership passed\n";
}
