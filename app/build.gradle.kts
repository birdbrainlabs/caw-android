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

    // The speech model is read straight out of the APK; do not zip it.
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    // sherpa-onnx (k2-fsa). The Android AAR is published as a GitHub release asset
    // (sherpa-onnx-<version>.aar), not on Maven Central: put it at app/libs/sherpa-onnx.aar
    // (see README.md, "Build") and this block uses it. The Maven coordinate below is only a
    // fallback and does not resolve today. The Kotlin API used is com.k2fsa.sherpa.onnx.*,
    // which ships inside that AAR.
    val localSherpa = file("libs/sherpa-onnx.aar")
    if (localSherpa.exists()) {
        implementation(files(localSherpa))
    } else {
        implementation(libs.sherpa.onnx)
    }

    // WebViewAssetLoader: serves assets/crumb/ at an https origin so the page's CSP 'self' works.
    implementation(libs.androidx.webkit)

    // QR scanning for Send to computer pairing (no ML Kit / Play services needed).
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation(libs.org.json)
}
