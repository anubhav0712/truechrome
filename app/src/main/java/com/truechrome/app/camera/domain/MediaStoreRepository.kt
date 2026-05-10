package com.truechrome.app.camera.domain

import android.net.Uri

/**
 * MediaStoreRepository — Manages saving images to the device's public gallery.
 *
 * All saved photos should have deterministic EXIF metadata applied 
 * (including the film simulation name in the MakerNote or UserComment).
 */
interface MediaStoreRepository {
    
    /**
     * Saves a JPEG byte array to the MediaStore.
     *
     * @param jpegBytes The compressed JPEG data
     * @param simulationName The name of the film simulation used (for EXIF)
     * @return The ContentResolver Uri of the saved image
     */
    suspend fun saveJpeg(jpegBytes: ByteArray, simulationName: String): Uri
}
