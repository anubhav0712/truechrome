// TrueChrome — Root Build Configuration
// =========================================
// This is the project-level build file. It declares plugins used across all modules
// but does NOT apply them here (they are applied in the :app module).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
