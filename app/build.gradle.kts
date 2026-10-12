plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.caw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.caw.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Phones are arm64. Keeps the APK from carrying four copies of onnxruntime.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // The owner's own word list (word-fixes.personal.json) is bundled from outside the repo when that
    // folder exists, so it never goes into git (docs/BUILD.md "Your own word list").
    val personalAssets = file("${System.getProperty("user.home")}/.config/caw-android/assets")
    if (personalAssets.isDirectory) {
        sourceSets.getByName("main").assets.srcDir(personalAssets)
    }

    // The speech model is read straight out of the APK; do not zip it.
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    // sherpa-onnx (k2-fsa). NOT CERTAIN this coordinate is on Maven Central: k2-fsa documents
    // the Android AAR mainly as a GitHub release asset (sherpa-onnx-<version>.aar). If Gradle
    // cannot resolve com.k2-fsa:sherpa-onnx, put the release AAR at app/libs/sherpa-onnx.aar
    // (see docs/BUILD.md) and this block uses it instead. The Kotlin API used is
    // com.k2fsa.sherpa.onnx.OnlineRecognizer, which ships inside that AAR.
    val localSherpa = file("libs/sherpa-onnx.aar")
    if (localSherpa.exists()) {
        implementation(files(localSherpa))
    } else {
        implementation(libs.sherpa.onnx)
    }

    // WebViewAssetLoader: serves assets/crumb/ at an https origin so the page's CSP 'self' works.
    implementation(libs.androidx.webkit)

    // QR scanning for Send to computer pairing (no ML Kit / Play services: the phone has none).
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.org.json)
}
