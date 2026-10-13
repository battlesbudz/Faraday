// SOURCE-ONLY PROPOSAL: not compiled or executed during planning.
// The actual production Session header and frontend implementation are linked unchanged.
#include <cmath>
#include "smart_turn_session.h"
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <vector>

static std::vector<char> Model() {
  std::vector<char> model(8679182);
  if (!std::cin.read(model.data(), model.size()) || std::cin.get() != EOF)
    throw std::runtime_error("bounded model input required");
  return model;
}
static std::vector<float> Pcm(const char* path) {
  std::ifstream in(path, std::ios::binary | std::ios::ate);
  const auto bytes = in.tellg();
  if (bytes <= 0 || bytes > 128000 * 4 || bytes % 4 != 0)
    throw std::runtime_error("bounded PCM input required");
  in.seekg(0);
  std::vector<float> pcm(static_cast<size_t>(bytes) / 4);
  if (!in.read(reinterpret_cast<char*>(pcm.data()), bytes))
    throw std::runtime_error("PCM read failed");
  return pcm;
}
template <class F> static void MustReject(F action) {
  bool rejected = false;
  try { action(); } catch (const std::exception&) { rejected = true; }
  if (!rejected) throw std::runtime_error("negative control accepted");
}
int main(int argc, char** argv) {
  try {
    if (argc != 9 || sizeof(float) != 4) return 2;
    auto model = Model();
    jarvis::smartturn::Session session(model.data(), model.size());
    const float silence = 0;
    MustReject([&] { session.Infer(1, &silence, 1); });
    session.Prepare(2);
    session.Cancel(2);
    MustReject([&] { session.Infer(2, &silence, 1); });
    std::cout << std::setprecision(std::numeric_limits<double>::max_digits10);
    double initial = 0;
    for (int i = 1; i < argc; ++i) {
      auto pcm = Pcm(argv[i]);
      session.Prepare(100 + i);
      const auto prediction = session.Infer(100 + i, pcm.data(), pcm.size());
      if (i == 1) initial = prediction.probability;
      // Independent invocation of the exact same production frontend exposes its
      // features without changing Session or providing it an alternate feature path.
      jarvis::smartturn::WhisperFeatures frontend;
      const auto features = frontend.Compute(pcm.data(), pcm.size());
      std::ofstream out(std::string(argv[i]) + ".native-features.f32le", std::ios::binary);
      if (!out.write(reinterpret_cast<const char*>(features.data()), features.size() * 4))
        throw std::runtime_error("feature output failed");
      std::cout << "{\"case_index\":" << i - 1
                << ",\"probability\":" << prediction.probability
                << ",\"frontend_nanos\":" << prediction.frontend_nanos
                << ",\"inference_nanos\":" << prediction.inference_nanos << "}\n";
    }
    const float nonfinite = std::numeric_limits<float>::quiet_NaN();
    session.Prepare(200);
    MustReject([&] { session.Infer(200, &nonfinite, 1); });
    auto first = Pcm(argv[1]);
    session.Prepare(201);
    const auto again = session.Infer(201, first.data(), first.size());
    if (again.probability != initial) throw std::runtime_error("same-session repeat differed");
    std::cout << "{\"unprepared_rejected\":true,\"pre_cancel_rejected\":true,"
                 "\"nonfinite_rejected\":true,\"reuse_after_rejections_exact\":true}\n";
    return 0;
  } catch (const std::exception&) {
    std::cerr << "Native Smart Turn host validation failed\n";
    return 1;
  }
}
