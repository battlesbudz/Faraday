#pragma once
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace jarvis::whisperqnn {
// IEEE binary16 conversions independent of compiler-specific __fp16 support.
inline uint16_t FloatToHalf(float value) {
  uint32_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  const uint32_t sign = (bits >> 16) & 0x8000u;
  const uint32_t exponent = (bits >> 23) & 0xffu;
  const uint32_t mantissa = bits & 0x7fffffu;
  if (exponent == 255) return static_cast<uint16_t>(sign | 0x7c00u | (mantissa ? 0x0200u : 0));
  const int adjusted = static_cast<int>(exponent) - 127 + 15;
  if (adjusted >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
  if (adjusted < -10) return static_cast<uint16_t>(sign);
  if (adjusted <= 0) {
    const uint32_t normalized = mantissa | 0x800000u;
    const unsigned shift = static_cast<unsigned>(14 - adjusted);
    uint32_t rounded = normalized >> shift;
    const uint32_t remainder = normalized & ((1u << shift) - 1u);
    const uint32_t halfway = 1u << (shift - 1);
    if (remainder > halfway || (remainder == halfway && (rounded & 1))) ++rounded;
    return static_cast<uint16_t>(sign | rounded);
  }
  uint32_t rounded = (static_cast<uint32_t>(adjusted) << 10) | (mantissa >> 13);
  const uint32_t remainder = mantissa & 0x1fffu;
  if (remainder > 0x1000u || (remainder == 0x1000u && (rounded & 1))) ++rounded;
  return static_cast<uint16_t>(sign | rounded);
}
inline float HalfToFloat(uint16_t value) {
  const bool negative = (value & 0x8000u) != 0;
  const unsigned exponent = (value >> 10) & 31u;
  const unsigned mantissa = value & 1023u;
  float result;
  if (exponent == 0) result = std::ldexp(static_cast<float>(mantissa), -24);
  else if (exponent == 31) result = mantissa ? std::numeric_limits<float>::quiet_NaN()
                                           : std::numeric_limits<float>::infinity();
  else result = std::ldexp(static_cast<float>(1024 + mantissa), static_cast<int>(exponent) - 25);
  return negative ? -result : result;
}
enum class TensorType { Half, Int32 };
struct TensorSpec {
  std::string name;
  TensorType type;
  std::vector<int64_t> dimensions;
};
inline std::vector<int64_t> KeyShape(int64_t length) { return {8, 1, 64, length}; }
inline std::vector<int64_t> ValueShape(int64_t length) { return {8, 1, length, 64}; }
inline std::vector<TensorSpec> CrossSpecs() {
  std::vector<TensorSpec> result;
  for (int i = 0; i < 6; ++i) {
    result.push_back({"k_cache_cross_" + std::to_string(i), TensorType::Half, KeyShape(1500)});
    result.push_back({"v_cache_cross_" + std::to_string(i), TensorType::Half, ValueShape(1500)});
  }
  return result;
}
inline std::vector<TensorSpec> SelfSpecs(const std::string& suffix) {
  std::vector<TensorSpec> result;
  for (int i = 0; i < 6; ++i) {
    result.push_back({"k_cache_self_" + std::to_string(i) + suffix, TensorType::Half, KeyShape(199)});
    result.push_back({"v_cache_self_" + std::to_string(i) + suffix, TensorType::Half, ValueShape(199)});
  }
  return result;
}
inline std::vector<TensorSpec> EncoderInputs() {
  return {{"input_features", TensorType::Half, {1, 80, 3000}}};
}
inline std::vector<TensorSpec> DecoderInputs() {
  std::vector<TensorSpec> result{{"input_ids", TensorType::Int32, {1, 1}},
                               {"position_ids", TensorType::Int32, {1}},
                               {"attention_mask", TensorType::Half, {1, 1, 1, 200}}};
  const auto self = SelfSpecs("_in");
  const auto cross = CrossSpecs();
  result.insert(result.end(), self.begin(), self.end());
  result.insert(result.end(), cross.begin(), cross.end());
  return result;
}
inline std::vector<TensorSpec> DecoderOutputs() {
  auto result = SelfSpecs("_out");
  result.push_back({"logits", TensorType::Half, {1, 51865, 1, 1}});
  return result;
}
inline bool MatchesSignature(const std::vector<TensorSpec>& actual,
                             const std::vector<TensorSpec>& expected) {
  if (actual.size() != expected.size()) return false;
  for (const auto& want : expected) {
    size_t matches = 0;
    for (const auto& got : actual) {
      if (got.name == want.name) {
        if (got.type != want.type || got.dimensions != want.dimensions) return false;
        ++matches;
      }
    }
    if (matches != 1) return false;
  }
  return true;
}
// Cache/mask contract from Qualcomm AI Hub hf_whisper v0.64.0 app.py.
// The graph shifts self caches. Never shift again on the host.
// English transcription + no timestamps are explicitly forced.
class DecodeState {
 public:
  static constexpr int32_t kEos = 50257;
  static constexpr size_t kVocabulary = 51865;
  static constexpr int32_t kMaxCalls = 199;
  DecodeState() { mask_.fill(FloatToHalf(-100.0f)); }
  int32_t InputToken() const { return input_token_; }
  int32_t Position() const { return position_; }
  const std::array<uint16_t, 200>& BeginStep() {
    if (done_) throw std::logic_error("decode already finished");
    mask_[199 - position_] = 0;
    return mask_;
  }
  bool Done() const { return done_; }
  const std::vector<int32_t>& Tokens() const { return tokens_; }
  void Advance(const uint16_t* logits, size_t count) {
    if (done_ || !logits || count != kVocabulary) throw std::invalid_argument("invalid decoder logits");
    if (position_ < 3) {
      constexpr std::array<int32_t, 4> prefix{50258, 50259, 50359, 50363};
      input_token_ = prefix[position_ + 1];
    } else {
      float best = -std::numeric_limits<float>::infinity();
      int32_t token = -1;
      // Other language/task/timestamp/special tokens are never emitted.
      for (int32_t i = 0; i <= kEos; ++i) {
        const float value = HalfToFloat(logits[i]);
        if (!std::isfinite(value)) throw std::invalid_argument("invalid decoder logits");
        if (value > best) { best = value; token = i; }
      }
      if (token == kEos) done_ = true;
      else { input_token_ = token; tokens_.push_back(token); }
    }
    if (++position_ == kMaxCalls) done_ = true;
  }
 private:
  int32_t position_ = 0;
  int32_t input_token_ = 50258;
  bool done_ = false;
  std::array<uint16_t, 200> mask_{};
  std::vector<int32_t> tokens_;
};
}  // namespace jarvis::whisperqnn
