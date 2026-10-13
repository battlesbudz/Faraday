#pragma once
#include "whisper_features.h"
#include "onnxruntime_cxx_api.h"
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace jarvis::smartturn {
struct Prediction { double probability; double frontend_nanos; double inference_nanos; };
class Session {
 public:
  Session(const void* model, size_t bytes)
      : environment_(ORT_LOGGING_LEVEL_ERROR, "jarvis-smart-turn-shadow"),
        session_(nullptr) {
    if (std::string(Ort::GetVersionString()) != "1.27.1") throw std::runtime_error("ORT identity mismatch");
    Ort::SessionOptions options;
    options.SetInterOpNumThreads(1);
    options.SetIntraOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    options.AddConfigEntry("session.intra_op.allow_spinning", "0");
    options.AddConfigEntry("session.inter_op.allow_spinning", "0");
    options.DisableCpuMemArena();
    // Do not register NNAPI/XNNPACK or a second runtime. CPU is the only provider.
    session_ = Ort::Session(environment_, model, bytes, options);
    Ort::AllocatorWithDefaultOptions allocator;
    if (session_.GetInputCount() != 1 || session_.GetOutputCount() != 1 ||
        std::string(session_.GetInputNameAllocated(0, allocator).get()) != "input_features" ||
        std::string(session_.GetOutputNameAllocated(0, allocator).get()) != "logits")
      throw std::runtime_error("model name/count mismatch");
    auto input_info = session_.GetInputTypeInfo(0);
    auto output_info = session_.GetOutputTypeInfo(0);
    auto in = input_info.GetTensorTypeAndShapeInfo();
    auto out = output_info.GetTensorTypeAndShapeInfo();
    if (in.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        out.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        in.GetShape() != std::vector<int64_t>({-1, 80, 800}) ||
        out.GetShape() != std::vector<int64_t>({-1, 1}))
      throw std::runtime_error("model tensor signature mismatch");
  }
  void Prepare(int64_t token) {
    std::lock_guard<std::mutex> guard(lock_);
    if (run_) throw std::runtime_error("inference already active");
    run_ = std::make_shared<Run>(token);
  }
  void Cancel(int64_t token) noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    if (run_ && run_->token == token) {
      run_->cancelled.store(true);
      try { run_->options.SetTerminate(); } catch (...) { /* Publication is revoked in Kotlin. */ }
    }
  }
  Prediction Infer(int64_t token, const float* pcm, size_t count) {
    std::shared_ptr<Run> run;
    {
      std::lock_guard<std::mutex> guard(lock_);
      if (!run_ || run_->token != token) throw std::runtime_error("unprepared inference");
      run = run_;
    }
    struct Reset {
      Session* owner;
      ~Reset() { std::lock_guard<std::mutex> guard(owner->lock_); owner->run_.reset(); }
    } reset{this};
    const auto begin = std::chrono::steady_clock::now();
    auto features = frontend_.Compute(pcm, count, &run->cancelled);
    const auto prepared = std::chrono::steady_clock::now();
    if (run->cancelled.load()) throw std::runtime_error("cancelled");
    auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    const std::array<int64_t, 3> shape{1, 80, 800};
    auto input = Ort::Value::CreateTensor<float>(memory, features.data(), features.size(), shape.data(), shape.size());
    const char* input_names[] = {"input_features"};
    const char* output_names[] = {"logits"};
    auto output = session_.Run(run->options, input_names, &input, 1, output_names, 1);
    const auto finished = std::chrono::steady_clock::now();
    if (run->cancelled.load() || output.size() != 1 || !output[0].IsTensor())
      throw std::runtime_error("cancelled or malformed output");
    const auto type = output[0].GetTensorTypeAndShapeInfo();
    if (type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        type.GetShape() != std::vector<int64_t>({1, 1})) throw std::runtime_error("output signature mismatch");
    const float probability = output[0].GetTensorData<float>()[0];
    if (!std::isfinite(probability) || probability < 0 || probability > 1)
      throw std::runtime_error("invalid probability");
    return {probability,
            static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(prepared - begin).count()),
            static_cast<double>(std::chrono::duration_cast<std::chrono::nanoseconds>(finished - prepared).count())};
  }
 private:
  struct Run {
    explicit Run(int64_t id) : token(id) {}
    int64_t token;
    std::atomic<bool> cancelled{false};
    Ort::RunOptions options;
  };
  Ort::Env environment_;
  Ort::Session session_;
  WhisperFeatures frontend_;
  std::mutex lock_;
  std::shared_ptr<Run> run_;
};
}  // namespace jarvis::smartturn
