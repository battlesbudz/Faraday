#include "whisper_qnn_session.h"
#include "whisper_features.h"
#include <jni.h>
#include <limits>
#include <map>
#include <mutex>
namespace {
using jarvis::whisperqnn::Failure;
using jarvis::whisperqnn::Session;
std::mutex registry_lock;
std::map<jlong, std::shared_ptr<Session>> sessions;
jlong next_handle = 1;
std::shared_ptr<Session> Find(jlong handle) {
  std::lock_guard<std::mutex> guard(registry_lock);
  const auto item = sessions.find(handle);
  if (item == sessions.end()) throw Failure("whisper_qnn_cancelled");
  return item->second;  // Retains sessions until an in-flight Decode returns.
}
std::string String(JNIEnv* env, jstring value) {
  if (!value) throw Failure("whisper_qnn_invalid_directory");
  const auto* chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw Failure("whisper_qnn_invalid_directory");
  struct Release {
    JNIEnv* env; jstring value; const char* chars;
    ~Release() { env->ReleaseStringUTFChars(value, chars); }
  } release{env, value, chars};
  return chars;
}
void Throw(JNIEnv* env, const char* code) {
  if (env->ExceptionCheck()) return;
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type) env->ThrowNew(type, code);
}
}  // namespace
#define JNI_METHOD(name) Java_com_battlesbudz_jarvis_v2_voice_whisper_WhisperQnnNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env, jobject, jstring directory,
                                                      jstring library_directory, jstring dsp_directory) {
  try {
    auto session = std::make_shared<Session>(String(env, directory), String(env, library_directory), String(env, dsp_directory));
    std::lock_guard<std::mutex> guard(registry_lock);
    if (next_handle == std::numeric_limits<jlong>::max()) throw Failure("whisper_qnn_handle_limit");
    const auto handle = next_handle++;
    sessions.emplace(handle, std::move(session));
    return handle;
  } catch (const Failure& failure) { Throw(env, failure.what()); }
  catch (...) { Throw(env, "whisper_qnn_init_failed"); }
  return 0;
}
extern "C" JNIEXPORT jintArray JNICALL JNI_METHOD(decode)(JNIEnv* env, jobject, jlong handle, jfloatArray samples) {
  try {
    const auto session = Find(handle);
    if (!samples) throw Failure("whisper_qnn_invalid_pcm");
    const auto count = env->GetArrayLength(samples);
    if (count <= 0 || static_cast<size_t>(count) > jarvis::whisperqnn::kSamples)
      throw Failure("whisper_qnn_invalid_pcm");
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    const auto tokens = session->Decode(pcm.data(), pcm.size());
    auto result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    if (result && !tokens.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()), tokens.data());
    return result;
  } catch (const Failure& failure) { Throw(env, failure.what()); }
  catch (...) { Throw(env, "whisper_qnn_decode_failed"); }
  return nullptr;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(cancel)(JNIEnv*, jobject, jlong handle) {
  try { Find(handle)->Cancel(); } catch (...) {}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv*, jobject, jlong handle) {
  std::shared_ptr<Session> session;
  {
    std::lock_guard<std::mutex> guard(registry_lock);
    const auto item = sessions.find(handle);
    if (item == sessions.end()) return;
    session = std::move(item->second);
    sessions.erase(item);  // Revoke new borrowers before cancellation.
  }
  session->Cancel();  // No registry lock during provider work or destruction.
}
