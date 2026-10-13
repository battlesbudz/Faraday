// SOURCE-ONLY PROPOSAL: not compiled or executed during planning.
// Direct official ORT API oracle. No Jarvis source, frontend, Session or JNI imports.
#include "onnxruntime_cxx_api.h"
#include <array>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

int main(int argc, char** argv) {
  try {
    if (argc != 17 || sizeof(float) != 4) return 2;
    std::vector<char> model(8679182);
    if (!std::cin.read(model.data(), model.size()) || std::cin.get() != EOF)
      throw std::runtime_error("bounded model input required");
    if (std::string(Ort::GetVersionString()) != "1.27.1")
      throw std::runtime_error("ORT identity mismatch");
    Ort::Env environment(ORT_LOGGING_LEVEL_ERROR, "smart-turn-independent-host-reference");
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(1);
    options.SetInterOpNumThreads(1);
    options.SetExecutionMode(ExecutionMode::ORT_SEQUENTIAL);
    options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    options.AddConfigEntry("session.intra_op.allow_spinning", "0");
    options.AddConfigEntry("session.inter_op.allow_spinning", "0");
    options.DisableCpuMemArena();
    Ort::Session session(environment, model.data(), model.size(), options);
    Ort::AllocatorWithDefaultOptions allocator;
    if (session.GetInputCount() != 1 || session.GetOutputCount() != 1 ||
        std::string(session.GetInputNameAllocated(0, allocator).get()) != "input_features" ||
        std::string(session.GetOutputNameAllocated(0, allocator).get()) != "logits")
      throw std::runtime_error("independent schema mismatch");
    auto input_info = session.GetInputTypeInfo(0);
    auto output_info = session.GetOutputTypeInfo(0);
    auto input_type = input_info.GetTensorTypeAndShapeInfo();
    auto output_type = output_info.GetTensorTypeAndShapeInfo();
    if (input_type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        output_type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        input_type.GetShape() != std::vector<int64_t>({-1, 80, 800}) ||
        output_type.GetShape() != std::vector<int64_t>({-1, 1}))
      throw std::runtime_error("independent tensor signature mismatch");
    std::cout << std::setprecision(std::numeric_limits<float>::max_digits10);
    for (int i = 1; i < argc; ++i) {
      std::ifstream in(argv[i], std::ios::binary | std::ios::ate);
      if (in.tellg() != 64000 * 4) throw std::runtime_error("feature byte count mismatch");
      in.seekg(0);
      std::vector<float> features(64000);
      if (!in.read(reinterpret_cast<char*>(features.data()), features.size() * 4))
        throw std::runtime_error("feature read failed");
      for (float value : features)
        if (!std::isfinite(value)) throw std::runtime_error("nonfinite reference feature");
      auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
      const std::array<int64_t, 3> shape{1, 80, 800};
      auto input = Ort::Value::CreateTensor<float>(memory, features.data(), features.size(), shape.data(), shape.size());
      const char* inputs[] = {"input_features"};
      const char* outputs[] = {"logits"};
      Ort::RunOptions run;
      auto result = session.Run(run, inputs, &input, 1, outputs, 1);
      if (result.size() != 1 || !result[0].IsTensor()) throw std::runtime_error("output count/type mismatch");
      const auto type = result[0].GetTensorTypeAndShapeInfo();
      if (type.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
          type.GetShape() != std::vector<int64_t>({1, 1})) throw std::runtime_error("output signature mismatch");
      const float probability = result[0].GetTensorData<float>()[0];
      if (!std::isfinite(probability) || probability < 0 || probability > 1)
        throw std::runtime_error("invalid probability");
      // The pinned model already supplies a probability. Never add another sigmoid.
      std::cout << "{\"input_index\":" << i - 1 << ",\"probability\":" << probability << "}\n";
    }
    return 0;
  } catch (const std::exception&) {
    std::cerr << "Independent Smart Turn ORT reference failed\n";
    return 1;
  }
}
