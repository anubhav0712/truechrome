package com.truechrome.app.camera.data

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.truechrome.app.camera.domain.model.TrackingData
import java.util.concurrent.Executors

/**
 * Sony-Style Real-Time Predictive Subject Tracking Core.
 *
 * This engine runs completely off the main thread, ingesting 480p YUV frames.
 * It combines Google ML Kit Face Detection (hitting the MediaTek APU via NNAPI)
 * with a zero-allocation Linear Kalman Filter to maintain "sticky" focus
 * even when the subject is temporarily obscured.
 */
class TrackingEngine {
    private val TAG = "TrackingEngine"
    
    // Executor for ML processing to avoid blocking Camera2 threads
    private val executor = Executors.newSingleThreadExecutor()
    
    // ML Kit Face Detector (fast options, no contours, just bounding boxes)
    // Uses NNAPI/GPU delegation under the hood
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    // Reusable tracking data to prevent GC allocations
    val trackingData = TrackingData()

    // Kalman Filter State: [x, y, vx, vy]
    // Primitive array to avoid allocations
    private val kalmanState = FloatArray(4) { 0f }
    private var isKalmanInitialized = false
    private var framesWithoutDetection = 0
    private val MAX_PREDICTIVE_FRAMES = 60
    
    // Concurrency flag to drop frames and prevent ImageReader starvation
    @Volatile
    private var isProcessing = false

    /**
     * Process an image from the 480p ImageReader.
     * This method must run extremely fast (< 16ms).
     */
    fun processImage(image: Image, sensorOrientation: Int, onTrackingUpdated: (TrackingData) -> Unit) {
        // Drop frames if the ML Kit pipeline is currently busy
        if (isProcessing || image.format != ImageFormat.YUV_420_888) {
            image.close()
            return
        }

        isProcessing = true
        
        // Convert YUV Image to ML Kit InputImage
        val inputImage = InputImage.fromMediaImage(image, sensorOrientation)
        
        val rotatedWidth = if (sensorOrientation == 90 || sensorOrientation == 270) image.height else image.width
        val rotatedHeight = if (sensorOrientation == 90 || sensorOrientation == 270) image.width else image.height
        
        try {
            faceDetector.process(inputImage)
                .addOnCompleteListener(executor) { task ->
                    if (task.isSuccessful) {
                    val faces = task.result
                    if (faces != null && faces.isNotEmpty()) {
                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        if (face != null) {
                            updateKalmanFilter(face.boundingBox, true, rotatedWidth, rotatedHeight)
                            trackingData.isActive = true
                            trackingData.confidence = 1.0f
                        }
                    } else {
                        if (trackingData.isActive) {
                            updateKalmanFilter(null, false, rotatedWidth, rotatedHeight)
                        }
                    }
                } else {
                    Log.e(TAG, "Face detection failed", task.exception)
                    if (trackingData.isActive) {
                        updateKalmanFilter(null, false, rotatedWidth, rotatedHeight)
                    }
                }
                
                if (trackingData.isActive) {
                    onTrackingUpdated(trackingData)
                }
                
                image.close() // CRITICAL: Release buffer back to ImageReader
                isProcessing = false
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            image.close()
            isProcessing = false
        }
    }

    /**
     * Alpha-Beta Filter (simplified Linear Kalman Filter) for bounding box center.
     * Zero object allocations.
     */
    private fun updateKalmanFilter(box: Rect?, hasDetection: Boolean, imageWidth: Int, imageHeight: Int) {
        if (hasDetection && box != null) {
            val cx = box.exactCenterX()
            val cy = box.exactCenterY()
            
            if (!isKalmanInitialized) {
                kalmanState[0] = cx
                kalmanState[1] = cy
                kalmanState[2] = 0f
                kalmanState[3] = 0f
                isKalmanInitialized = true
            } else {
                // Alpha-Beta Update Math
                val alpha = 0.6f
                val beta = 0.3f
                
                val predX = kalmanState[0] + kalmanState[2]
                val predY = kalmanState[1] + kalmanState[3]
                
                val resX = cx - predX
                val resY = cy - predY
                
                kalmanState[0] = predX + alpha * resX
                kalmanState[1] = predY + alpha * resY
                kalmanState[2] = kalmanState[2] + beta * resX
                kalmanState[3] = kalmanState[3] + beta * resY
            }
            framesWithoutDetection = 0
        } else {
            // Predict position based on velocity vector
            if (isKalmanInitialized) {
                kalmanState[0] += kalmanState[2]
                kalmanState[1] += kalmanState[3]
                framesWithoutDetection++
                
                // Decay confidence linearly over 60 frames
                trackingData.confidence = 1.0f - (framesWithoutDetection.toFloat() / MAX_PREDICTIVE_FRAMES)
                
                if (framesWithoutDetection > MAX_PREDICTIVE_FRAMES) {
                    trackingData.isActive = false
                    isKalmanInitialized = false
                }
            }
        }
        
        // Update reusable TrackingData with normalized coordinates
        if (trackingData.isActive) {
            val normX = (kalmanState[0] / imageWidth).coerceIn(0f, 1f)
            val normY = (kalmanState[1] / imageHeight).coerceIn(0f, 1f)
            trackingData.normalizedCenter.set(normX, normY)
        }
    }

    fun release() {
        faceDetector.close()
        executor.shutdown()
    }
}
