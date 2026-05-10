package com.truechrome.app.camera.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.media.ExifInterface
import com.truechrome.app.camera.domain.MediaStoreRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStoreRepositoryImpl — Implementation for saving JPEGs to the public gallery.
 */
@Singleton
class MediaStoreRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaStoreRepository {

    override suspend fun saveJpeg(jpegBytes: ByteArray, simulationName: String): Uri {
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            
            val filename = "TC_${System.currentTimeMillis()}.jpg"
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TrueChrome")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")

            try {
                // Write the JPEG bytes
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(jpegBytes)
                } ?: throw IOException("Failed to open output stream")

                // Write custom EXIF (Film Simulation tag)
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    // We write the simulation name into the UserComment EXIF tag
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "TrueChrome: $simulationName")
                    exif.saveAttributes()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw IOException("Failed to save image", e)
            }
        }
    }
}
