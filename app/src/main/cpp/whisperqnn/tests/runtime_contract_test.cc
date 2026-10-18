// Exercise the exact private adapter with a weight-free real ORT host session.
#include "../whisper_qnn_session.cc"
#include <cassert>
#include <iostream>
#include <thread>
using namespace jarvis::whisperqnn;
int main(int argc, char** argv) {
  assert(argc == 3);
  Ort::Env environment(ORT_LOGGING_LEVEL_FATAL, "whisper-host-test", SilentLogger, nullptr);
  Ort::SessionOptions settings;
  settings.SetIntraOpNumThreads(1);
  settings.SetInterOpNumThreads(1);
  Ort::Session identity(environment, argv[1], settings);
  const std::vector<TensorSpec> spec{{"x", TensorType::Half, {1}}};
  const std::vector<TensorSpec> out{{"y", TensorType::Half, {1}}};
  ValidateSession(identity, spec, out);
  bool rejected = false;
  try { ValidateSession(identity, EncoderInputs(), CrossSpecs()); }
  catch (const Failure& failure) { rejected = std::string(failure.what()) == "whisper_qnn_model_signature"; }
  assert(rejected);
  auto wrong_type = spec; wrong_type[0].type = TensorType::Int32;
  rejected = false;
  try { ValidateSession(identity, wrong_type, out); } catch (const Failure&) { rejected = true; }
  assert(rejected);
  auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
  uint16_t data = FloatToHalf(1);
  auto tensor = HalfTensor(memory, &data, 1, spec[0]);
  const char* input_names[] = {"x"};
  const char* output_names[] = {"y"};
  WhisperRunState state;
  auto run = state.Start();
  auto result = identity.Run(*run, input_names, &tensor, 1, output_names, 1);
  assert(result[0].GetTensorData<uint16_t>()[0] == data);
  state.End();
  run = state.Start();  // Successful utterances retain a reusable healthy lease.
  std::thread canceller([&state] { state.Cancel(); });
  canceller.join();
  assert(state.cancelled.load());
  rejected = false;
  try { identity.Run(*run, input_names, &tensor, 1, output_names, 1); }
  catch (const Ort::Exception&) { rejected = true; }
  assert(rejected);  // Real RunOptions.SetTerminate, not just a Java-side flag.
  state.End();
  rejected = false;
  try { state.Start(); } catch (const Failure& failure) { rejected = std::string(failure.what()) == "whisper_qnn_cancelled"; }
  assert(rejected);
  WhisperRunState cancelled_before_start;
  cancelled_before_start.Cancel();
  rejected = false;
  try { cancelled_before_start.Start(); } catch (const Failure&) { rejected = true; }
  assert(rejected);
  for (int i = 0; i < 250; ++i) {
    WhisperRunState racing;
    std::thread cancel([&racing] { racing.Cancel(); });
    try { racing.Start(); racing.End(); } catch (const Failure&) {}
    cancel.join();
    rejected = false;
    try { racing.Check(); } catch (const Failure&) { rejected = true; }
    assert(rejected);
  }
  rejected = false;
  try { Session missing("/nonexistent-whisper-fixture", "/nonexistent-whisper-fixture", "/nonexistent-whisper-fixture"); }
  catch (const Failure& failure) { rejected = std::string(failure.what()) == "whisper_qnn_provider_init_failed"; }
  assert(rejected);
  // ORT's Env is shared: dropping one alias does not remove a registration
  // while this test's environment still exists (as Sherpa's would).
  const auto plugin = Join(argv[2], "libonnxruntime_providers_qnn.so");
  {
    Ort::Env alias(ORT_LOGGING_LEVEL_FATAL, "whisper-alias");
    Ort::ThrowOnError(Ort::GetApi().RegisterExecutionProviderLibrary(alias, kProvider, plugin.c_str()));
  }
  auto* duplicate = Ort::GetApi().RegisterExecutionProviderLibrary(environment, kProvider, plugin.c_str());
  assert(duplicate != nullptr);
  Ort::GetApi().ReleaseStatus(duplicate);
  Ort::ThrowOnError(Ort::GetApi().UnregisterExecutionProviderLibrary(environment, kProvider));
  // Production rollback must release the no-NPU registration on each attempt.
  // Without rollback the second attempt becomes provider_init_failed.
  for (int attempt = 0; attempt < 3; ++attempt) {
    rejected = false;
    try { Session no_npu("/unused-model-fixture", argv[2], argv[2]); }
    catch (const Failure& failure) { rejected = std::string(failure.what()) == "whisper_qnn_npu_unavailable"; }
    assert(rejected);
  }
  std::cout << "Real ORT metadata, cancellation, absent-QNN and registration rollback passed\n";
}
