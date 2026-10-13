// Isolated full SDK MiniAudio boundary check. No LLM or encoder is loaded.
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <stdexcept>
#include <vector>
#include "absl/types/span.h"
#include "litert/cc/litert_element_type.h"
#include "litert/cc/litert_tensor_buffer.h"
#include "support/preprocessor/audio_preprocessor.h"
#include "support/preprocessor/audio_preprocessor_miniaudio.h"
#include "support/util/io_types.h"

namespace {
std::string Read(const char* path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) throw std::runtime_error("Cannot open fixture");
  return {std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
}
void Require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
}
int main(int argc, char** argv) {
  try {
    Require(argc == 4, "WAV PCM_F32LE MEL_F32LE required");
    const uint32_t endian = 1;
    Require(*reinterpret_cast<const uint8_t*>(&endian) == 1, "Little endian host required");
    const auto wav = Read(argv[1]), pcm_bytes = Read(argv[2]);
    std::string mel_bytes;
    Require(pcm_bytes.size() == 49221 * sizeof(float), "PCM sample count mismatch");
    std::vector<float> pcm(49221), decoded;
    std::memcpy(pcm.data(), pcm_bytes.data(), pcm_bytes.size());
    auto decode = litert::support::AudioPreprocessorMiniAudio::DecodeAudio(wav, 1, 16000, decoded);
    Require(decode.ok(), "Full SDK MiniAudio WAV decode failed");
    Require(decoded.size() == pcm.size() &&
      std::memcmp(decoded.data(), pcm.data(), pcm_bytes.size()) == 0, "Decoded PCM differs bitwise");
    using C = litert::support::AudioPreprocessorConfig;
    auto config = C::Create(16000, 1, 320, 160, 512, 1.0f, 0.0f, 128,
      0.0f, 8000.0f, 1e-3f, false, true, true, false, true,
      C::FftPaddingType::kCenter, false, false);
    auto encoded_preprocessor = litert::support::AudioPreprocessorMiniAudio::Create(config);
    auto pcm_preprocessor = litert::support::AudioPreprocessorMiniAudio::Create(config);
    Require(encoded_preprocessor.ok() && pcm_preprocessor.ok(), "Frontend create failed");
    litert::support::InputAudio encoded(wav), raw(pcm);
    auto encoded_result = (*encoded_preprocessor)->Preprocess(encoded);
    auto raw_result = (*pcm_preprocessor)->Preprocess(raw);
    Require(encoded_result.ok() && raw_result.ok(), "Full SDK preprocess failed");
    auto check_mel = [&](const litert::support::InputAudio& input) {
      auto buffer = input.GetPreprocessedAudioTensor();
      Require(buffer.ok(), "No preprocessed tensor");
      auto type = (*buffer)->TensorType();
      Require(type.HasValue() && type->ElementType() == litert::ElementType::Float32,
              "Expected float32 Mel tensor");
      auto dims = type->Layout().Dimensions();
      Require(dims.size() == 3 && dims[0] == 1 && dims[1] == 307 && dims[2] == 128,
              "Unexpected full SDK Mel dimensions");
      auto packed = (*buffer)->PackedSize();
      Require(packed.HasValue() && *packed == 307 * 128 * sizeof(float), "Unexpected Mel tensor size");
      auto locked = litert::TensorBufferScopedLock::Create<float>(**buffer,
          litert::TensorBuffer::LockMode::kRead);
      Require(locked.HasValue(), "Cannot lock Mel tensor");
      if (mel_bytes.empty()) {
        mel_bytes.assign(reinterpret_cast<const char*>(locked->second), *packed);
        std::ofstream output(argv[3], std::ios::binary);
        Require(static_cast<bool>(output.write(mel_bytes.data(), mel_bytes.size())), "Cannot write Mel output");
      }
      Require(std::memcmp(locked->second, mel_bytes.data(), mel_bytes.size()) == 0,
              "Full SDK Mel bytes differ from streaming fixture");
    };
    check_mel(*encoded_result);
    check_mel(*raw_result);
    std::cout << "{\"passed\":true,\"decode_pcm_bitwise\":true,"
      "\"encoded_and_pcm_mel_bitwise\":true,\"mel_written\":true,"
      "\"pcm_samples\":49221,\"mel_frames\":307,\"model_loaded\":false}\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "FRONTEND_GATE_ERROR " << error.what() << '\n';
    return 2;
  }
}
