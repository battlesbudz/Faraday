// Weight-free test-only ORT plugin. It deliberately advertises no NPU and
// cannot execute any model. Never package this library in an Android artifact.
#include "onnxruntime_c_api.h"
namespace {
const OrtApi* api = nullptr;
const char* ORT_API_CALL Name(const OrtEpFactory*) noexcept { return "QNNExecutionProvider"; }
const char* ORT_API_CALL Vendor(const OrtEpFactory*) noexcept { return "JarvisHostContractTest"; }
const char* ORT_API_CALL Version(const OrtEpFactory*) noexcept { return "0.0.0"; }
uint32_t ORT_API_CALL VendorId(const OrtEpFactory*) noexcept { return 0; }
OrtStatus* ORT_API_CALL Devices(OrtEpFactory*, const OrtHardwareDevice* const*, size_t,
                              OrtEpDevice**, size_t, size_t* count) noexcept {
  *count = 0;
  return nullptr;
}
OrtStatus* ORT_API_CALL Create(OrtEpFactory*, const OrtHardwareDevice* const*,
                             const OrtKeyValuePairs* const*, size_t,
                             const OrtSessionOptions*, const OrtLogger*, OrtEp** out) noexcept {
  *out = nullptr;
  return api->CreateStatus(ORT_FAIL, "Test fixture cannot execute");
}
void ORT_API_CALL Release(OrtEpFactory*, OrtEp*) noexcept {}
OrtStatus* ORT_API_CALL Allocator(OrtEpFactory*, const OrtMemoryInfo*, const OrtKeyValuePairs*,
                                OrtAllocator** out) noexcept { *out = nullptr; return nullptr; }
void ORT_API_CALL FreeAllocator(OrtEpFactory*, OrtAllocator*) noexcept {}
OrtStatus* ORT_API_CALL Transfer(OrtEpFactory*, OrtDataTransferImpl** out) noexcept {
  *out = nullptr; return nullptr;
}
bool ORT_API_CALL StreamAware(const OrtEpFactory*) noexcept { return false; }
OrtStatus* ORT_API_CALL Stream(OrtEpFactory*, const OrtMemoryDevice*, const OrtKeyValuePairs*,
                             OrtSyncStreamImpl** out) noexcept { *out = nullptr; return nullptr; }
OrtStatus* ORT_API_CALL DomainCount(OrtEpFactory*, size_t* count) noexcept { *count = 0; return nullptr; }
OrtStatus* ORT_API_CALL Domains(OrtEpFactory*, OrtCustomOpDomain**, size_t) noexcept { return nullptr; }
}
extern "C" OrtStatus* CreateEpFactories(const char*, const OrtApiBase* base, const OrtLogger*,
                                       OrtEpFactory** factories, size_t capacity, size_t* count) noexcept {
  api = base->GetApi(ORT_API_VERSION);
  if (!capacity) return api->CreateStatus(ORT_FAIL, "No factory capacity");
  auto* factory = new OrtEpFactory{};
  factory->ort_version_supported = ORT_API_VERSION;
  factory->GetName = Name;
  factory->GetVendor = Vendor;
  factory->GetVendorId = VendorId;
  factory->GetVersion = Version;
  factory->GetSupportedDevices = Devices;
  factory->CreateEp = Create;
  factory->ReleaseEp = Release;
  factory->CreateAllocator = Allocator;
  factory->ReleaseAllocator = FreeAllocator;
  factory->CreateDataTransfer = Transfer;
  factory->IsStreamAware = StreamAware;
  factory->CreateSyncStreamForDevice = Stream;
  factory->GetNumCustomOpDomains = DomainCount;
  factory->GetCustomOpDomains = Domains;
  factories[0] = factory;
  *count = 1;
  return nullptr;
}
extern "C" OrtStatus* ReleaseEpFactory(OrtEpFactory* factory) noexcept {
  delete factory;
  return nullptr;
}
