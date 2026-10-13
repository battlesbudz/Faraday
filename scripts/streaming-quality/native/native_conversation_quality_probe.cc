// Source-only fallback: actual native Conversation, never a JNI success claim.
// The Python supervisor verifies all hashes and installs hard process limits.
#include <sys/resource.h>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "absl/base/log_severity.h"
#include "absl/log/globals.h"
#include "absl/status/status.h"
#include "absl/status/statusor.h"
#include "absl/strings/escaping.h"
#include "absl/time/time.h"
#include "litert/cc/litert_element_type.h"
#include "litert/cc/litert_tensor_buffer.h"
#include "nlohmann/json.hpp"
#include "runtime/conversation/conversation.h"
#include "runtime/conversation/io_types.h"
#include "runtime/conversation/thinking_config.h"
#include "runtime/engine/engine.h"
#include "runtime/engine/engine_factory.h"
#include "runtime/engine/engine_settings.h"
#include "runtime/engine/io_types.h"
#include "runtime/executor/audio_executor_settings.h"
#include "runtime/executor/executor_settings_base.h"
#include "runtime/executor/llm_executor_io_types.h"
#include "runtime/executor/llm_executor_settings.h"
#include "runtime/proto/sampler_params.pb.h"

namespace {
using Json = nlohmann::ordered_json;
using namespace litert::lm;
constexpr uint64_t kAddressLimit = 6ull * 1024 * 1024 * 1024;
constexpr size_t kAudioBytes = 77 * 1536 * sizeof(float);
std::string stage = "validate_request";

void Need(bool value, const std::string& reason) {
  if (!value) throw std::runtime_error(reason);
}
template<class T> T Take(absl::StatusOr<T> value, const char* what) {
  if (!value.ok()) throw std::runtime_error(std::string(what) + ": " + value.status().ToString());
  return std::move(value).value();
}
void Check(absl::Status value, const char* what) {
  if (!value.ok()) throw std::runtime_error(std::string(what) + ": " + value.ToString());
}
std::string Read(const std::string& path, size_t max_bytes) {
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  Need(file.good(), "Cannot read " + path);
  const auto size = file.tellg();
  Need(size >= 0 && static_cast<uint64_t>(size) <= max_bytes, "Input exceeds bound");
  file.seekg(0);
  std::string result(static_cast<size_t>(size), '\0');
  Need(static_cast<bool>(file.read(result.data(), result.size())), "Incomplete fixture read");
  return result;
}
Json Memory() {
  Json result = Json::object();
  std::ifstream file("/proc/self/status");
  std::string line;
  while (std::getline(file, line)) {
    for (const char* name : {"VmSize:", "VmRSS:", "VmPeak:", "VmHWM:"}) {
      if (line.rfind(name, 0) == 0) result[name] = line.substr(std::strlen(name));
    }
  }
  return result;
}
void Stage(const char* name) {
  stage = name;
  std::cout << Json{{"stage", stage}, {"memory", Memory()}}.dump() << std::endl;
}

struct Tap {
  const std::string expected;
  std::mutex mutex;
  Json receipt = Json{{"calls", 0}, {"bitwise_equal", false}};
  explicit Tap(std::string input) : expected(std::move(input)) {}
  void Observe(const ExecutorAudioData& audio) noexcept {
    std::lock_guard<std::mutex> guard(mutex);
    try {
      receipt["calls"] = receipt["calls"].get<int>() + 1;
      receipt["valid_tokens"] = audio.GetValidTokens();
      auto ptr = audio.GetEmbeddingsPtr();
      Need(ptr.ok() && *ptr != nullptr, "Audio callback has no embeddings");
      auto type = (*ptr)->TensorType();
      Need(type.HasValue(), "Cannot get callback tensor type");
      auto dims = type->Layout().Dimensions();
      receipt["shape"] = std::vector<int32_t>(dims.begin(), dims.end());
      Need(type->ElementType() == litert::ElementType::Float32 && dims.size() == 3 &&
           dims[0] == 1 && dims[1] >= 77 && dims[2] == 1536 && audio.GetValidTokens() == 77,
           "Unexpected audio callback shape/type/count");
      auto size = (*ptr)->PackedSize();
      Need(size.HasValue() && *size >= kAudioBytes, "Audio callback buffer too short");
      auto locked = litert::TensorBufferScopedLock::Create<float>(**ptr, litert::TensorBuffer::LockMode::kRead);
      Need(locked.HasValue(), "Cannot read audio callback buffer");
      receipt["bitwise_equal"] = std::memcmp(locked->second, expected.data(), kAudioBytes) == 0;
      receipt["bytes_compared"] = kAudioBytes;
      receipt["eoa_included"] = false;
      receipt["scope"] = "ExecutorAudioData post-adapter valid rows before LLM prefill; not token IDs/logits/KV or learned-EOA bytes";
    } catch (const std::exception& error) {
      receipt["error"] = error.what();
    } catch (...) {
      // No callback exception may unwind through the SDK. The process-level
      // budget still protects catastrophic allocator failures.
    }
  }
  Json Result() { std::lock_guard<std::mutex> guard(mutex); return receipt; }
};

void Run(const Json& request, Json& result, std::unique_ptr<Engine>& engine,
         std::unique_ptr<Conversation>& conversation) {
  Need(request.at("sdk_commit") == "924e79c91542761242244e4f1651851f822e4cbb", "SDK pin mismatch");
  Need(request.at("litert_workspace_pin") == "0ff28117f1cb5556d0e015bf80b773f74e2bee51", "LiteRT pin mismatch");
  Need(request.at("bundle_sha256") == "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", "Bundle pin mismatch");
  Need(request.at("producer_sha256") == "d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b", "Producer pin mismatch");
  Need(request.at("case") == "transcribe", "Only bounded transcription is implemented in this minimal runner");
  const auto mode = request.at("mode").get<std::string>();
  Need(mode == "raw" || mode == "projected_null", "Invalid lane");
  Need(request.at("context_tokens") == 640 && request.at("max_output_tokens") == 64, "Budget/config mismatch");
  Need(request.at("resource_profile") == "hosted_full_e2b_context640_control", "Resource profile mismatch");
  Need(request.at("audio_embedding_tap") == true, "Audio embedding tap required");
  const Json message = request.at("message");
  Need(message.at("role") == "user" && message.at("content").size() == 2,
       "One text and one audio item required");
  Need(message.at("content")[0].at("type") == "text" && message.at("content")[1].at("type") == "audio", "Content layout mismatch");
  const auto expected = Read(request.at("projected_tokens_path"), kAudioBytes);
  Need(expected.size() == kAudioBytes, "Projected reference size mismatch");
  const auto& audio_item = message.at("content")[1];
  Need(audio_item.contains("projected_audio") == (mode == "projected_null"), "Lane payload mismatch");
  std::string decoded;
  Need(absl::Base64Unescape(audio_item.at("blob").get<std::string>(), &decoded), "Invalid audio base64");
  if (mode == "projected_null") {
    Need(decoded == expected, "Projected transport bytes do not equal pinned reference");
    const auto& meta = audio_item.at("projected_audio");
    Need(meta.at("pcm_samples") == 49221 && meta.at("token_count") == 77 && meta.at("embedding_width") == 1536 && meta.at("complete") == true,
         "Projected metadata mismatch");
  } else {
    Need(decoded == Read(request.at("wav_path"), 256 * 1024), "Raw transport bytes differ from matched WAV");
  }
  decoded.clear(); decoded.shrink_to_fit();
  auto tap = std::make_shared<Tap>(expected);
  Stage("initialize_engine");
  auto assets = Take(ModelAssets::Create(request.at("model_path").get<std::string>()), "ModelAssets");
  auto settings = Take(EngineSettings::CreateDefault(std::move(assets), Backend::CPU, std::nullopt,
      mode == "raw" ? std::optional<Backend>(Backend::CPU) : std::nullopt), "EngineSettings");
  auto& main = settings.GetMutableMainExecutorSettings();
  main.SetMaxNumTokens(640); main.SetCacheDir(":nocache");
  auto cpu = Take(main.MutableBackendConfig<CpuConfig>(), "CPU settings");
  cpu.number_of_threads = 1; main.SetBackendConfig(cpu);
  auto advanced = main.GetAdvancedSettings().value_or(AdvancedSettings());
  advanced.enable_speculative_decoding = false; main.SetAdvancedSettings(advanced);
  if (mode == "raw") {
    settings.GetMutableAudioExecutorSettings()->SetNumThreads(1);
    settings.GetMutableAudioExecutorSettings()->SetCacheDir(":nocache");
  }
  settings.GetMutableBenchmarkParams();
  engine = Take(EngineFactory::CreateDefault(std::move(settings)), "Engine creation");
  Stage("create_conversation");
  auto session = SessionConfig::CreateDefault();
  auto& sampler = session.GetMutableSamplerParams();
  // Match the JNI SamplerConfig conversion exactly: TOP_P with k=1 is greedy.
  sampler.set_type(proto::SamplerParameters::TOP_P);
  sampler.set_k(1); sampler.set_p(1.0f); sampler.set_temperature(0.0f); sampler.set_seed(0);
  session.SetMaxOutputTokens(64);
  session.SetAudioModalityEnabled(mode == "raw");
  session.SetVisionModalityEnabled(false);
  const bool tap_enabled = request.value("audio_embedding_tap", true);
  if (tap_enabled) session.SetAudioEmbeddingsCallback([tap](const ExecutorAudioData& audio) { tap->Observe(audio); });
  JsonPreface preface{Json::array(), Json::array(), Json::object()};
  auto config = Take(ConversationConfig::Builder().SetSessionConfig(session).SetPreface(preface)
      .SetEnableConstrainedDecoding(false).SetPrefillPrefaceOnInit(false).SetChannels({})
      .SetThinkingConfig(ThinkingConfig(false, 0)).Build(*engine), "ConversationConfig");
  conversation = Take(Conversation::Create(*engine, config), "Conversation creation");
  result["initial_tokens"] = Take(conversation->GetTokenCount(), "Initial token count");
  Stage("prefill_and_decode");
  const auto start = std::chrono::steady_clock::now();
  auto response = conversation->SendMessage(message);
  result["elapsed_seconds"] = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
  if (tap_enabled) result["audio_embedding_tap"] = tap->Result();
  auto message_response = Take(std::move(response), "Native SendMessage");
  Check(conversation->WaitUntilDone(), "Post-send checked drain");
  result["final_tokens"] = Take(conversation->GetTokenCount(), "Final token count");
  auto benchmark = Take(conversation->GetBenchmarkInfo(), "Benchmark info");
  Need(benchmark.GetTotalPrefillTurns() > 0 && benchmark.GetTotalDecodeTurns() > 0, "Missing benchmark turns");
  auto prefill = Take(benchmark.GetPrefillTurn(benchmark.GetTotalPrefillTurns() - 1), "Prefill count");
  auto decode = Take(benchmark.GetDecodeTurn(benchmark.GetTotalDecodeTurns() - 1), "Decode count");
  result["prefill_tokens"] = prefill.num_tokens;
  result["decode_tokens"] = decode.num_tokens;
  result["decode_at_budget"] = decode.num_tokens >= 64;
  result["response"] = message_response;
  result["tool_calls"] = message_response.value("tool_calls", Json::array());
  std::string text;
  if (message_response.contains("content")) for (const auto& item : message_response["content"]) {
    if (item.value("type", "") == "text") text += item.at("text").get<std::string>();
  }
  result["text"] = text;
  Need(prefill.num_tokens >= 78 && decode.num_tokens > 0 && !text.empty() && result["tool_calls"].empty(), "Missing positive audio/transcription result");
  if (tap_enabled) {
    const auto receipt = tap->Result();
    Need(receipt.at("calls") == 1 && receipt.at("bitwise_equal") == true && !receipt.contains("error"),
         "Native audio-embedding callback differs from pinned reference");
  }
}
} // namespace

int main(int argc, char** argv) {
  Need(argc == 3, "REQUEST_JSON RESULT_JSON required");
  rlimit limit{};
  Need(getrlimit(RLIMIT_AS, &limit) == 0 && limit.rlim_cur == kAddressLimit && limit.rlim_max == kAddressLimit,
       "Must run under the fixed 6 GiB address-space supervisor");
  Need(std::getenv("GEMMA_QUALITY_COMPUTE_SLOT") != nullptr &&
       std::string(std::getenv("GEMMA_QUALITY_COMPUTE_SLOT")) == "confirmed_by_owner", "No owner compute slot");
  absl::SetMinLogLevel(absl::LogSeverityAtLeast::kInfo);
  absl::SetStderrThreshold(absl::LogSeverityAtLeast::kInfo);
  Json result{{"interface", "native_cpp_conversation"}, {"jni_positive_consumption_passed", false},
              {"quality_claim", false}, {"fresh_process", true}, {"fresh_conversation", true},
              {"tool_dispatch_count", 0}, {"automatic_tool_calling", false}};
  std::unique_ptr<Engine> engine;
  std::unique_ptr<Conversation> conversation;
  int exit_code = 2;
  try {
    auto request = Json::parse(Read(argv[1], 2 * 1024 * 1024));
    for (const char* key : {"mode", "case", "sdk_commit", "litert_workspace_pin", "bundle_sha256", "producer_sha256",
                           "manifest_sha256", "pcm_sha256", "projected_tokens_sha256", "native_binary_sha256",
                           "native_source_snapshot_sha256", "full_bundle_hash_reverified_before_launch",
                           "context_tokens", "max_output_tokens", "resource_profile"}) result[key] = request.at(key);
    Run(request, result, engine, conversation);
    result["execution_passed"] = true;
    result["status"] = "executed_needs_native_paired_comparison";
    exit_code = 0;
  } catch (const std::exception& error) {
    result["execution_passed"] = false; result["status"] = "failed_or_blocked";
    result["failure_stage"] = stage; result["error"] = error.what();
  }
  Stage("checked_drain_and_dispose");
  absl::Status drain = absl::OkStatus();
  if (conversation) drain = conversation->WaitUntilDone();
  if (drain.ok() && engine) drain = engine->WaitUntilDone(absl::Seconds(10));
  if (drain.ok()) {
    conversation.reset(); engine.reset(); result["checked_drain_delete"] = true;
  } else {
    result["checked_drain_delete"] = false; result["drain_error"] = drain.ToString();
    result["execution_passed"] = false; result["status"] = "drain_failed_quarantined_until_process_exit";
    (void)conversation.release(); (void)engine.release(); exit_code = 2;
  }
  std::ofstream(argv[2]) << result.dump(2) << '\n';
  std::cout << Json{{"stage", "finished"}, {"exit_code", exit_code}, {"memory", Memory()}}.dump() << std::endl;
  return exit_code;
}
