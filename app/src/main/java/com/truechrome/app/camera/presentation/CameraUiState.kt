package com.truechrome.app.camera.presentation

import com.truechrome.app.camera.domain.model.CameraConfig
import com.truechrome.app.processing.color.FilmSimulation

/**
 * CameraUiState — The single source of truth for the camera screen UI.
 *
 * WHY A SINGLE STATE CLASS:
 * Unidirectional Data Flow (UDF) requires one immutable state object that the UI
 * observes. This eliminates impossible states (e.g., "capturing" while "not previewing")
 * and makes the UI a pure function of state: UI = f(CameraUiState).
 *
 * DETERMINISM:
 * Given the same CameraUiState, the UI always renders identically.
 * No side effects, no conditional rendering based on external state.
 */
data class CameraUiState(
    /** Whether the camera is actively previewing (viewfinder is live) */
    val isPreviewing: Boolean = false,

    /** Whether a capture is in progress (shutter button disabled) */
    val isCapturing: Boolean = false,

    /** Currently selected film simulation */
    val selectedSimulation: FilmSimulation = FilmSimulation.DEFAULT,

    /** Whether the film simulation picker is visible */
    val isSimSelectorVisible: Boolean = false,

    /** Whether camera permission has been granted */
    val hasPermission: Boolean = false,

    /** Error message to display, null if no error */
    val errorMessage: String? = null,

    /** Whether the camera is in the process of opening */
    val isLoading: Boolean = false,

    /** Camera hardware capabilities (available after camera opens) */
    val cameraConfig: CameraConfig? = null,

    /** Whether AE/AWB have converged and locked (deterministic mode ready) */
    val isExposureLocked: Boolean = false,

    /** URI of the most recently captured photo */
    val latestPhotoUri: android.net.Uri? = null
)
