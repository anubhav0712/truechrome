package com.truechrome.app.camera.data

import android.media.Image
import android.util.Log
import java.util.ArrayDeque

/**
 * ZslRingBuffer — Zero Shutter Lag (ZSL) circular frame buffer.
 *
 * This buffer holds the last N high-resolution YUV_420_888 frames directly from
 * the camera ISP. When the user presses the shutter, we extract frames from the
 * PAST to eliminate shutter lag completely.
 *
 * DETERMINISM:
 * - Frames are evicted strictly in FIFO order.
 * - Frame extraction always pulls the EXACT timestamp requested, plus N history frames.
 *
 * MEMORY SAFETY (CRITICAL):
 * Camera2 Image objects are backed by native Gralloc buffers. If they aren't closed,
 * the entire camera subsystem will stall and the app will crash within 10 frames.
 * Every single evicted or unused Image MUST be explicitly closed.
 */
class ZslRingBuffer(
    private val capacity: Int = 12  // 12 frames at 30fps = ~400ms of history
) {
    companion object {
        private const val TAG = "ZslRingBuffer"
    }

    // Stores pairs of (Timestamp, Image). ArrayDeque is efficient for FIFO.
    private val buffer = ArrayDeque<Pair<Long, Image>>(capacity)
    
    private val lock = Object()
    private var isLockedForExtraction = false

    /**
     * Adds a new frame to the ring buffer.
     * If the buffer is full, the oldest frame is evicted AND closed.
     *
     * @param image The Image to add (transfers ownership to the buffer)
     * @param timestamp The sensor timestamp of the image
     */
    fun onImageAvailable(image: Image, timestamp: Long) {
        synchronized(lock) {
            // If locked for capture, we immediately drop incoming frames
            // because the ImageReader might be starved of buffers.
            if (isLockedForExtraction) {
                image.close()
                return
            }

            // Evict oldest if full
            if (buffer.size >= capacity) {
                val oldest = buffer.removeFirst()
                oldest.second.close() // CRITICAL: Prevent native memory leak
            }

            buffer.addLast(Pair(timestamp, image))
        }
    }

    /**
     * Extracts a burst of frames ending at (or just before) the target timestamp.
     *
     * This locks the buffer, stopping new frames from being added. The extracted
     * frames are removed from the buffer, and ownership is transferred to the caller.
     * The caller MUST close these images when done.
     *
     * Any unused frames left in the buffer are automatically closed and cleared.
     *
     * @param targetTimestamp The timestamp of the shutter press
     * @param burstSize The number of frames to extract (e.g., 5)
     * @return List of Images. Will be empty if no frames are available.
     */
    fun extractBurst(targetTimestamp: Long, burstSize: Int): List<Image> {
        synchronized(lock) {
            isLockedForExtraction = true

            if (buffer.isEmpty()) {
                Log.w(TAG, "Extraction requested but buffer is empty")
                return emptyList()
            }

            Log.d(TAG, "Extracting burst of $burstSize frames near timestamp $targetTimestamp")

            // Find the index of the frame closest to, but not exceeding, the target timestamp
            // (Since frames arrive slightly after the sensor exposes them)
            var targetIndex = buffer.size - 1
            val list = buffer.toList()
            
            for (i in list.indices.reversed()) {
                if (list[i].first <= targetTimestamp) {
                    targetIndex = i
                    break
                }
            }

            // Determine the range of frames to extract (going backwards from target)
            val startIndex = maxOf(0, targetIndex - burstSize + 1)
            val extractedImages = mutableListOf<Image>()

            // Extract the requested frames
            for (i in startIndex..targetIndex) {
                extractedImages.add(list[i].second)
            }

            // Close all OTHER frames in the buffer to free Gralloc memory immediately
            for (i in list.indices) {
                if (i < startIndex || i > targetIndex) {
                    list[i].second.close()
                }
            }

            buffer.clear()
            return extractedImages
        }
    }

    /**
     * Unlocks the buffer so it can start accepting frames again.
     */
    fun unlock() {
        synchronized(lock) {
            isLockedForExtraction = false
        }
    }

    /**
     * Clears the buffer and closes all images.
     * Must be called when the camera session ends.
     */
    fun clear() {
        synchronized(lock) {
            for (pair in buffer) {
                pair.second.close()
            }
            buffer.clear()
            isLockedForExtraction = false
        }
    }
}
