package com.truechrome.app.camera.domain

import android.view.Surface
import com.truechrome.app.camera.domain.model.CameraConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * CameraRepository — Clean Architecture interface for all camera operations.
 *
 * WHY AN INTERFACE:
 * 1. Decouples domain/presentation layers from Camera2 API implementation details
 * 2. Enables unit testing of ViewModel/UseCases with a mock repository
 * 3. Allows swapping implementations (e.g., fake camera for UI testing)
 *
 * All methods are suspend functions or return Flows — no Camera2 callbacks leak out.
 */
interface CameraRepository {

    /** Observable camera configuration. Null until camera is opened. */
    val cameraConfig: StateFlow<CameraConfig?>

    /** Whether the camera is currently open and previewing */
    val isOpen: StateFlow<Boolean>

    /**
     * Opens the rear camera, queries capabilities, and prepares for preview.
     *
     * @return CameraConfig with the device's capabilities
     * @throws CameraException if the camera cannot be opened
     */
    suspend fun openCamera(): CameraConfig

    /**
     * Starts the preview stream on the given surface.
     *
     * The surface is typically a SurfaceTexture from the OpenGL pipeline (Phase 3).
     * AE and AWB are locked after initial convergence for deterministic output.
     *
     * @param previewSurface The surface to render preview frames to
     */
    suspend fun startPreview(previewSurface: Surface)

    /**
     * Stops the preview stream and releases the capture session.
     * Does NOT close the camera device — call closeCamera() for full cleanup.
     */
    suspend fun stopPreview()

    /**
     * Closes the camera device and releases ALL resources.
     * After this call, openCamera() must be called again to use the camera.
     */
    suspend fun closeCamera()

    /**
     * Triggers autofocus at the given normalized coordinates (0..1, 0..1).
     * Returns when focus is locked or fails.
     */
    suspend fun triggerAutofocus(x: Float, y: Float)

    /** Tick flow that emits whenever TrackingData is updated */
    val trackingTickFlow: StateFlow<Int>

    /** Reusable tracking data object */
    val trackingData: com.truechrome.app.camera.domain.model.TrackingData
}

/**
 * CameraException — Domain-level camera error.
 * Wraps Camera2 error codes into human-readable messages.
 */
class CameraException(
    message: String,
    cause: Throwable? = null,
    val errorCode: Int = -1
) : Exception(message, cause) {

    companion object {
        fun fromDeviceError(error: Int): CameraException {
            val msg = when (error) {
                1 -> "Camera device is in use by another application"
                2 -> "Camera device could not be opened (max cameras exceeded)"
                3 -> "Camera device has been disabled by policy"
                4 -> "Camera device has encountered a fatal error"
                5 -> "Camera service has encountered a fatal error"
                else -> "Unknown camera error (code: $error)"
            }
            return CameraException(msg, errorCode = error)
        }
    }
}
