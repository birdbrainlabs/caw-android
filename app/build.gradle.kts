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
        versionCode = 2
        versionName = "0.2"
        // Phones are arm64. Keeps the APK from carrying four copies of onnxruntime.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Release signing key: kept outside the repo in ~/.config/caw-android/signing, its password passed in
    // CAW_KEYSTORE_PASSWORD (scripts/release.sh reads it from the macOS Keychain). Without both, release
    // builds come out unsigned.
    val releaseKeystore = file("${System.getProperty("user.home")}/.config/caw-android/signing/caw-release.p12")
    val keystorePassword = System.getenv("CAW_KEYSTORE_PASSWORD")
    if (releaseKeystore.isFile && !keystorePassword.isNullOrEmpty()) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storeType = "pkcs12"
            storePassword = keystorePassword
            keyAlias = "caw"
            keyPassword = keystorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    // folder exists, so it never goes into git (README, "Word fixes"). -Pcaw.public leaves it
    // out, for APKs other people download.
    val personalAssets = file("${System.getProperty("user.home")}/.config/caw-android/assets")
    val publicBuild = providers.gradleProperty("caw.public").isPresent
    if (personalAssets.isDirectory && !publicBuild) {
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
    // (README, "Build and run") and this block uses it instead. The Kotlin API used is
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
