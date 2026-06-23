/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  // Build-time only: scans the real dependency graph and generates R.raw.aboutlibraries, consumed by
  // the FOSS license screen (LicensesActivity). Fully GMS-free, so it applies to BOTH flavors (CI
  // dex-scan enforces the degoogled APK stays GMS-class-free). Replaces the GMS oss-licenses plugin.
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

android {
  namespace = "cc.grepon.relais"
  compileSdk = 35

  defaultConfig {
    applicationId = "cc.grepon.relais"
    minSdk = 31
    targetSdk = 35
    versionCode = 33
    versionName = "1.0.15"

    // The bundled LiteRT runtime ships native .so for 4 ABIs, but the litertlm LLM AAR only ships
    // arm64-v8a + x86_64 — so the node can't run on the others anyway. Match that set to avoid shipping
    // ~9 MB of armeabi-v7a/x86 TFLite libs that could never execute (and a latent ABI-mismatch trap).
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "cc.grepon.relais.RelaisApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  // Distribution flavor split. `full` is the Play-Store build (includes the Google-Mobile-Services-
  // pulling deps); `degoogled` is the IzzyOnDroid / self-hosted-F-Droid build that excludes ALL of
  // them. The GMS-touching code lives in src/full/ and is replaced by GMS-free stubs in src/degoogled/
  // (see RelaisAicore, AICoreModelHelper, ImageTextRecognizer). Inference is already non-GMS (bundled
  // litertlm + litert), so `degoogled` is a fully functional node — it just drops ML Kit OCR (#13) and
  // AICore/Gemini-Nano. (Third-party licenses are handled by the FOSS AboutLibraries screen in both.)
  flavorDimensions += listOf("dist", "policy")
  productFlavors {
    create("full") {
      dimension = "dist"
      // AICore (Gemini Nano) is available — see isAICoreSupported() / the RelaisAicore impl.
      buildConfigField("boolean", "SUPPORTS_AICORE", "true")
    }
    // Same applicationId as `full` (drop-in replacement for the alt-store channel); not installable
    // side-by-side with `full` on one device without an applicationIdSuffix (intentional).
    create("degoogled") {
      dimension = "dist"
      // No AICore: Gemini Nano needs Play Services. This flag makes isAICoreSupported() return false
      // regardless of device model, so AICORE models are filtered out of the catalog entirely (they
      // can't run on the GMS-free stub) — not just listed-then-perma-failed.
      buildConfigField("boolean", "SUPPORTS_AICORE", "false")
    }
    // Policy dimension (ventouxlabs channels). `open` = all power-user features (IzzyOnDroid /
    // GrapheneOS); `playsafe` = the (future) Google-Play-compliant subset. NOTE: this is
    // FOUNDATION-ONLY — playsafe currently equals open APART FROM the appId. A follow-up PR will add
    // src/playsafe/AndroidManifest.xml (tools:node="remove") to drop the risky perms/components and
    // will consume POLICY_OPEN (generated below but not yet read) to gate their entry points. Shipping
    // combos: fullOpen→IzzyOnDroid, fullPlaysafe→Play, degoogledOpen→GrapheneOS (degoogled+playsafe is
    // filtered out — see androidComponents below).
    create("open") {
      dimension = "policy"
      buildConfigField("boolean", "POLICY_OPEN", "true")
    }
    create("playsafe") {
      dimension = "policy"
      buildConfigField("boolean", "POLICY_OPEN", "false")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      // Unmocked android.* returns defaults in JVM unit tests (lets AllowedModel.toModel() run
      // without Robolectric). Tradeoff: masks accidental unmocked-Android calls — migrate
      // Android-touching tests to Robolectric (PR5).
      isReturnDefaultValues = true
      // Required for Robolectric to load AndroidManifest, resources, and assets when running
      // Context-touching tests as JVM unit tests (PR5).
      isIncludeAndroidResources = true
    }
  }
}

androidComponents {
  // Ship only 3 of the 4 dist×policy combos: drop degoogled+playsafe (GrapheneOS doesn't need the
  // Play-compliant subset — it sideloads the open build).
  beforeVariants(selector().withFlavor("dist" to "degoogled").withFlavor("policy" to "playsafe")) {
    it.enable = false
  }
  // appId follows the CHANNEL, not a flavor suffix — composing suffixes across two dimensions would
  // double-suffix the degoogled+open case. namespace stays cc.grepon.relais (no source-package churn).
  onVariants(selector().all()) { variant ->
    val dist = variant.productFlavors.firstOrNull { it.first == "dist" }?.second
    val policy = variant.productFlavors.firstOrNull { it.first == "policy" }?.second
    variant.applicationId.set(
      when {
        dist == "full" && policy == "playsafe" -> "com.ventouxlabs.relais"          // Play Store
        dist == "full" && policy == "open" -> "com.ventouxlabs.relais.izzy"         // IzzyOnDroid
        else -> "com.ventouxlabs.relais.degoogled"                                   // GrapheneOS (degoogled+open)
      }
    )
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  // Runtime self-signed TLS cert for the LAN endpoint: software RSA key + X509 builder. Needed
  // because AndroidKeyStore keys can't sign the TLS server handshake via conscrypt on-device.
  // jdk15to18 variant is the Android-compatible (non-multi-release) build.
  implementation("org.bouncycastle:bcpkix-jdk15to18:1.78.1")
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.litert) // bundled LiteRT runtime (no Play Services) — embeddings work de-Googled
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  // FOSS third-party-license screen (LicensesActivity) — no GMS, works in BOTH flavors. Replaces the
  // Play-Services OSS-licenses viewer; the plugin (above) generates R.raw.aboutlibraries. We use
  // core (pure-Kotlin parsing) ONLY and render the list ourselves — the prebuilt aboutlibraries-
  // compose-m3 UI calls a FlowRow overload absent from this project's Compose BOM (runtime crash),
  // and its 14.x fix needs compileSdk 36.
  implementation(libs.aboutlibraries.core)
  implementation(libs.androidx.exifinterface)
  // DocumentFile (SAF) was previously on the classpath transitively via Firebase; declare it
  // explicitly now that Firebase is removed.
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation(libs.moshi.kotlin)
  // Shared on-device SQLite (RelaisDatabase) — vector store (#4), session memory (#5), batch queue
  // (#14) all add entities/DAOs. Static singleton accessor, not Hilt-provided (matches the node layer).
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  // Home-screen widget (#3): Glance app-widget renderer. Pinned 1.1.1 (cached for --offline builds).
  implementation(libs.androidx.glance.appwidget)
  testImplementation(libs.androidx.room.testing)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation(libs.com.google.code.gson)
  testImplementation(libs.robolectric)
  // Real org.json implementation for JVM unit tests: Android stubs return null for put() under
  // isReturnDefaultValues=true, which breaks buildModelsResponse. This jar provides the real impl.
  testImplementation("org.json:json:20240303")
  testImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  // GMS deps — `full` flavor only. AICore/Gemini-Nano (genai-prompt) + the ML Kit Latin OCR
  // pipeline for share-in images (#13), which transitively pulls play-services-mlkit-text-recognition
  // (NOT GMS-free). `degoogled` excludes both; the touching code is stubbed out in src/degoogled/.
  "fullImplementation"(libs.mlkit.genai.prompt)
  "fullImplementation"(libs.mlkit.text.recognition)
  // On-device image generation (sd.cpp/Vulkan) — feature #16, FULL FLAVOR ONLY (#16 PR-A). Used only
  // from the process-isolated :imagegen service (src/full/imagegen/), never the node process. Two
  // excludes (the set the plan scopes for PR-A): `sentence-embeddings` drags in onnxruntime (we have
  // our own embedder); llmedge's `io.ktor` is only its HF-download path (we provision via
  // ModelSpec.localFile, and Relais's own ktor 3.4.3 is a separate direct dep, untouched by this).
  // NOTE: llmedge also transitively pulls features image-gen doesn't use — com.tom-roush:pdfbox-android,
  // com.google.mlkit:image-labeling, kotlinx-coroutines-play-services — left in for now (full APK bloat
  // only; all isolated to `full`, so degoogled stays GMS=0). Trimming them needs an on-device
  // generate-still-works check (a wrong exclude → NoClassDefFoundError in :imagegen) — a PR-D footnote.
  "fullImplementation"(libs.llmedge) {
    exclude(group = "io.gitlab.shubham0204", module = "sentence-embeddings")
    exclude(group = "io.ktor")
  }
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}

// Room exports its schema JSON (committed under app/schemas) so future migrations (#4/#5/#14) are
// diffable and migration-testable.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
