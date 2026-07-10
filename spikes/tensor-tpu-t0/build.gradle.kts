// Toolchain pinned to match the main Relais Android project (Android/src):
// AGP 8.8.2 / Kotlin 2.2.0 / Gradle 8.10.2 — build with the main project's wrapper (see README).
plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
}
