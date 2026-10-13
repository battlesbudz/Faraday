# Copy this file and the uniquely named C++ source into a NEW experimental
# Bazel package only after the owner grants a build slot. Not installed by prep.
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
cc_binary(
    name = "native_frontend_quality_probe",
    testonly = True,
    linkstatic = False,
    srcs = ["native_frontend_quality_probe.cc"],
    deps = [
        "@com_google_absl//absl/types:span",
        "@litert//litert/cc:litert_element_type",
        "@litert//litert/cc:litert_tensor_buffer",
        "//support/preprocessor:audio_preprocessor",
        "//support/preprocessor:audio_preprocessor_miniaudio",
        "//support/util:io_types",
    ],
)

# Install only into a NEW experimental package during an owner-approved slot.
cc_binary(
    name = "native_conversation_quality_probe",
    testonly = True,
    linkstatic = False,
    srcs = ["native_conversation_quality_probe.cc"],
    linkopts = ["-Wl,--export-dynamic-symbol=LiteRt*"],
    deps = [
        "@com_google_absl//absl/base:log_severity",
        "@com_google_absl//absl/log:globals",
        "@com_google_absl//absl/status",
        "@com_google_absl//absl/status:statusor",
        "@com_google_absl//absl/strings",
        "@com_google_absl//absl/time",
        "@litert//litert/cc:litert_element_type",
        "@litert//litert/cc:litert_tensor_buffer",
        "@nlohmann_json//:json",
        "//runtime/conversation",
        "//runtime/conversation:io_types",
        "//runtime/conversation:thinking_config",
        "//runtime/core:engine_impl",
        "//runtime/engine:engine_factory",
        "//runtime/engine:engine_interface",
        "//runtime/engine:engine_settings",
        "//runtime/engine:io_types",
        "//runtime/executor:audio_executor_settings",
        "//runtime/executor:executor_settings_base",
        "//runtime/executor:llm_executor_io_types",
        "//runtime/executor:llm_executor_settings",
        "//runtime/proto:sampler_params_cc_proto",
    ],
)


# Local research only. No model weights or audio are Bazel runfiles.
cc_binary(
    name = "pinned_encoder_probe",
    testonly = True,
    # Match SDK tests' dynamic linking so their pinned PIC dependencies are reused.
    linkstatic = False,
    srcs = ["pinned_encoder_probe.cc"],
    deps = [
        "@litert//litert/cc:litert_api_types",
        "@litert//litert/cc:litert_common",
        "@litert//litert/cc:litert_compiled_model",
        "@litert//litert/cc:litert_element_type",
        "@litert//litert/cc:litert_environment",
        "@litert//litert/cc:litert_expected",
        "@litert//litert/cc:litert_options",
        "@litert//litert/cc:litert_tensor_buffer",
    ],
)
