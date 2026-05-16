package com.truechrome.app.camera.data

import android.util.Log
import android.view.Surface
import com.truechrome.app.camera.domain.CameraException
import com.truechrome.app.camera.domain.CameraRepository
import com.truechrome.app.camera.domain.model.CameraConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraRepositoryImpl — Bridges Camera2DataSource to the domain layer.
 *
 * This layer adds:
 * 1. StateFlow exposure for reactive UI updates
 * 2. Error wrapping (Camera2 exceptions → CameraException)
 * 3. Lifecycle coordination (ensures operations happen in valid order)
 *
 * The repository does NOT make hardware decisions — it delegates to Camera2DataSource
 * and wraps the results in domain-friendly types.
 */
@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val dataSource: Camera2DataSource
) : CameraRepository {

    companion object {
        private const val TAG = "CameraRepository"
    }

    private val _cameraConfig = MutableStateFlow<CameraConfig?>(null)
    override val cameraConfig: StateFlow<CameraConfig?> = _cameraConfig.asStateFlow()

    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    override val trackingTickFlow: StateFlow<Int> = dataSource.trackingTickFlow
    
    override val trackingData: com.truechrome.app.camera.domain.model.TrackingData
        get() = dataSource.trackingData

    override suspend fun openCamera(): CameraConfig {
        try {
            // Step 1: Query capabilities (no device open needed)
            val config = dataSource.queryCameraConfig()
            _cameraConfig.value = config

            Log.d(TAG, buildString {
                append("Camera capabilities: ")
                append("level3=${config.isLevel3}, ")
                append("yuvReprocess=${config.supportsYuvReprocessing}, ")
                append("10bit=${config.supports10BitHdr}, ")
                append("preview=${config.previewSize}, ")
                append("capture=${config.captureSize}")
            })

            // Step 2: Open the camera device
            dataSource.openCamera(config.cameraId)
            _isOpen.value = true

            return config
        } catch (e: CameraException) {
            _isOpen.value = false
            throw e
        } catch (e: SecurityException) {
            _isOpen.value = false
            throw CameraException("Camera permission not granted", e)
        } catch (e: Exception) {
            _isOpen.value = false
            throw CameraException("Unexpected error opening camera: ${e.message}", e)
        }
    }

    override suspend fun startPreview(previewSurface: Surface) {
        if (!_isOpen.value) {
            throw CameraException("Cannot start preview: camera not open")
        }
        try {
            dataSource.createPreviewSession(previewSurface)
        } catch (e: CameraException) {
            throw e
        } catch (e: Exception) {
            throw CameraException("Failed to start preview: ${e.message}", e)
        }
    }

    override suspend fun stopPreview() {
        try {
            dataSource.stopPreview()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping preview (non-fatal)", e)
        }
    }

    override suspend fun closeCamera() {
        try {
            dataSource.closeCamera()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera (non-fatal)", e)
        } finally {
            _isOpen.value = false
            _cameraConfig.value = null
        }
    }

    override suspend fun triggerAutofocus(x: Float, y: Float) {
        if (!_isOpen.value) return
        try {
            dataSource.triggerAutofocus(x, y)
        } catch (e: Exception) {
            Log.w(TAG, "Autofocus failed (non-fatal)", e)
        }
    }
}
