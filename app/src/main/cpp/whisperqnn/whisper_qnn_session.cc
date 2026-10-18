#include "whisper_qnn_session.h"
#include "whisper_decode_state.h"
#include "whisper_features.h"
#include "whisper_run_state.h"
#include "onnxruntime_cxx_api.h"
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <utility>

namespace jarvis::whisperqnn {
namespace {
constexpr const char* kProvider = "QNNExecutionProvider";
void SilentLogger(void*, OrtLoggingLevel, const char*, const char*, const char*, const char*) {}
std::string Join(const std::string& directory, const char* file) {
  if (directory.empty() || directory.front() != '/' || directory.find(';') != std::string::npos ||
      directory.find('\n') != std::string::npos || directory.find('\0') != std::string::npos)
    throw Failure("whisper_qnn_invalid_directory");
  return directory + (directory.back() == '/' ? "" : "/") + file;
}
}  // namespace

// One process-owned registration shared with all retained encoder/decoder
// sessions. Never unregister the EP while any session may still be running.
class QnnRuntime {
 public:
  QnnRuntime(const std::string& library_directory, const std::string& dsp_directory)
      : libraries(library_directory), dsp(dsp_directory), environment(nullptr) {
    if (Ort::GetVersionString() != "1.27.1") throw Failure("whisper_qnn_runtime_mismatch");
    const auto plugin = Join(libraries, "libonnxruntime_providers_qnn.so");
    Join(dsp, "libQnnHtpV75Skel.so");
    const auto adsp = dsp + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    if (setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1) != 0)
      throw Failure("whisper_qnn_dsp_setup_failed");
    environment = Ort::Env(ORT_LOGGING_LEVEL_FATAL, "jarvis-whisper-qnn", SilentLogger, nullptr);
    Ort::ThrowOnError(Ort::GetApi().RegisterExecutionProviderLibrary(environment, kProvider, plugin.c_str()));
    try {
      const OrtEpDevice* const* devices = nullptr;
      size_t count = 0;
      Ort::ThrowOnError(Ort::GetApi().GetEpDevices(environment, &devices, &count));
      for (size_t i = 0; i < count; ++i) {
        const auto* hardware = Ort::GetApi().EpDevice_Device(devices[i]);
        const char* name = Ort::GetApi().EpDevice_EpName(devices[i]);
        if (name && std::strcmp(name, kProvider) == 0 && hardware &&
            Ort::GetApi().HardwareDevice_Type(hardware) == OrtHardwareDeviceType_NPU) {
          device = devices[i];
          break;
        }
      }
      if (!device) throw Failure("whisper_qnn_npu_unavailable");
    } catch (...) {
      // No session borrowed this failed registration. An existing ORT Env may
      // outlive this constructor, so undo registration before a possible retry.
      auto* status = Ort::GetApi().UnregisterExecutionProviderLibrary(environment, kProvider);
      if (status) Ort::GetApi().ReleaseStatus(status);
      throw;
    }
  }
  const std::string libraries;
  const std::string dsp;
  Ort::Env environment;
  const OrtEpDevice* device = nullptr;
};
namespace {
std::shared_ptr<QnnRuntime> SharedRuntime(const std::string& libraries, const std::string& dsp) {
  static std::mutex lock;
  static std::shared_ptr<QnnRuntime> runtime;
  std::lock_guard<std::mutex> guard(lock);
  if (runtime && (runtime->libraries != libraries || runtime->dsp != dsp))
    throw Failure("whisper_qnn_runtime_directory_mismatch");
  if (!runtime) {
    try { runtime = std::make_shared<QnnRuntime>(libraries, dsp); }
    catch (const Failure&) { throw; }
    catch (...) { throw Failure("whisper_qnn_provider_init_failed"); }
  }
  return runtime;
}
std::vector<TensorSpec> Signature(const Ort::Session& session, bool inputs) {
  Ort::AllocatorWithDefaultOptions allocator;
  const auto count = inputs ? session.GetInputCount() : session.GetOutputCount();
  std::vector<TensorSpec> result;
  for (size_t i = 0; i < count; ++i) {
    auto name = inputs ? session.GetInputNameAllocated(i, allocator) : session.GetOutputNameAllocated(i, allocator);
    auto type = inputs ? session.GetInputTypeInfo(i) : session.GetOutputTypeInfo(i);
    if (!name || type.GetONNXType() != ONNX_TYPE_TENSOR) throw Failure("whisper_qnn_model_signature");
    auto tensor = type.GetTensorTypeAndShapeInfo();
    const auto element = tensor.GetElementType();
    if (element != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 && element != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32)
      throw Failure("whisper_qnn_model_signature");
    result.push_back({name.get(), element == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 ? TensorType::Half : TensorType::Int32,
                      tensor.GetShape()});
  }
  return result;
}
void ValidateSession(const Ort::Session& session, const std::vector<TensorSpec>& inputs,
                     const std::vector<TensorSpec>& outputs) {
  if (!MatchesSignature(Signature(session, true), inputs) || !MatchesSignature(Signature(session, false), outputs))
    throw Failure("whisper_qnn_model_signature");
}
void ValidateTensor(const OrtValue* raw, const TensorSpec& expected) {
  if (!raw) throw Failure("whisper_qnn_output_signature");
  const Ort::ConstValue value(raw);
  if (!value.IsTensor()) throw Failure("whisper_qnn_output_signature");
  auto type = value.GetTensorTypeAndShapeInfo();
  const auto element = expected.type == TensorType::Half ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16
                                                        : ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32;
  if (type.GetElementType() != element || type.GetShape() != expected.dimensions)
    throw Failure("whisper_qnn_output_signature");
}
std::vector<const char*> Names(const std::vector<TensorSpec>& specs) {
  std::vector<const char*> result;
  for (const auto& spec : specs) result.push_back(spec.name.c_str());
  return result;
}
struct Outputs {
  explicit Outputs(size_t count) : values(count, nullptr) {}
  Outputs(Outputs&& other) noexcept : values(std::move(other.values)) { other.values.clear(); }
  Outputs& operator=(Outputs&& other) noexcept {
    if (this != &other) { Release(); values = std::move(other.values); other.values.clear(); }
    return *this;
  }
  Outputs(const Outputs&) = delete;
  ~Outputs() { Release(); }
  void Release() noexcept { for (auto* value : values) if (value) Ort::GetApi().ReleaseValue(value); values.clear(); }
  std::vector<OrtValue*> values;
};
Ort::Value HalfTensor(const Ort::MemoryInfo& memory, uint16_t* data, size_t count, const TensorSpec& spec) {
  return Ort::Value::CreateTensor(memory, data, count * sizeof(uint16_t), spec.dimensions.data(),
                                  spec.dimensions.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16);
}
}  // namespace

class Session::Impl {
 public:
  Impl(const std::string& directory, const std::string& libraries, const std::string& dsp)
      : runtime_(SharedRuntime(libraries, dsp)), encoder_(nullptr), decoder_(nullptr) {
    Ort::SessionOptions options;
    options.SetInterOpNumThreads(1);
    options.SetIntraOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    options.SetLogSeverityLevel(ORT_LOGGING_LEVEL_FATAL);
    options.AddConfigEntry("session.intra_op.allow_spinning", "0");
    options.AddConfigEntry("session.inter_op.allow_spinning", "0");
    options.AddConfigEntry("session.disable_cpu_ep_fallback", "1");
    options.DisableCpuMemArena();
    options.DisableMemPattern();
    const auto backend = Join(libraries, "libQnnHtp.so");
    const char* keys[] = {"backend_path", "htp_performance_mode", "enable_htp_fp16_precision", "offload_graph_io_quantization"};
    const char* values[] = {backend.c_str(), "burst", "1", "1"};
    Ort::ThrowOnError(Ort::GetApi().SessionOptionsAppendExecutionProvider_V2(
        options, runtime_->environment, &runtime_->device, 1, keys, values, 4));
    encoder_ = Ort::Session(runtime_->environment, Join(directory, "encoder.onnx").c_str(), options);
    decoder_ = Ort::Session(runtime_->environment, Join(directory, "decoder.onnx").c_str(), options);
    ValidateSession(encoder_, EncoderInputs(), CrossSpecs());
    ValidateSession(decoder_, DecoderInputs(), DecoderOutputs());
  }
  void Check() const { run_state_.Check(); }
  void Cancel() noexcept { run_state_.Cancel(); }
  Outputs Run(const Ort::Session& session, const std::shared_ptr<Ort::RunOptions>& options,
              const std::vector<TensorSpec>& inputs, const std::vector<const OrtValue*>& values,
              const std::vector<TensorSpec>& outputs) {
    Check();
    const auto input_names = Names(inputs), output_names = Names(outputs);
    Outputs result(outputs.size());
    auto* status = Ort::GetApi().Run(session, *options, input_names.data(), values.data(), values.size(),
                                    output_names.data(), output_names.size(), result.values.data());
    // Own partial outputs before throwing. Prefer terminal cancellation over an
    // ORT error, and never expose raw model paths/audio/text in diagnostics.
    if (run_state_.cancelled.load(std::memory_order_acquire)) {
      if (status) Ort::GetApi().ReleaseStatus(status);
      Check();
    }
    Ort::ThrowOnError(status);
    Check();
    for (size_t i = 0; i < outputs.size(); ++i) { ValidateTensor(result.values[i], outputs[i]); Check(); }
    return result;
  }
  std::vector<int32_t> Decode(const float* samples, size_t count) {
    // One borrower per native session. Reject concurrent work rather than
    // queuing PCM across an utterance/cancellation boundary.
    std::unique_lock<std::mutex> exclusive(decode_lock_, std::try_to_lock);
    if (!exclusive.owns_lock()) throw Failure("whisper_qnn_decode_busy");
    Check();
    const auto options = run_state_.Start();
    struct ReleaseRun {
      WhisperRunState* state;
      ~ReleaseRun() { state->End(); }
    } release{&run_state_};
    Check();
    auto features = frontend_.Compute(samples, count, &run_state_.cancelled);
    Check();
    std::vector<uint16_t> half_features(features.size());
    std::transform(features.begin(), features.end(), half_features.begin(), FloatToHalf);
    features.clear();
    Check();
    auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    const auto encoder_inputs = EncoderInputs(), cross_specs = CrossSpecs();
    auto feature_tensor = HalfTensor(memory, half_features.data(), half_features.size(), encoder_inputs[0]);
    auto cross = Run(encoder_, options, encoder_inputs, {feature_tensor}, cross_specs);
    Check();
    const auto inputs = DecoderInputs(), outputs = DecoderOutputs();
    const auto self_specs = SelfSpecs("_in");
    // Initial caches are read-only zeros. All later caches are graph outputs,
    // and one zero allocation safely backs all twelve differently-shaped inputs.
    std::vector<uint16_t> zeros(8 * 64 * 199, 0);
    std::vector<Ort::Value> initial_cache;
    for (const auto& spec : self_specs) initial_cache.push_back(HalfTensor(memory, zeros.data(), zeros.size(), spec));
    Outputs self(0);
    DecodeState state;
    while (!state.Done()) {
      Check();
      int32_t token = state.InputToken(), position = state.Position();
      const auto& mask = state.BeginStep();
      auto token_tensor = Ort::Value::CreateTensor<int32_t>(memory, &token, 1, inputs[0].dimensions.data(), inputs[0].dimensions.size());
      auto position_tensor = Ort::Value::CreateTensor<int32_t>(memory, &position, 1, inputs[1].dimensions.data(), inputs[1].dimensions.size());
      auto mask_tensor = HalfTensor(memory, const_cast<uint16_t*>(mask.data()), mask.size(), inputs[2]);
      std::vector<const OrtValue*> values{token_tensor, position_tensor, mask_tensor};
      for (size_t i = 0; i < 12; ++i) values.push_back(self.values.empty() ? initial_cache[i] : self.values[i]);
      for (auto* cache : cross.values) values.push_back(cache);
      auto next = Run(decoder_, options, inputs, values, outputs);
      Check();
      const auto* logits = Ort::ConstValue(next.values[12]).GetTensorData<uint16_t>();
      state.Advance(logits, DecodeState::kVocabulary);
      Check();
      self = std::move(next);  // Old caches are no longer being read by Run.
    }
    Check();
    return state.Tokens();
  }
 private:
  std::shared_ptr<QnnRuntime> runtime_;  // Destroyed after sessions.
  Ort::Session encoder_;
  Ort::Session decoder_;
  WhisperFeatures frontend_;
  WhisperRunState run_state_;
  std::mutex decode_lock_;
};
Session::Session(const std::string& directory, const std::string& libraries, const std::string& dsp) {
  try { impl_ = std::make_unique<Impl>(directory, libraries, dsp); }
  catch (const Failure&) { throw; }
  catch (...) { throw Failure("whisper_qnn_model_init_failed"); }
}
Session::~Session() = default;
void Session::Cancel() noexcept { impl_->Cancel(); }
std::vector<int32_t> Session::Decode(const float* samples, size_t count) {
  try { return impl_->Decode(samples, count); }
  catch (const Failure&) { throw; }
  catch (...) { impl_->Check(); throw Failure("whisper_qnn_decode_failed"); }
}
}  // namespace jarvis::whisperqnn
