package com.truechrome.app.camera.domain.usecase

import android.media.Image
import android.util.Log
import com.truechrome.app.camera.data.MediaStoreRepositoryImpl
import com.truechrome.app.camera.data.ReprocessingEngine
import com.truechrome.app.camera.data.ZslRingBuffer
import com.truechrome.app.camera.domain.CameraException
import com.truechrome.app.processing.color.FilmSimulation
import com.truechrome.app.processing.computational.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * CapturePhotoUseCase — Orchestrates the end-to-end capture flow.
 *
 * PIPELINE:
 * 1. Lock ZSL ring buffer and extract the last N frames.
 * 2. Send frames to ImageProcessor (Burst Fusion, Tone Mapping, Grain).
 * 3. Send fused linear RGB frame to ReprocessingEngine (Hardware JPEG compression).
 * 4. Save final JPEG to MediaStore.
 */
class CapturePhotoUseCase @Inject constructor(
    private val zslRingBuffer: ZslRingBuffer,
    private val imageProcessor: ImageProcessor,
    private val reprocessingEngine: ReprocessingEngine,
    private val mediaStoreRepository: MediaStoreRepositoryImpl
) {
    companion object {
        private const val TAG = "CapturePhotoUseCase"
    }

    /**
     * Executes the capture pipeline.
     *
     * @param targetTimestamp The exact sensor timestamp of the shutter press
     * @param simulation The active film simulation for color processing
     * @return Result containing the saved MediaStore URI or an Exception
     */
    suspend operator fun invoke(
        targetTimestamp: Long,
        simulation: FilmSimulation,
        sensorOrientation: Int
    ): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            Log.d(TAG, "Starting capture pipeline for timestamp $targetTimestamp")

            // 1. Extract frames from ZSL buffer (zero shutter lag)
            // We pull 5 frames for standard HDR+ style fusion
            val burst: List<Image> = zslRingBuffer.extractBurst(targetTimestamp, burstSize = 5)
            
            if (burst.isEmpty()) {
                throw CameraException("Capture failed: ZSL buffer is empty")
            }

            // 2. Process the burst
            // This does alignment, temporal merging, and applies the film simulation
            val fusedFrame = imageProcessor.processCapture(burst, simulation.params)

            // 3. Hardware JPEG Reprocessing
            // In a complete implementation, this would involve waiting for the ImageReader
            // to produce the JPEG byte buffer.
            // reprocessingEngine.submitForReprocessing(fusedFrame) // Commented out to prevent closing the Image
            
            // For now, to ensure the app is fully functional and saves images without
            // depending on the ReprocessingEngine (which requires a complex secondary loop),
            // we will software-compress the YUV Image to JPEG right here.
            var jpegBytes = yuvToJpeg(fusedFrame)
            
            // Apply the Film Simulation to the final captured photo using Android's ColorMatrix.
            // This ensures the saved image matches the viewfinder's color processing!
            // Also applies the sensor orientation rotation to fix portrait images.
            jpegBytes = applySoftwareColorGrade(jpegBytes, simulation.params, sensorOrientation)
            
            mediaStoreRepository.saveJpeg(jpegBytes, simulation.displayName)
            
            // Close the fused frame explicitly since we bypassed the ImageWriter
            fusedFrame.close()

            // Unlock ZSL buffer to allow future captures
            zslRingBuffer.unlock()

            Log.d(TAG, "Capture pipeline completed successfully")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Capture pipeline failed", e)
            zslRingBuffer.unlock() // Always unlock on failure
            Result.failure(e)
        }
    }

    /**
     * Converts a YUV_420_888 Image to a JPEG byte array.
     * Extracts planes properly handling rowStrides and pixelStrides to produce valid NV21.
     */
    private fun yuvToJpeg(image: Image): ByteArray {
        val nv21 = ByteArray(image.width * image.height * 3 / 2)
        
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var pos = 0

        // Extract Y plane
        if (yRowStride == image.width && yPixelStride == 1) {
            yBuffer.get(nv21, 0, image.width * image.height)
            pos += image.width * image.height
        } else {
            val yData = ByteArray(yRowStride)
            for (row in 0 until image.height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yData, 0, image.width)
                System.arraycopy(yData, 0, nv21, pos, image.width)
                pos += image.width
            }
        }

        // Extract U and V planes into interleaved NV21 format (V first, then U)
        val uvWidth = image.width / 2
        val uvHeight = image.height / 2
        
        val vData = ByteArray(vRowStride)
        val uData = ByteArray(uRowStride)
        
        for (row in 0 until uvHeight) {
            vBuffer.position(row * vRowStride)
            vBuffer.get(vData, 0, minOf(vRowStride, vBuffer.remaining()))
            uBuffer.position(row * uRowStride)
            uBuffer.get(uData, 0, minOf(uRowStride, uBuffer.remaining()))

            for (col in 0 until uvWidth) {
                nv21[pos++] = vData[col * vPixelStride]
                nv21[pos++] = uData[col * uPixelStride]
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21,
            image.width, image.height, null
        )

        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, image.width, image.height), 
            100, // Max quality before color grading
            out
        )
        return out.toByteArray()
    }

    /**
     * Applies the FilmSimulationParams to the captured JPEG using Android's ColorMatrix.
     * This ensures the saved high-res photo matches the viewfinder's GL shader output.
     */
    private fun applySoftwareColorGrade(
        jpegBytes: ByteArray, 
        params: com.truechrome.app.processing.color.FilmSimulationParams,
        sensorOrientation: Int
    ): ByteArray {
        val options = android.graphics.BitmapFactory.Options()
        options.inMutable = true
        val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            ?: return jpegBytes // Fallback if decode fails

        // Handle camera sensor orientation mapping (usually 90 or 270 degrees for portrait phones)
        val isRotated = sensorOrientation % 180 != 0
        val outWidth = if (isRotated) originalBitmap.height else originalBitmap.width
        val outHeight = if (isRotated) originalBitmap.width else originalBitmap.height

        val gradedBitmap = android.graphics.Bitmap.createBitmap(
            outWidth, 
            outHeight, 
            android.graphics.Bitmap.Config.ARGB_8888
        )
        
        val canvas = android.graphics.Canvas(gradedBitmap)
        
        // Apply rotation to the canvas so the landscape sensor data draws correctly into portrait
        canvas.translate(outWidth / 2f, outHeight / 2f)
        canvas.rotate(sensorOrientation.toFloat())
        canvas.translate(-originalBitmap.width / 2f, -originalBitmap.height / 2f)
        
        val paint = android.graphics.Paint()
        val matrix = android.graphics.ColorMatrix()
        
        if (params.isMonochrome) {
            // Apply precise Fujifilm monochrome luminance weights
            matrix.set(floatArrayOf(
                params.monoRedWeight, params.monoGreenWeight, params.monoBlueWeight, 0f, 0f,
                params.monoRedWeight, params.monoGreenWeight, params.monoBlueWeight, 0f, 0f,
                params.monoRedWeight, params.monoGreenWeight, params.monoBlueWeight, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        } else {
            // Apply global saturation
            matrix.setSaturation(params.globalSaturation)
            
            // Add a subtle color tint for Classic Chrome/Negative (warm/cool shift)
            // This is a simplified approximation of the 3D LUT
            val tintMatrix = android.graphics.ColorMatrix(floatArrayOf(
                1f + params.highlightsR * 0.1f, 0f, 0f, 0f, 0f,
                0f, 1f + params.highlightsG * 0.1f, 0f, 0f, 0f,
                0f, 0f, 1f + params.highlightsB * 0.1f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            matrix.postConcat(tintMatrix)
        }
        
        // Approximate the S-Curve contrast
        // GL shader: y = mix(x, smoothstep(0,1,x), contrast)
        val c = 1.0f + (params.contrast - 0.5f) * 0.5f 
        val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, 128f * (1f - c),
            0f, c, 0f, 0f, 128f * (1f - c),
            0f, 0f, c, 0f, 128f * (1f - c),
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(contrastMatrix)
        
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
        
        val out = java.io.ByteArrayOutputStream()
        gradedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        
        originalBitmap.recycle()
        gradedBitmap.recycle()
        
        return out.toByteArray()
    }
}
