// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the
// Kotlin Android plugin is only put on the classpath here to pin the KGP version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
