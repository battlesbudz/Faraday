// Bounded, local-only CompiledModel probe for the SDK's actual LiteRT pin.
// Inputs/outputs stay on disk locally. No LLM, conversation or device claim.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "litert/cc/litert_api_types.h"
#include "litert/cc/litert_common.h"
#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_element_type.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_tensor_buffer.h"

namespace {
namespace fs = std::filesystem;
constexpr char kPin[] = "0ff28117f1cb5556d0e015bf80b773f74e2bee51";
constexpr size_t kMaxTensorBytes = 16 * 1024 * 1024;

[[noreturn]] void Fail(const std::string& message) {
  std::cerr << "PROBE_ERROR " << message << '\n';
  std::exit(2);
}

template <typename T>
T Take(litert::Expected<T> result, const std::string& operation) {
  if (!result) Fail(operation + ": " + result.Error().Message());
  return std::move(*result);
}

void Check(litert::Expected<void> result, const std::string& operation) {
  if (!result) Fail(operation + ": " + result.Error().Message());
}

bool SafeLabel(const std::string& value) {
  return !value.empty() && value.find_first_not_of(
      "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-") ==
      std::string::npos;
}

fs::path LocalPath(const fs::path& root, const std::string& relative) {
  fs::path path(relative);
  if (path.is_absolute()) Fail("absolute fixture path rejected");
  for (const auto& part : path) {
    if (part == "..") Fail("parent traversal rejected");
  }
  return root / path;
}

struct TensorSpec {
  size_t index = 0;
  std::string name;
  std::string dtype;
  std::vector<int32_t> shape;
  size_t bytes = 0;
  std::string file_or_label;
  std::string chain;
};

struct Case {
  std::string id;
  std::string signature;
  bool chain_state = false;
  std::vector<TensorSpec> inputs;
  std::vector<TensorSpec> outputs;
};

TensorSpec ReadSpec(std::istream& stream, bool input) {
  TensorSpec spec;
  size_t rank;
  stream >> spec.index >> spec.name >> spec.dtype >> rank;
  if (!stream || rank > 8) Fail("invalid tensor header");
  size_t bytes = spec.dtype == "bool" ? 1 : 4;
  if (spec.dtype != "bool" && spec.dtype != "float32" &&
      spec.dtype != "int32") Fail("unsupported fixture dtype");
  for (size_t i = 0; i < rank; ++i) {
    int32_t dim = 0;
    stream >> dim;
    if (dim <= 0 || static_cast<size_t>(dim) > kMaxTensorBytes / bytes)
      Fail("invalid tensor dimensions");
    bytes *= dim;
    spec.shape.push_back(dim);
  }
  stream >> spec.bytes >> spec.file_or_label;
  if (input) stream >> spec.chain;
  if (!stream || spec.bytes != bytes || spec.bytes > kMaxTensorBytes)
    Fail("invalid tensor size");
  if (!input && !SafeLabel(spec.file_or_label)) Fail("unsafe output label");
  return spec;
}

std::vector<Case> ReadCases(const fs::path& manifest) {
  std::ifstream stream(manifest);
  std::string token;
  stream >> token;
  if (token != "PINNED_ENCODER_FIXTURES_V1") Fail("invalid manifest version");
  std::vector<Case> cases;
  while (stream >> token) {
    if (token != "CASE" || cases.size() >= 64) Fail("invalid case boundary");
    Case current;
    std::string state_mode;
    stream >> current.id >> current.signature >> state_mode;
    if (!SafeLabel(current.id) || (state_mode != "RESET" && state_mode != "CHAIN"))
      Fail("invalid case metadata");
    current.chain_state = state_mode == "CHAIN";
    bool ended = false;
    while (stream >> token) {
      if (token == "END") { ended = true; break; }
      if (token == "INPUT") current.inputs.push_back(ReadSpec(stream, true));
      else if (token == "OUTPUT") current.outputs.push_back(ReadSpec(stream, false));
      else Fail("invalid tensor row");
    }
    if (!ended || current.outputs.empty()) Fail("incomplete case");
    cases.push_back(std::move(current));
  }
  if (cases.empty()) Fail("empty manifest");
  return cases;
}

std::vector<uint8_t> ReadBytes(const fs::path& path, size_t bytes) {
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream || stream.tellg() != static_cast<std::streamoff>(bytes))
    Fail("wrong input file length: " + path.string());
  stream.seekg(0);
  std::vector<uint8_t> result(bytes);
  if (!stream.read(reinterpret_cast<char*>(result.data()), bytes))
    Fail("failed reading " + path.string());
  return result;
}

void Validate(litert::TensorBuffer& buffer, const TensorSpec& spec) {
  const auto type = Take(buffer.TensorType(), "TensorType");
  const auto dims = type.Layout().Dimensions();
  if (dims.size() != spec.shape.size() ||
      !std::equal(dims.begin(), dims.end(), spec.shape.begin()))
    Fail("shape mismatch: " + spec.name);
  const auto wanted = spec.dtype == "float32" ? litert::ElementType::Float32 :
      spec.dtype == "int32" ? litert::ElementType::Int32 : litert::ElementType::Bool;
  if (type.ElementType() != wanted ||
      Take(buffer.PackedSize(), "PackedSize") != spec.bytes)
    Fail("type or packed-size mismatch: " + spec.name);
}

std::vector<size_t> MapNames(const std::vector<litert::StringView>& names,
                             const std::vector<TensorSpec>& specs) {
  if (names.size() != specs.size()) Fail("signature tensor count mismatch");
  std::vector<size_t> mapped;
  std::vector<bool> used(specs.size(), false);
  for (size_t i = 0; i < names.size(); ++i) {
    size_t found = specs.size();
    for (size_t j = 0; j < specs.size(); ++j) {
      if (names[i] == specs[j].name) {
        if (found != specs.size() || used[j]) Fail("duplicate signature tensor name");
        found = j;
      }
    }
    if (found == specs.size())
      Fail("unknown signature tensor name: " + std::string(names[i]));
    used[found] = true;
    mapped.push_back(found);
  }
  return mapped;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc != 5) {
    std::cerr << "usage: pinned_encoder_probe MODEL MANIFEST FIXTURE_ROOT OUTPUT_DIR\n";
    return 2;
  }
  const uint32_t endian = 1;
  if (*reinterpret_cast<const uint8_t*>(&endian) != 1)
    Fail("little-endian host required");
  const auto cases = ReadCases(argv[2]);
  const fs::path root(argv[3]), output(argv[4]);
  if (!fs::is_directory(output)) Fail("output directory must already exist");
  std::ofstream receipt(output / "pinned-encoder-run.tsv");
  if (!receipt) Fail("cannot create execution receipt");
  receipt << "PIN\t" << kPin << "\nCPU_THREADS\t1\n";
  auto environment = Take(litert::Environment::Create({}), "Environment::Create");
  auto options = Take(litert::Options::Create(), "Options::Create");
  Check(options.SetHardwareAccelerators(litert::HwAccelerators::kCpu), "CPU selection");
  auto cpu = options.GetCpuOptions();
  if (!cpu) Fail("GetCpuOptions: " + cpu.Error().Message());
  Check(cpu->SetNumThreads(1), "SetNumThreads");
  auto model = Take(litert::CompiledModel::Create(environment, std::string(argv[1]), options),
                    "CompiledModel::Create");
  std::map<std::string, std::vector<uint8_t>> previous;
  for (const auto& current : cases) {
    const size_t signature = current.signature == "@0" ? 0 :
        Take(model.GetSignatureIndex(current.signature), "GetSignatureIndex");
    // LiteRT exposes tensors in its runtime order, which need not equal the
    // FlatBuffer SignatureDef map order. Resolve the complete exact name set.
    const auto input_order = MapNames(
        Take(model.GetSignatureInputNames(signature), "input names"), current.inputs);
    const auto output_order = MapNames(
        Take(model.GetSignatureOutputNames(signature), "output names"), current.outputs);
    auto inputs = Take(model.CreateInputBuffers(signature), "CreateInputBuffers");
    auto outputs = Take(model.CreateOutputBuffers(signature), "CreateOutputBuffers");
    for (size_t i = 0; i < inputs.size(); ++i) {
      const auto& spec = current.inputs[input_order[i]];
      Validate(inputs[i], spec);
      std::vector<uint8_t> data;
      if (current.chain_state && spec.chain != "-") {
        const auto found = previous.find(spec.chain);
        if (found == previous.end() || found->second.size() != spec.bytes)
          Fail("missing actual previous output: " + spec.chain);
        data = found->second;
      } else {
        data = ReadBytes(LocalPath(root, spec.file_or_label), spec.bytes);
      }
      Check(inputs[i].Write<uint8_t>(litert::Span<const uint8_t>(data.data(), data.size())),
            "TensorBuffer::Write " + spec.name);
    }
    const auto start = std::chrono::steady_clock::now();
    Check(model.Run(signature, inputs, outputs), "CompiledModel::Run " + current.id);
    const auto elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    previous.clear();
    for (size_t i = 0; i < outputs.size(); ++i) {
      const auto& spec = current.outputs[output_order[i]];
      Validate(outputs[i], spec);
      std::vector<uint8_t> data(spec.bytes);
      Check(outputs[i].Read<uint8_t>(litert::Span<uint8_t>(data.data(), data.size())),
            "TensorBuffer::Read " + spec.name);
      const auto filename = "pinned-encoder-" + current.id + ".out." + spec.file_or_label + ".bin";
      std::ofstream stream(output / filename, std::ios::binary);
      stream.write(reinterpret_cast<const char*>(data.data()), data.size());
      stream.close();
      if (!stream) Fail("failed output write: " + filename);
      receipt << "OUTPUT\t" << current.id << '\t' << spec.file_or_label << '\t'
              << spec.bytes << '\t' << filename << '\n';
      previous.emplace(spec.name, std::move(data));
    }
    receipt << "CASE\t" << current.id << '\t' << current.signature << '\t' << elapsed << '\n';
    receipt.flush();
    std::cout << "CASE " << current.id << " seconds=" << elapsed << std::endl;
  }
  receipt << "COMPLETE\t" << cases.size() << '\n';
  receipt.close();
  if (!receipt) Fail("failed receipt write");
  std::cout << "COMPLETE " << cases.size() << " cases; compare outputs separately\n";
  return 0;
}
