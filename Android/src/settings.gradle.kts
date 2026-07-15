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

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    //        mavenLocal()
    google()
    mavenCentral()
    // JitPack hosts the sd.cpp/Vulkan image-gen AAR (io.github.aatricks:llmedge, full flavor only —
    // feature #16). Scoped to that one group so it can't silently shadow google()/mavenCentral().
    maven {
      url = uri("https://jitpack.io")
      // sd.cpp/Vulkan image-gen AAR (io.github.aatricks:llmedge, #16) + the on-device TTS runtime
      // (com.github.k2-fsa:sherpa-onnx, #168 — self-contained ONNX Runtime + espeak-ng + Piper/Kokoro,
      // published on JitPack, not Maven Central). Scoped to those two groups so it can't shadow
      // google()/mavenCentral().
      content {
        includeGroup("io.github.aatricks")
        includeGroup("com.github.k2-fsa")
      }
    }
  }
}

rootProject.name = "AI Edge Gallery"

include(":app")
