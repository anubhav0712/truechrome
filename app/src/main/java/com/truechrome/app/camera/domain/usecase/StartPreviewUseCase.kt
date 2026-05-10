package com.truechrome.app.camera.domain.usecase

import android.view.Surface
import com.truechrome.app.camera.domain.CameraRepository
import javax.inject.Inject

/**
 * StartPreviewUseCase — Binds the UI Surface to the camera and starts streaming.
 */
class StartPreviewUseCase @Inject constructor(
    private val cameraRepository: CameraRepository
) {
    suspend operator fun invoke(surface: Surface) {
        cameraRepository.startPreview(surface)
    }
}
