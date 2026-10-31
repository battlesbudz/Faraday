import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Sherpa supplies the shared CPU runtime for Whisper, Piper, VAD and speaker models.
val sherpaSdk by configurations.creating { isTransitive = false }
// Plugin-only QNN reuses Sherpa ORT 1.27.1; never add a second ORT AAR.
val whisperQnnProvider by configurations.creating { isTransitive = false }
val whisperQnnRuntime by configurations.creating { isTransitive = false }
val whisperQnnDir = layout.buildDirectory.dir("whisper-qnn-runtime")
val prepareWhisperQnn by tasks.registering(Exec::class) {
    inputs.files(whisperQnnProvider, whisperQnnRuntime)
    inputs.file(rootProject.file("scripts/prepare_whisper_qnn.py"))
    outputs.dir(whisperQnnDir)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare_whisper_qnn.py"),
            whisperQnnProvider.singleFile, whisperQnnRuntime.singleFile, whisperQnnDir.get().asFile)
    }
}
val sherpaDir = layout.buildDirectory.dir("sherpa-sdk")
val sherpaNativeDir = layout.buildDirectory.dir("sherpa-native")
// The streaming API and checked lifecycle ABI must come from one reviewed build.
// A missing property deliberately fails; stock Maven 0.18.0 is not API-compatible.
fun requiredStreamingProperty(name: String): String = providers.gradleProperty(name).orNull
    ?.takeIf { it.isNotBlank() }
    ?: throw GradleException("Missing -P$name. Build the reviewed SDK with scripts/streaming-sdk first.")
val litertLmBridgeAar = rootProject.file(requiredStreamingProperty("litertLmBridgeAar"))
val litertLmBridgeProvenance = rootProject.file(requiredStreamingProperty("litertLmBridgeProvenance"))
val litertLmBridgeSha256 = requiredStreamingProperty("litertLmBridgeSha256")
val litertLmBridgeProvenanceSha256 = requiredStreamingProperty("litertLmBridgeProvenanceSha256")
val validateStreamingSdk by tasks.registering(Exec::class) {
    // Always rehash both class/native payloads; a stale up-to-date result is not provenance.
    outputs.upToDateWhen { false }
    doFirst {
        val arguments = mutableListOf<Any>("python3", rootProject.file("scripts/streaming-sdk/validate_artifact.py"),
            "--aar", litertLmBridgeAar, "--provenance", litertLmBridgeProvenance,
            "--expected-aar-sha256", litertLmBridgeSha256,
            "--expected-provenance-sha256", litertLmBridgeProvenanceSha256,
            "--require-digests", "--app-ndk", File(android.sdkDirectory, "ndk/27.2.12479018"))
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            arguments.addAll(listOf("--check-workflow", "--producer-attempt",
                requiredStreamingProperty("litertLmBridgeProducerAttempt")))
        }
        commandLine(arguments)
    }
}
val extractSherpa by tasks.registering(Exec::class) {
    inputs.files(sherpaSdk)
    inputs.file(rootProject.file("scripts/prepare_sherpa_sdk.py"))
    outputs.dir(sherpaDir)
    doFirst {
        commandLine("python3", rootProject.file("scripts/prepare_sherpa_sdk.py"),
            sherpaSdk.singleFile, sherpaDir.get().asFile)
    }
}
val buildSherpa by tasks.registering(Exec::class) {
    inputs.file(rootProject.file("scripts/build_sherpa.py"))
    inputs.file(rootProject.file("scripts/sherpa_jni_profile.py"))
    inputs.dir(rootProject.file("app/src/main/cpp/smartturn"))
    inputs.dir(rootProject.file("app/src/main/cpp/whisperqnn"))
    outputs.dir(sherpaNativeDir.map { it.dir("jni") })
    doFirst {
        commandLine("python3", rootProject.file("scripts/build_sherpa.py"), "--output", sherpaNativeDir.get().asFile,
            "--android-ndk", File(android.sdkDirectory, "ndk/27.2.12479018"))
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(validateStreamingSdk, extractSherpa, buildSherpa, prepareWhisperQnn) }
android {
    sourceSets.getByName("main").assets.srcDir(whisperQnnDir.map { it.dir("assets") })
    sourceSets.getByName("main").jniLibs.srcDir(sherpaNativeDir.map { it.dir("jni") })
    namespace = "com.battlesbudz.jarvis.v2"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    externalNativeBuild { cmake { path = file("src/main/cpp/microwakeword/CMakeLists.txt"); version = "3.22.1" } }
    val buildVersionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
    val buildVersionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1.0"
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "com.battlesbudz.jarvis.v2"
        minSdk = 30
        targetSdk = 35
        // Jarvis is currently shipped for modern ARM64 Android phones.
        // Excluding unused x86/32-bit native runtimes keeps the APK much smaller.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
        versionCode = buildVersionCode
        versionName = buildVersionName
        val sourceCommit = System.getenv("GITHUB_SHA")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "unavailable"
        buildConfigField("String", "SOURCE_COMMIT", "\"$sourceCommit\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Exercise the same signed, shrunk variant delivered to the user.
    testBuildType = "release"
    // Robolectric needs the merged manifest/resources (the ComponentActivity
    // declared for createAndroidComposeRule lives in the release manifest).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // Optional smaller sideload download. Android extracts these libraries at install;
    // the normal artifact keeps direct APK loading and lower installed storage.
    packaging.jniLibs.useLegacyPackaging = providers.gradleProperty("compactApk")
        .map { it.toBooleanStrict() }.getOrElse(false)
    packaging.jniLibs.excludes += setOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
    // These two reviewed ELF derivations must reach the APK byte-for-byte.
    // A second stripping pass can move their added read-only metadata segment.
    packaging.jniLibs.keepDebugSymbols.addAll(setOf(
        "**/libLiteRtTopKOpenClSampler.so",
        "**/libLiteRtTopKWebGpuSampler.so",
    ))
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    whisperQnnProvider("com.qualcomm.qti:onnxruntime-android-qnn:2.6.0@aar")
    whisperQnnRuntime("com.qualcomm.qti:qnn-runtime:2.50.0@aar")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // Slice 1 video calls: CameraX frame capture + LifecycleService.
    // 1.4.0 supplies a 16 KB-compatible image_processing_util_jni native library.
    val cameraXVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(files(litertLmBridgeAar).builtBy(validateStreamingSdk))
    // A local AAR carries no Maven POM/transitive metadata.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    sherpaSdk("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7@aar")
    implementation(files(sherpaDir.map { it.file("classes.jar") }).builtBy(extractSherpa))
    implementation(project(":phoneinference"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
    // BrowserSubmitJsTest runs the real extraction/submission JavaScript
    // (Jerry's review, build 1196) inside a minimal DOM shim.
    testImplementation("org.mozilla:rhino:1.8.0")
    testImplementation("org.json:json:20240303")
    // End-call visibility regression (round-two review): mounts the shipping
    // ConversationScreen/VoiceCallScreen composables on the JVM.
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
