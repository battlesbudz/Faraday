// Reconstructed weight-free verification probe. This never initializes a model runtime.
#include "whisper_features.h"
#include <fstream>
#include <iostream>
int main(int argc, char** argv) {
  try {
    if (argc != 3) return 2;
    std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
    const auto bytes = input.tellg(); input.seekg(0);
    if (bytes <= 0 || bytes % sizeof(float) != 0 || bytes > 128000 * sizeof(float)) return 3;
    std::vector<float> pcm(static_cast<size_t>(bytes) / sizeof(float));
    if (!input.read(reinterpret_cast<char*>(pcm.data()), bytes)) return 3;
    jarvis::smartturn::WhisperFeatures frontend;
    const auto features = frontend.Compute(pcm.data(), pcm.size());
    std::ofstream output(argv[2], std::ios::binary);
    if (!output.write(reinterpret_cast<const char*>(features.data()), features.size() * sizeof(float))) return 4;
  } catch (...) { std::cerr << "Invalid bounded frontend request\n"; return 1; }
}
