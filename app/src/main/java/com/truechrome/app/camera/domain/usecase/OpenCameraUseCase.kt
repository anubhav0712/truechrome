package com.truechrome.app.camera.domain.usecase

import com.truechrome.app.camera.domain.CameraRepository
import com.truechrome.app.camera.domain.model.CameraConfig
import javax.inject.Inject

/**
 * OpenCameraUseCase — Single-responsibility use case for opening the camera.
 *
 * WHY USE CASES:
 * Use cases enforce the Single Responsibility Principle. Each use case does ONE thing.
 * This makes the ViewModel thin (it just orchestrates use cases) and each use case
 * is independently testable.
 *
 * This use case:
 * 1. Opens the rear camera device
 * 2. Queries hardware capabilities
 * 3. Returns a CameraConfig with the device's supported features
 *
 * The ViewModel decides WHEN to call this (on permission grant + Activity resume).
 * The use case decides HOW (delegate to repository).
 */
class OpenCameraUseCase @Inject constructor(
    private val cameraRepository: CameraRepository
) {
    /**
     * Opens the camera and returns its capabilities.
     *
     * @return CameraConfig describing the device's hardware features
     * @throws CameraException if the camera cannot be opened
     */
    suspend operator fun invoke(): CameraConfig {
        return cameraRepository.openCamera()
    }
}
