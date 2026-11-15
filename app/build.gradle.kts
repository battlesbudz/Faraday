import java.util.zip.ZipFile
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
val webShellDir = layout.buildDirectory.dir("webshell")
// Code-and-export Unit 5 (scope section 5.1): CI builds the :webshell
// module's unsigned release APK and this task embeds it into the app's
// generated assets as shell/webshell.apk, following the generated-assets
// precedent above (the whisper QNN runtime). The exporter streams it back
// out on the phone; no prebuilt binary is committed to the repo and no
// developer machine builds anything.
val copyWebShell by tasks.registering(Copy::class) {
    dependsOn(":webshell:assembleRelease")
    from(rootProject.file("webshell/build/outputs/apk/release/webshell-release-unsigned.apk"))
    into(webShellDir.map { it.dir("shell") })
    rename { "webshell.apk" }
}
// The shipped app must actually carry the shell: fail the release build
// rather than ship a code mode whose export has nothing to export. The
// shell's size is printed so the CI log reports what the asset costs.
val verifyWebShellAsset by tasks.registering {
    group = "verification"
    description = "Fails unless the packaged release APK embeds assets/shell/webshell.apk."
    dependsOn("packageRelease")
    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    inputs.file(apkFile)
    doLast {
        val apk = apkFile.get().asFile
        check(apk.isFile) { "Release APK not found at ${apk.path}; packageRelease should have produced it." }
        ZipFile(apk).use { zip ->
            val shell = zip.getEntry("assets/shell/webshell.apk")
                ?: throw GradleException(
                    "assets/shell/webshell.apk is missing from ${apk.name}: " +
                        "the code-export shell did not reach the app APK (check copyWebShell)."
                )
            println("Web shell asset verified: ${shell.size} bytes inside ${apk.name} (${apk.length()} bytes).")
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyWebShellAsset) }
// LFM3-Omni U0 coexistence spike (~/workspace/lfm3-omni/DESIGN.md sections 7-8):
// Maven LiteRT 2.3.0 (CompiledModel API) now packages beside the reviewed
// LiteRT-LM 0.18.1 streaming bridge AAR. This check inventories every packaged
// ARM64 native library in the release APK and fails unless both runtimes are
// present and exactly one libLiteRt.so shipped: the Maven 2.3.0 build, proven
// by its published size. If a future bridge build ever ships its own
// libLiteRt.so, packaging fails on the duplicate path first; resolve that
// collision explicitly in the packaging rules, never by dropping a runtime.
val verifyLiteRtCoexistence by tasks.registering {
    group = "verification"
    description = "Fails unless the packaged release APK carries both the reviewed bridge runtime and Maven LiteRT 2.3.0, with exactly one libLiteRt.so."
    dependsOn("packageRelease")
    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    inputs.file(apkFile)
    doLast {
        val apk = apkFile.get().asFile
        check(apk.isFile) { "Release APK not found at ${apk.path}; packageRelease should have produced it." }
        ZipFile(apk).use { zip ->
            val libs = zip.entries().asSequence()
                .filter { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .map { it.name.removePrefix("lib/arm64-v8a/") to it.size }
                .toList()
            println("LiteRT coexistence inventory for ${apk.name} (${apk.length()} bytes), lib/arm64-v8a:")
            libs.sortedBy { it.first }.forEach { (name, size) -> println("  $name $size") }
            val names = libs.map { it.first }
            // The reviewed bridge AAR's dlopen roots (scripts/streaming-sdk
            // package_android_aar.py ROOTS + android-prebuilts.json).
            val bridgeRoots = listOf(
                "liblitertlm_jni.so", "libnative_audio_owner_jni.so",
                "libGemmaModelConstraintProvider.so", "libLiteRtGpuAccelerator.so",
                "libLiteRtOpenClAccelerator.so", "libLiteRtTopKOpenClSampler.so",
                "libLiteRtTopKWebGpuSampler.so", "libLiteRtWebGpuAccelerator.so",
                "libwebgpu_dawn.so",
            )
            // Maven LiteRT 2.3.0: litert (libLiteRt.so), litert-api
            // (liblitert_jni.so), litert-gpu (libLiteRtClGlAccelerator.so).
            val mavenLibs = listOf("libLiteRt.so", "liblitert_jni.so", "libLiteRtClGlAccelerator.so")
            val missing = (bridgeRoots + mavenLibs).filter { it !in names }
            check(missing.isEmpty()) {
                "LiteRT coexistence failed: missing packaged native libraries $missing in ${apk.name}."
            }
            val liteRtEntries = libs.filter { it.first == "libLiteRt.so" }
            check(liteRtEntries.size == 1 && liteRtEntries.single().second == 5_629_696L) {
                "LiteRT coexistence failed: expected exactly one libLiteRt.so of 5629696 bytes " +
                    "(Maven LiteRT 2.3.0, arm64-v8a); found $liteRtEntries."
            }
            println("LiteRT coexistence verified: bridge roots ${bridgeRoots.size}, Maven 2.3.0 libs ${mavenLibs.size}, one libLiteRt.so (Maven 2.3.0).")
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyLiteRtCoexistence) }
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(validateStreamingSdk, extractSherpa, buildSherpa, prepareWhisperQnn, copyWebShell) }
android {
    sourceSets.getByName("main").assets.srcDir(whisperQnnDir.map { it.dir("assets") })
    sourceSets.getByName("main").assets.srcDir(webShellDir)
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
            // LiteRT 2.3.0's Kotlin API (litert-api-2.3.0) carries Kotlin 2.4
            // metadata (mv = [2, 4, 0], verified from the published AAR);
            // this project's Kotlin 2.3.21 compiler reads it only with the
            // metadata check skipped (LFM3-Omni DESIGN.md section 8.1(b)).
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    // The sandbox rootfs asset must stay stored uncompressed: the installer
    // reads its length via openFd to compute the extraction fraction.
    androidResources { noCompress += "xz" }
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
    // Step 5: PreviewView for the call screen's live camera preview.
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(files(litertLmBridgeAar).builtBy(validateStreamingSdk))
    // A local AAR carries no Maven POM/transitive metadata.
    implementation("com.google.code.gson:gson:2.13.2")
    // LFM3-Omni (DESIGN.md section 7, unit U0): Maven LiteRT 2.3.0's
    // CompiledModel API packages beside the reviewed bridge AAR above; the
    // Omni path drives the .litertlm bundle sections directly because
    // LiteRT-LM cannot feed embeddings (DESIGN.md section 1). Coexistence is
    // proven by verifyLiteRtCoexistence; the only caller in U0 is the fenced
    // ai/OmniRuntime.kt stub.
    // The lifecycle exclusion is load-bearing (U0, CI run 1895):
    // litert-api 2.3.0's POM drags androidx.lifecycle:lifecycle-runtime
    // 2.10.0, whose bundled lint detector is binary-incompatible with
    // AGP 8.7.3's lint (IncompatibleClassChangeError on the Kotlin
    // analysis API's KaCallableMemberCall) and crashes
    // lintVitalAnalyzeRelease. The app keeps its own lifecycle 2.8.7;
    // litert-api references only long-stable lifecycle types
    // (Lifecycle, LifecycleOwner, LifecycleCoroutineScope), all present
    // in 2.8.7, so linkage is unaffected.
    // The ai-delivery exclusion is load-bearing (U0, CI run 1896):
    // litert-api's POM also drags com.google.android.play:ai-delivery,
    // whose play-services-basement pulls androidx.fragment:fragment
    // 1.1.0 onto the classpath, tripping the activity lint error
    // InvalidFragmentVersionForActivityResult (fragment < 1.3.0) in
    // lintVitalRelease. Faraday delivers its models itself and never
    // uses Play AI delivery, so the whole subtree stays out.
    implementation("com.google.ai.edge.litert:litert:2.3.0") {
        exclude(group = "androidx.lifecycle", module = "lifecycle-runtime")
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.0") {
        exclude(group = "androidx.lifecycle", module = "lifecycle-runtime")
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
    sherpaSdk("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7@aar")
    implementation(files(sherpaDir.map { it.file("classes.jar") }).builtBy(extractSherpa))
    implementation(project(":phoneinference"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Sandbox: pure-Java XZ decoder for the vendored rootfs installer
    // (org.tukaani:xz is public domain; see app/src/main/cpp/terminal/NOTICE.md).
    implementation("org.tukaani:xz:1.10")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Markdown parsing for assistant replies (issue #20): the maintained
    // CommonMark parser, pure JVM with no transitive dependencies. Only the
    // parsed tree is used; rendering to Compose lives in the ui package.
    implementation("org.commonmark:commonmark:0.32.0")
    // Code mode preview (code-and-export Unit 4): WebViewAssetLoader
    // serving the open project on the appassets origin. The
    // browser-automation WebView in actions/ does not use it and is
    // untouched; this is a separate WebView with a different job.
    implementation("androidx.webkit:webkit:1.14.0")
    // Code mode APK export (code-and-export Unit 5): on-device signing
    // of exported project APKs, APK Signature Scheme v2 only. The
    // version matches the AGP pin in the root build file (8.7.3), per
    // the scope's fact 7.
    implementation("com.android.tools.build:apksig:8.7.3")
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
