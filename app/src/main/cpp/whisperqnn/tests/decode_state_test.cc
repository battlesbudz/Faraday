#include "../whisper_decode_state.h"
#include <algorithm>
#include <cassert>
#include <iostream>
using namespace jarvis::whisperqnn;
int main() {
  for (uint32_t bits = 0; bits < 65536; ++bits) {
    const auto half = static_cast<uint16_t>(bits);
    if ((half & 0x7c00u) != 0x7c00u || !(half & 0x03ffu)) assert(FloatToHalf(HalfToFloat(half)) == half);
  }
  assert(FloatToHalf(1.00048828125f) == 0x3c00);
  assert(FloatToHalf(1.00146484375f) == 0x3c02);
  assert(FloatToHalf(65504.0f) == 0x7bff);
  assert(FloatToHalf(-100.0f) == 0xd640);
  auto inputs = DecoderInputs();
  assert(inputs.size() == 27 && DecoderOutputs().size() == 13 && CrossSpecs().size() == 12);
  auto reordered = inputs;
  std::reverse(reordered.begin(), reordered.end());
  assert(MatchesSignature(reordered, inputs));
  reordered[0].type = TensorType::Int32;
  assert(!MatchesSignature(reordered, inputs));
  reordered = inputs; reordered[4].dimensions = KeyShape(200);
  assert(!MatchesSignature(reordered, inputs));
  reordered = inputs; reordered[4].name = reordered[3].name;
  assert(!MatchesSignature(reordered, inputs));
  reordered = inputs; reordered.pop_back(); assert(!MatchesSignature(reordered, inputs));
  reordered = inputs; reordered.push_back(inputs[0]); assert(!MatchesSignature(reordered, inputs));
  auto outputs = DecoderOutputs();
  assert(outputs.front().name == "k_cache_self_0_out");
  assert(outputs[11].name == "v_cache_self_5_out" && outputs[12].name == "logits");
  assert(outputs[0].dimensions == KeyShape(199) && outputs[1].dimensions == ValueShape(199));
  const std::array<int32_t, 4> prefix{50258, 50259, 50359, 50363};
  std::vector<uint16_t> logits(DecodeState::kVocabulary, FloatToHalf(-10));
  logits[42] = FloatToHalf(1);
  logits[50359] = FloatToHalf(100);
  logits[51000] = FloatToHalf(100);
  DecodeState state;
  for (int n = 0; n < DecodeState::kMaxCalls; ++n) {
    assert(!state.Done() && state.Position() == n);
    if (n < 4) assert(state.InputToken() == prefix[n]);
    else assert(state.InputToken() == 42);
    const auto& mask = state.BeginStep();
    for (int i = 0; i < 200; ++i) assert(HalfToFloat(mask[i]) == (i >= 199 - n ? 0 : -100));
    state.Advance(logits.data(), logits.size());
  }
  assert(state.Done() && state.Tokens().size() == 196);
  assert(std::all_of(state.Tokens().begin(), state.Tokens().end(), [](int token) { return token == 42; }));
  bool rejected = false;
  try { state.BeginStep(); } catch (const std::logic_error&) { rejected = true; }
  assert(rejected);
  DecodeState eos;
  logits[DecodeState::kEos] = FloatToHalf(50);
  for (int n = 0; n < 4; ++n) { eos.BeginStep(); eos.Advance(logits.data(), logits.size()); }
  assert(eos.Done() && eos.Tokens().empty());
  DecodeState malformed;
  rejected = false;
  try { malformed.Advance(nullptr, logits.size()); } catch (const std::invalid_argument&) { rejected = true; }
  assert(rejected);
  for (int n = 0; n < 3; ++n) { malformed.BeginStep(); malformed.Advance(logits.data(), logits.size()); }
  logits[7] = 0x7e00; rejected = false;
  try { malformed.Advance(logits.data(), logits.size()); } catch (const std::invalid_argument&) { rejected = true; }
  assert(rejected);
  std::cout << "Decoder mask/prefix/cache/metadata/FP16 contracts passed\n";
}
