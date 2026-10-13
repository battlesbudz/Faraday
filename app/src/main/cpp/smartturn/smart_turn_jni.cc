#include "smart_turn_session.h"
#include <jni.h>
#include <map>

namespace {
std::mutex registry_lock;
std::map<jlong, std::shared_ptr<jarvis::smartturn::Session>> sessions;
jlong next_handle = 1;
std::shared_ptr<jarvis::smartturn::Session> Find(jlong handle) {
  std::lock_guard<std::mutex> guard(registry_lock);
  auto item = sessions.find(handle);
  if (item == sessions.end()) throw std::runtime_error("closed Smart Turn session");
  return item->second;
}
void Failure(JNIEnv* env) {
  // Constant diagnostic only: no audio, local file paths, or model tensors in logs/errors.
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Smart Turn native operation failed");
}
}
#define JNI_METHOD(name) Java_com_battlesbudz_jarvis_v2_voice_smartturn_SmartTurnNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jbyteArray model) {
  try {
    if (!model || env->GetArrayLength(model) != 8679182) throw std::runtime_error("invalid model size");
    std::vector<jbyte> bytes(env->GetArrayLength(model));
    env->GetByteArrayRegion(model, 0, bytes.size(), bytes.data());
    if (env->ExceptionCheck()) return 0;
    auto session = std::make_shared<jarvis::smartturn::Session>(bytes.data(), bytes.size());
    std::lock_guard<std::mutex> guard(registry_lock);
    const auto handle = next_handle++;
    sessions.emplace(handle, std::move(session));
    return handle;
  } catch (...) { Failure(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(prepare)(JNIEnv* env, jobject, jlong handle, jlong token) {
  try { Find(handle)->Prepare(token); } catch (...) { Failure(env); }
}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI_METHOD(infer)(JNIEnv* env, jobject, jlong handle, jlong token, jfloatArray samples) {
  try {
    if (!samples || env->GetArrayLength(samples) <= 0 || env->GetArrayLength(samples) > 128000)
      throw std::runtime_error("invalid samples");
    std::vector<float> pcm(env->GetArrayLength(samples));
    env->GetFloatArrayRegion(samples, 0, pcm.size(), pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    const auto result = Find(handle)->Infer(token, pcm.data(), pcm.size());
    const jdouble fields[] = {result.probability, result.frontend_nanos, result.inference_nanos};
    auto output = env->NewDoubleArray(3);
    if (output) env->SetDoubleArrayRegion(output, 0, 3, fields);
    return output;
  } catch (...) { Failure(env); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(cancel)(JNIEnv*, jobject, jlong handle, jlong token) {
  try { Find(handle)->Cancel(token); } catch (...) { /* Closing/closed: no surviving publication. */ }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv*, jobject, jlong handle) {
  std::lock_guard<std::mutex> guard(registry_lock);
  sessions.erase(handle);
}
