// TrueChrome — App Module Build Configuration
// =============================================
// This module contains the entire TrueChrome application.
// Key architectural decisions:
// - minSdk 28: Ensures Camera2 LEVEL_3 features and YUV reprocessing support
// - compileSdk 35: Access to latest Camera2 APIs including DynamicRangeProfiles
// - Hilt for DI: All ViewModels, Repositories, and UseCases are injectable
// - Compose: Entire UI is declarative, no XML layouts
// - No CameraX: We use raw Camera2 for maximum hardware control

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.truechrome.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.truechrome.app"
        // minSdk 28: Required for Camera2 advanced features, ImageWriter,
        // and scoped storage. Also ensures OpenGL ES 3.0+ availability.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug builds include LeakCanary for memory leak detection
            // during camera lifecycle stress testing
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Ensure GLSL shader files are included in the APK assets
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // ── Core Android ──
    implementation(libs.androidx.core.ktx)

    // ── Lifecycle (MVVM) ──
    // These are critical for tying camera operations to Activity lifecycle.
    // viewModelScope automatically cancels coroutines when ViewModel is cleared,
    // preventing camera resource leaks.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ── Jetpack Compose (UI) ──
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // ── Hilt (Dependency Injection) ──
    // WHY HILT: Camera2 has deep dependency chains (CameraManager → DataSource →
    // Repository → UseCase → ViewModel). Hilt ensures each layer is testable
    // by allowing mock injection, and prevents manual singleton management
    // which is a common source of memory leaks in camera apps.
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Camera2 API ──
    // WHY NOT CAMERAX: CameraX abstracts away the low-level controls we need:
    // - Manual ISP overrides (NOISE_REDUCTION_MODE_OFF, EDGE_MODE_OFF)
    // - Reprocessable capture sessions (YUV reprocessing)
    // - Direct SurfaceTexture binding for OpenGL pipeline
    // - 10-bit HDR output configuration (DynamicRangeProfiles)
    // Camera2 gives us direct hardware access at the cost of more boilerplate.
    implementation(libs.androidx.camera2)

    // ── Coroutines ──
    // Used to bridge Camera2's callback-based API to structured concurrency.
    // callbackFlow and suspendCancellableCoroutine are our primary patterns.
    implementation(libs.kotlinx.coroutines.android)

    // ── Debug: Memory Leak Detection ──
    // LeakCanary automatically detects Activity, Fragment, View, and ViewModel leaks.
    // Critical for camera apps where native Image buffers can leak silently.
    debugImplementation(libs.leakcanary)

    // ── Compose Debug Tooling ──
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // ── Testing ──
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
