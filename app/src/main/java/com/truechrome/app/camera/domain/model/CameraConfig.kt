package com.truechrome.app.camera.domain.model

import android.util.Size

/**
 * CameraConfig — Immutable snapshot of the camera device's hardware capabilities.
 *
 * This is queried once when the camera is opened and passed to all layers
 * that need to know what the hardware supports. By capturing this upfront,
 * we avoid repeated CameraCharacteristics lookups (which are not free on some HALs).
 *
 * DETERMINISM:
 * These values come from the hardware and never change for a given device.
 * The same camera ID always produces the same CameraConfig.
 */
data class CameraConfig(
    /** The camera device ID (e.g., "0" for rear) */
    val cameraId: String,

    /** Whether the device supports LEVEL_3 (required for YUV reprocessing) */
    val isLevel3: Boolean,

    /** Whether YUV_420_888 reprocessing is available */
    val supportsYuvReprocessing: Boolean,

    /** Whether 10-bit HDR output is supported */
    val supports10BitHdr: Boolean,

    /** The recommended 10-bit dynamic range profile, if available */
    val tenBitProfile: Long?,

    /** Best preview size matching the display aspect ratio */
    val previewSize: Size,

    /** Best capture size (maximum resolution for JPEG output) */
    val captureSize: Size,

    /** Best YUV size for the ZSL ring buffer */
    val yuvSize: Size,

    /** Sensor orientation (0, 90, 180, 270) */
    val sensorOrientation: Int,

    /** Whether manual post-processing control is available */
    val supportsManualPostProcessing: Boolean,

    /** Maximum number of simultaneous output surfaces */
    val maxOutputSurfaces: Int,

    /** Optical Black level pattern for RGGB (used for strict shadow clamping) */
    val sensorBlackLevelPattern: IntArray?,

    /** Whether Camera2 Extension Night Mode is supported */
    val supportsNightExtension: Boolean = false,

    /** Whether Camera2 Extension HDR Mode is supported */
    val supportsHdrExtension: Boolean = false
)
