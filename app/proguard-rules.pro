# TrueChrome ProGuard Rules
# =========================

# Keep Hilt-generated components
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Camera2 callback classes (used via reflection by the framework)
-keep class * extends android.hardware.camera2.CameraDevice$StateCallback { *; }
-keep class * extends android.hardware.camera2.CameraCaptureSession$StateCallback { *; }
-keep class * extends android.hardware.camera2.CameraCaptureSession$CaptureCallback { *; }

# Keep film simulation enum (used in EXIF metadata)
-keep enum com.truechrome.app.processing.color.FilmSimulation { *; }

# Keep data classes used for state (Kotlin data class methods can be stripped)
-keep class com.truechrome.app.camera.presentation.CameraUiState { *; }
-keep class com.truechrome.app.processing.color.FilmSimulationParams { *; }
