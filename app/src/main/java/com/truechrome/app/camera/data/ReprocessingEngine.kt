package com.truechrome.app.camera.data

import android.content.Context
import android.media.Image
import android.media.ImageWriter
import android.os.Handler
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * ReprocessingEngine — Wraps Camera2 YUV_420_888 -> JPEG hardware reprocessing.
 *
 * WHY REPROCESSING?
 * Software JPEG encoding is slow and CPU-intensive. The camera ISP contains
 * dedicated hardware for JPEG compression. Reprocessing allows us to take our
 * custom fused YUV frame (from the BurstEngine), push it BACK into the camera
 * hardware, and have the ISP encode it to JPEG instantly.
 *
 * REQUIREMENT:
 * This requires CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 and
 * REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING.
 */
@Singleton
class ReprocessingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("CameraHandler") private val cameraHandler: Handler
) {
    companion object {
        private const val TAG = "ReprocessingEngine"
    }

    private var imageWriter: ImageWriter? = null
    
    /**
     * Initializes the ImageWriter bound to the reprocessing input surface.
     * 
     * @param inputSurface The surface created by CameraDevice.createReprocessableCaptureSession
     * @param maxImages Maximum number of images that can be written simultaneously
     */
    fun initialize(inputSurface: Surface, maxImages: Int = 2) {
        Log.d(TAG, "Initializing ReprocessingEngine")
        // ImageWriter takes ownership of the surface and allows us to push Images to it
        imageWriter = ImageWriter.newInstance(inputSurface, maxImages)
    }

    /**
     * Submits an Image to the camera ISP for reprocessing.
     * 
     * @param image The fused/processed YUV Image. Ownership is transferred to the ISP.
     */
    fun submitForReprocessing(image: Image) {
        val writer = imageWriter
        if (writer == null) {
            Log.e(TAG, "Cannot reprocess: ImageWriter not initialized")
            image.close() // Must close to prevent leaks
            return
        }

        try {
            Log.d(TAG, "Submitting frame for hardware JPEG reprocessing")
            // queueInputImage transfers ownership of the Image to the ImageWriter
            writer.queueInputImage(image)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue image for reprocessing", e)
            image.close()
        }
    }

    /**
     * Releases the ImageWriter.
     */
    fun release() {
        Log.d(TAG, "Releasing ReprocessingEngine")
        imageWriter?.close()
        imageWriter = null
    }
}
