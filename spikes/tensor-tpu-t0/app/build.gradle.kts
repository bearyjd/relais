plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.grepon.relais.spike.tensortpu"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.grepon.relais.spike.tensortpu"
        minSdk = 31 // matches the google_tensor_runtime module shipped in the v2.1.1 NPU zip
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // libLiteRtDispatch_GoogleTensor.so lands in src/main/jniLibs/arm64-v8a via ./fetch-npu-libs.sh
    // (kept out of git). Bundling it here instead of as a Play dynamic-feature module is deliberate:
    // the module manifest sets fusing=true, so this is equivalent to a fused APK — and it removes
    // Play Feature Delivery from the spike (docs/tensor-tpu-spike-plan.md, 2026-07-09 update).
}

dependencies {
    // PINNED: 2.1.1 is the only public LiteRT release whose NPU dispatcher exists and matches.
    // 2.1.2–2.1.5 ship no dispatcher; mixing versions → silent CPU fallback or SIGSEGV (LiteRT #7787).
    implementation("com.google.ai.edge.litert:litert:2.1.1")
}
