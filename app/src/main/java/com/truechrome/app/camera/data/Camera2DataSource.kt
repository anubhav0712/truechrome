package com.truechrome.app.camera.data

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.truechrome.app.camera.domain.CameraException
import com.truechrome.app.camera.domain.model.CameraConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2DataSource — Low-level wrapper around the Camera2 API.
 *
 * This class is the ONLY place in the entire codebase that directly touches
 * Camera2 APIs. Every other layer works through the CameraRepository interface.
 *
 * KEY DESIGN DECISIONS:
 *
 * 1. CALLBACKS → COROUTINES:
 *    Camera2 is entirely callback-based. We bridge every callback to a suspend function
 *    using suspendCancellableCoroutine. This gives us:
 *    - Structured concurrency (auto-cancel on ViewModel clear)
 *    - Linear, readable code (no callback hell)
 *    - Proper exception propagation
 *
 * 2. SINGLE HANDLER THREAD:
 *    All Camera2 callbacks run on the injected "CameraHandler" thread.
 *    This serializes all callbacks and eliminates race conditions between
 *    onOpened/onDisconnected/onError/onConfigured/onCaptureCompleted.
 *
 * 3. EXPLICIT RESOURCE CLEANUP:
 *    Camera devices, sessions, and surfaces MUST be explicitly closed.
 *    We track every opened resource and close them in reverse order.
 *    suspendCancellableCoroutine.invokeOnCancellation handles async cleanup.
 *
 * 4. DETERMINISTIC AE/AWB:
 *    Once preview starts, we lock AE and AWB after convergence.
 *    This ensures identical exposure/white-balance across all burst frames.
 */
@Singleton
class Camera2DataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: CameraManager,
    @Named("CameraHandler") private val cameraHandler: Handler,
    private val zslRingBuffer: com.truechrome.app.camera.data.ZslRingBuffer
) {
    companion object {
        private const val TAG = "Camera2DataSource"
    }

    // ── Active resources (tracked for cleanup) ──
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var zslImageReader: android.media.ImageReader? = null

    /**
     * Queries the rear camera's capabilities and returns a CameraConfig.
     *
     * This is a pure read operation — no camera device is opened.
     * The result is deterministic for a given device.
     */
    fun queryCameraConfig(): CameraConfig {
        val cameraId = findRearCameraId()
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // Query hardware level
        val hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val isLevel3 = hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3

        // Query capabilities
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        val supportsYuvReprocessing = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING
        )
        val supportsManualPostProcessing = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        )

        // Query 10-bit HDR support (API 33+)
        var supports10BitHdr = false
        var tenBitProfile: Long? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            supports10BitHdr = capabilities.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT
            )
            if (supports10BitHdr) {
                tenBitProfile = characteristics.get(
                    CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE
                )
            }
        }

        // Query output sizes
        val streamConfigMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: throw CameraException("No stream configuration map available")

        val previewSize = chooseBestSize(
            streamConfigMap.getOutputSizes(android.graphics.SurfaceTexture::class.java),
            targetAspectRatio = 4.0 / 3.0,
            maxWidth = 1920
        )

        val captureSize = chooseBestSize(
            streamConfigMap.getOutputSizes(ImageFormat.JPEG),
            targetAspectRatio = 4.0 / 3.0,
            maxWidth = Int.MAX_VALUE  // Maximum resolution for capture
        )

        val yuvSize = chooseBestSize(
            streamConfigMap.getOutputSizes(ImageFormat.YUV_420_888),
            targetAspectRatio = 4.0 / 3.0,
            maxWidth = captureSize.width  // Match capture resolution
        )

        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Extract Optical Black level clamping pattern
        val blackLevelPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let {
            intArrayOf(it.getOffsetForIndex(0, 0), it.getOffsetForIndex(1, 0), it.getOffsetForIndex(0, 1), it.getOffsetForIndex(1, 1))
        }

        // Query MediaTek Imagiq ISP Extensions
        var supportsNightExtension = false
        var supportsHdrExtension = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val extensionChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
            val availableExtensions = extensionChars.supportedExtensions
            supportsNightExtension = availableExtensions.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
            supportsHdrExtension = availableExtensions.contains(CameraExtensionCharacteristics.EXTENSION_HDR)
        }

        // Max output surfaces (LEVEL_3 supports at least 4)
        val maxOutputSurfaces = if (isLevel3) 4 else 3

        return CameraConfig(
            cameraId = cameraId,
            isLevel3 = isLevel3,
            supportsYuvReprocessing = supportsYuvReprocessing,
            supports10BitHdr = supports10BitHdr,
            tenBitProfile = tenBitProfile,
            previewSize = previewSize,
            captureSize = captureSize,
            yuvSize = yuvSize,
            sensorOrientation = sensorOrientation,
            supportsManualPostProcessing = supportsManualPostProcessing,
            maxOutputSurfaces = maxOutputSurfaces,
            sensorBlackLevelPattern = blackLevelPattern,
            supportsNightExtension = supportsNightExtension,
            supportsHdrExtension = supportsHdrExtension
        )
    }

    /**
     * Opens the camera device.
     *
     * Uses suspendCancellableCoroutine to bridge the async StateCallback.
     * If the coroutine is cancelled (e.g., Activity goes to background),
     * the camera device is immediately closed via invokeOnCancellation.
     *
     * @return The opened CameraDevice
     * @throws CameraException if opening fails
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun openCamera(cameraId: String): CameraDevice {
        return suspendCancellableCoroutine { continuation ->
            try {
                val callback = object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        Log.d(TAG, "Camera opened: ${device.id}")
                        cameraDevice = device
                        if (continuation.isActive) {
                            continuation.resume(device)
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        Log.w(TAG, "Camera disconnected: ${device.id}")
                        device.close()
                        cameraDevice = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CameraException("Camera disconnected during open")
                            )
                        }
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error during open: $error")
                        device.close()
                        cameraDevice = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CameraException.fromDeviceError(error)
                            )
                        }
                    }
                }

                cameraManager.openCamera(cameraId, callback, cameraHandler)

                // CRITICAL: Clean up if the coroutine is cancelled
                // (e.g., user navigates away during camera open)
                continuation.invokeOnCancellation {
                    Log.w(TAG, "Camera open cancelled — closing device")
                    cameraDevice?.close()
                    cameraDevice = null
                }
            } catch (e: SecurityException) {
                continuation.resumeWithException(
                    CameraException("Camera permission not granted", e)
                )
            } catch (e: CameraAccessException) {
                continuation.resumeWithException(
                    CameraException("Cannot access camera: ${e.message}", e)
                )
            }
        }
    }

    /**
     * Creates a capture session and starts the preview repeating request.
     *
     * AE/AWB LOCKING STRATEGY (Determinism):
     * 1. Start with AE/AWB in AUTO mode for initial convergence
     * 2. Wait for convergence via CaptureCallback
     * 3. Lock AE (CONTROL_AE_LOCK = true) and AWB (CONTROL_AWB_LOCK = true)
     * 4. All subsequent frames have identical exposure/WB — deterministic color
     *
     * @param previewSurface The surface to render preview frames onto
     */
    suspend fun createPreviewSession(previewSurface: Surface) {
        val device = cameraDevice
            ?: throw CameraException("Camera device not open")

        val config = queryCameraConfig()

        // Create ZSL ImageReader
        // maxImages MUST be > ZslRingBuffer capacity to prevent starvation
        // If ZSL capacity is 8, maxImages=14 gives the camera HAL 6 free buffers to work with.
        val reader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.media.ImageReader.newInstance(
                config.yuvSize.width,
                config.yuvSize.height,
                ImageFormat.YUV_420_888,
                14,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            )
        } else {
            android.media.ImageReader.newInstance(
                config.yuvSize.width,
                config.yuvSize.height,
                ImageFormat.YUV_420_888,
                14 
            )
        }
        zslImageReader = reader

        reader.setOnImageAvailableListener({ ir ->
            try {
                val image = ir.acquireNextImage()
                if (image != null) {
                    // Extract timestamp
                    val timestamp = image.timestamp
                    zslRingBuffer.onImageAvailable(image, timestamp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring ZSL image", e)
            }
        }, cameraHandler)

        // Build the preview request
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(reader.surface)

            // ── DETERMINISTIC CAPTURE SETTINGS ──

            // Start with auto AE/AWB for convergence, then we'll lock them
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // Continuous autofocus for viewfinder
            set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // STRIP OEM post-processing for raw, unprocessed frames:
            // We apply our own color science via the GPU shader pipeline.
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_FAST
            )
            // Disable OEM tone mapping — we do our own via film simulation curves
            set(
                CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_FAST
            )
        }

        previewRequestBuilder = builder

        // Create the capture session
        val session = suspendCancellableCoroutine { continuation ->
            try {
                val stateCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session configured")
                        captureSession = session
                        if (continuation.isActive) {
                            continuation.resume(session)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Preview session configuration failed")
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                CameraException("Failed to configure preview session")
                            )
                        }
                    }

                    override fun onClosed(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session closed")
                        captureSession = null
                    }
                }

                // Use SessionConfiguration for API 28+
                val previewOutput = OutputConfiguration(previewSurface)
                val zslOutput = OutputConfiguration(reader.surface)
                
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutput, zslOutput),
                    { it.run() },  // Direct executor (runs on camera handler thread)
                    stateCallback
                )

                device.createCaptureSession(sessionConfig)

                continuation.invokeOnCancellation {
                    Log.w(TAG, "Session creation cancelled")
                    captureSession?.close()
                    captureSession = null
                }
            } catch (e: CameraAccessException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        CameraException("Cannot create session: ${e.message}", e)
                    )
                }
            }
        }

        // Start the repeating preview request
        try {
            session.setRepeatingRequest(
                builder.build(),
                createAeLockingCallback(),
                cameraHandler
            )
            Log.d(TAG, "Preview repeating request started")
        } catch (e: CameraAccessException) {
            throw CameraException("Cannot start preview: ${e.message}", e)
        }
    }

    /**
     * Creates a CaptureCallback that locks AE/AWB after convergence.
     *
     * FLOW:
     * 1. Monitor CONTROL_AE_STATE for CONVERGED
     * 2. When AE converges, set CONTROL_AE_LOCK = true
     * 3. Monitor CONTROL_AWB_STATE for CONVERGED
     * 4. When AWB converges, set CONTROL_AWB_LOCK = true
     * 5. All subsequent frames now have locked, deterministic exposure/WB
     */
    private fun createAeLockingCallback(): CameraCaptureSession.CaptureCallback {
        var aeLocked = false
        var awbLocked = false

        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Lock AE after convergence
                if (!aeLocked) {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                        Log.d(TAG, "AE converged — locking")
                        previewRequestBuilder?.let { builder ->
                            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                            try {
                                session.setRepeatingRequest(
                                    builder.build(), this, cameraHandler
                                )
                                aeLocked = true
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, "Failed to lock AE", e)
                            }
                        }
                    }
                }

                // Lock AWB after convergence
                if (!awbLocked) {
                    val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                    if (awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED) {
                        Log.d(TAG, "AWB converged — locking")
                        previewRequestBuilder?.let { builder ->
                            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                            try {
                                session.setRepeatingRequest(
                                    builder.build(), this, cameraHandler
                                )
                                awbLocked = true
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, "Failed to lock AWB", e)
                            }
                        }
                    }
                }
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.w(TAG, "Preview capture failed: reason=${failure.reason}")
                // Don't crash — preview failures are transient and self-recovering
            }
        }
    }

    /**
     * Stops the preview and closes the session.
     * Does NOT close the camera device.
     */
    fun stopPreview() {
        try {
            captureSession?.stopRepeating()
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Error stopping repeating request", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Session already closed when stopping preview", e)
        }
        captureSession?.close()
        captureSession = null
        previewRequestBuilder = null
    }

    /**
     * Closes the camera device and releases ALL resources.
     *
     * ORDERING IS CRITICAL:
     * 1. Stop repeating request (prevents new callbacks)
     * 2. Close session (releases session-level resources)
     * 3. Close device (releases device-level resources)
     *
     * Reversing this order can cause crashes on some HAL implementations.
     */
    fun closeCamera() {
        Log.d(TAG, "Closing camera — releasing all resources")
        stopPreview()
        cameraDevice?.close()
        cameraDevice = null
        zslImageReader?.close()
        zslImageReader = null
        zslRingBuffer.clear()
    }

    /**
     * Triggers autofocus at normalized coordinates.
     * After focus locks, fires a haptic via the callback.
     */
    suspend fun triggerAutofocus(x: Float, y: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        // Cancel any ongoing AF
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        try {
            session.capture(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Failed to cancel AF", e)
            return
        }

        // Trigger new AF
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

        suspendCancellableCoroutine { continuation ->
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) {
                        // Reset trigger
                        builder.set(
                            CaptureRequest.CONTROL_AF_TRIGGER,
                            CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                        )
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)  // Don't crash on AF failure
                    }
                }
            }

            try {
                session.capture(builder.build(), callback, cameraHandler)
            } catch (e: CameraAccessException) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // Private helpers
    // ══════════════════════════════════════════════════════

    /**
     * Finds the rear-facing camera ID.
     * On devices with multiple rear cameras, prefers the primary (widest).
     */
    private fun findRearCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        throw CameraException("No rear-facing camera found")
    }

    /**
     * Chooses the best output size matching the target aspect ratio.
     *
     * STRATEGY:
     * 1. Filter sizes by maximum width constraint
     * 2. Among matching aspect ratios, pick the largest
     * 3. If no exact aspect ratio match, pick the closest
     *
     * This is deterministic — the same device always returns the same sizes
     * in the same order from CameraCharacteristics.
     */
    private fun chooseBestSize(
        sizes: Array<Size>,
        targetAspectRatio: Double,
        maxWidth: Int
    ): Size {
        val tolerance = 0.02  // 2% aspect ratio tolerance

        // Filter by max width and sort by area (largest first)
        val candidates = sizes
            .filter { it.width <= maxWidth }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }

        // Prefer exact aspect ratio match
        val exactMatch = candidates.firstOrNull { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            Math.abs(ratio - targetAspectRatio) < tolerance
        }

        if (exactMatch != null) return exactMatch

        // Fallback: largest available size
        return candidates.firstOrNull()
            ?: throw CameraException("No suitable output size found")
    }
}
