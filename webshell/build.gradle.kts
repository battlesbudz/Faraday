plugins {
    id("com.android.application")
}

// Code-and-export Unit 5 (scope section 5.1): the export shell. A
// complete minimal Android app whose whole UI is one WebView; CI builds
// it unsigned and :app embeds it as assets/shell/webshell.apk (see
// copyWebShell in app/build.gradle.kts). At export time the app streams
// it back out, binary-patches the placeholder package name and label in
// its manifest, injects a project's files under assets/www/, and signs
// the result on the phone. The placeholders are sized to be the longest
// values any export will ever need: the package placeholder below is
// 64 characters and the label placeholder in the manifest is 48; the
// exporter refuses anything longer instead of truncating. Keep the
// applicationId literal in step with
// CodeApkExporter.PLACEHOLDER_PACKAGE_NAME.
android {
    namespace = "com.battlesbudz.faraday.webshell"
    compileSdk = 35

    defaultConfig {
        // 64 characters: "com.battlesbudz.faraday.app.p" + 35 filler digits.
        applicationId = "com.battlesbudz.faraday.app.p00000000000000000000000000000000000"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Deliberately unsigned: no signingConfig, so assembleRelease
            // produces webshell-release-unsigned.apk and the only
            // signature an export ever carries is the on-device one
            // applied by the exporter.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
