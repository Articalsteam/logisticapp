// Root build file for the Articals Logistics Kotlin Multiplatform project.
// Plugins are declared here with `apply false` so that the version catalog stays
// the single source of truth for plugin versions (see gradle/libs.versions.toml).
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}
