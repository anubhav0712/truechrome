package com.truechrome.app.camera.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.truechrome.app.camera.domain.CameraException
import com.truechrome.app.camera.domain.CameraRepository
import com.truechrome.app.camera.domain.usecase.OpenCameraUseCase
import com.truechrome.app.camera.domain.usecase.StartPreviewUseCase
import com.truechrome.app.core.haptics.HapticsController
import com.truechrome.app.core.state.CameraState
import com.truechrome.app.core.state.CameraStateMachine
import com.truechrome.app.processing.color.FilmSimulation
import dagger.hilt.android.lifecycle.HiltViewModel
import android.view.Surface
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * CameraViewModel — Orchestrates camera state, lifecycle, and UI interactions.
 *
 * RESPONSIBILITIES:
 * 1. Manages CameraUiState (single source of truth for UI)
 * 2. Coordinates camera lifecycle (open on resume, close on pause)
 * 3. Translates user actions into state machine transitions
 * 4. Coordinates haptic feedback timing with capture events
 * 5. Manages film simulation selection (deterministic, no auto-selection)
 *
 * LIFECYCLE:
 * - Scoped to the Activity via @HiltViewModel
 * - Camera is opened when permission is granted AND the Activity is resumed
 * - Camera is closed when the Activity pauses (prevents resource leaks)
 * - viewModelScope ensures all coroutines are cancelled when VM is cleared
 *
 * DETERMINISM:
 * - Film simulation selection is purely user-driven (no auto-scene detection)
 * - AE/AWB are locked after convergence (handled by Camera2DataSource)
 * - State transitions follow a deterministic state machine
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val stateMachine: CameraStateMachine,
    private val hapticsController: HapticsController,
    private val openCameraUseCase: OpenCameraUseCase,
    private val startPreviewUseCase: StartPreviewUseCase,
    private val capturePhotoUseCase: com.truechrome.app.camera.domain.usecase.CapturePhotoUseCase,
    private val cameraRepository: CameraRepository,
    private val mediaStoreRepository: com.truechrome.app.camera.data.MediaStoreRepositoryImpl,
    val lutManager: com.truechrome.app.processing.gl.LutManager
) : ViewModel() {

    companion object {
        private const val TAG = "CameraViewModel"
    }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // Expose reusable TrackingData directly (no allocations)
    val trackingData: com.truechrome.app.camera.domain.model.TrackingData
        get() = cameraRepository.trackingData

    init {
        viewModelScope.launch {
            cameraRepository.trackingTickFlow.collect { tick ->
                _uiState.update { it.copy(trackingUpdateTick = tick) }
            }
        }
    }

    // Track the camera open job to prevent double-opening
    private var cameraJob: Job? = null

    // ══════════════════════════════════════════════════════
    // Lifecycle Management
    // ══════════════════════════════════════════════════════

    /**
     * Called when camera permission is granted.
     * Triggers camera opening if the Activity is in a resumed state.
     */
    fun onPermissionGranted() {
        _uiState.update { it.copy(hasPermission = true) }
        openCameraIfReady()
    }

    /**
     * Called when camera permission is denied.
     */
    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                hasPermission = false,
                errorMessage = "Camera permission is required to use TrueChrome"
            )
        }
    }

    /**
     * Called when the Activity resumes (becomes foreground).
     * Opens the camera if permission is already granted.
     *
     * WHY ON RESUME (not onCreate):
     * Camera resources are shared system resources. Another app might have
     * taken the camera while we were in the background. We must re-acquire
     * on resume and release on pause to be a good system citizen.
     */
    fun onResume() {
        openCameraIfReady()
        
        // If there's a thumbnail photo, check if the user deleted it in the gallery app
        _uiState.value.latestPhotoUri?.let { uri ->
            viewModelScope.launch {
                if (!mediaStoreRepository.doesUriExist(uri)) {
                    _uiState.update { it.copy(latestPhotoUri = null) }
                }
            }
        }
    }

    /**
     * Called when the Activity pauses (goes to background).
     * Closes the camera to release resources for other apps.
     */
    fun onPause() {
        closeCamera()
    }

    /**
     * Opens the camera if permission is granted and camera is not already open.
     */
    private fun openCameraIfReady() {
        if (!_uiState.value.hasPermission) return
        if (cameraJob?.isActive == true) return
        if (_uiState.value.isPreviewing) return

        cameraJob = viewModelScope.launch {
            try {
                // Transition: Idle → Opening
                if (!stateMachine.transitionTo(CameraState.Opening)) {
                    Log.w(TAG, "Cannot open camera: invalid state transition")
                    return@launch
                }

                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                // Open camera and get capabilities
                val config = openCameraUseCase()

                // Transition: Opening → Previewing
                stateMachine.transitionTo(CameraState.Previewing(config.cameraId))

                _uiState.update {
                    it.copy(
                        isPreviewing = true,
                        isLoading = false,
                        cameraConfig = config
                    )
                }

                Log.d(TAG, "Camera ready: ${config.cameraId}, " +
                        "level3=${config.isLevel3}, " +
                        "preview=${config.previewSize}")

            } catch (e: CameraException) {
                Log.e(TAG, "Failed to open camera", e)
                stateMachine.transitionTo(CameraState.Error(e))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPreviewing = false,
                        errorMessage = e.message
                    )
                }
                // Auto-recover: Error → Idle
                stateMachine.transitionTo(CameraState.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error opening camera", e)
                stateMachine.transitionTo(
                    CameraState.Error(e, "Unexpected camera error")
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPreviewing = false,
                        errorMessage = "Unexpected error: ${e.message}"
                    )
                }
                stateMachine.transitionTo(CameraState.Idle)
            }
        }
    }

    /**
     * Called when the GLTextureView has created its surface and is ready to
     * receive camera frames. Starts the preview stream.
     */
    fun onCameraSurfaceReady(surface: Surface) {
        viewModelScope.launch {
            try {
                startPreviewUseCase(surface)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start preview", e)
                _uiState.update {
                    it.copy(
                        errorMessage = "Failed to start camera preview: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Closes the camera and releases all resources.
     */
    private fun closeCamera() {
        cameraJob?.cancel()
        cameraJob = null

        viewModelScope.launch {
            try {
                stateMachine.transitionTo(CameraState.Closing)
                cameraRepository.closeCamera()
                stateMachine.transitionTo(CameraState.Closed)
                stateMachine.transitionTo(CameraState.Idle)
            } catch (e: Exception) {
                Log.w(TAG, "Error during camera close", e)
                stateMachine.forceReset()
            }

            _uiState.update {
                it.copy(
                    isPreviewing = false,
                    isCapturing = false,
                    cameraConfig = null,
                    isExposureLocked = false
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // User Actions
    // ══════════════════════════════════════════════════════

    /**
     * Selects a film simulation.
     *
     * DETERMINISM: This simply updates the enum variant stored in state.
     * The GPU pipeline will read the new simulation's immutable params
     * on the next frame. No gradual transition, no interpolation —
     * instant, deterministic switch.
     */
    fun selectSimulation(simulation: FilmSimulation) {
        _uiState.update {
            it.copy(
                selectedSimulation = simulation,
                isSimSelectorVisible = false
            )
        }
    }

    /**
     * Toggles visibility of the film simulation selector.
     */
    fun toggleSimSelector() {
        _uiState.update { it.copy(isSimSelectorVisible = !it.isSimSelectorVisible) }
    }

    /**
     * Handles shutter button press.
     *
     * The haptic fires HERE (in the ViewModel, not the UI) to ensure it
     * correlates with the actual capture event, not UI touch latency.
     */
    fun onShutterPressed() {
        if (_uiState.value.isCapturing) return
        if (!_uiState.value.isPreviewing) return

        hapticsController.triggerShutterClick()

        viewModelScope.launch {
            try {
                stateMachine.transitionTo(CameraState.Capturing)
                _uiState.update { it.copy(isCapturing = true) }

                // The target timestamp MUST match the camera sensor's time base
                // which is typically SystemClock.elapsedRealtimeNanos()
                val targetTimestamp = android.os.SystemClock.elapsedRealtimeNanos()

                // Trigger a short haptic feedback for the shutter click
                hapticsController.triggerShutterClick()

                val result = capturePhotoUseCase(
                    targetTimestamp = targetTimestamp,
                    simulation = _uiState.value.selectedSimulation,
                    sensorOrientation = _uiState.value.cameraConfig?.sensorOrientation ?: 90
                )

                if (result.isFailure) {
                    throw result.exceptionOrNull() ?: Exception("Unknown capture error")
                } else {
                    // Update UI with the latest captured photo URI
                    _uiState.update { it.copy(latestPhotoUri = result.getOrNull()) }
                }

                stateMachine.transitionTo(
                    CameraState.Previewing(_uiState.value.cameraConfig?.cameraId ?: "0")
                )
                _uiState.update { it.copy(isCapturing = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        errorMessage = "Capture failed: ${e.message}"
                    )
                }
                // Recover to previewing state
                val cameraId = _uiState.value.cameraConfig?.cameraId ?: "0"
                stateMachine.transitionTo(CameraState.Previewing(cameraId))
            }
        }
    }

    /**
     * Handles tap-to-focus gesture.
     * Temporarily unlocks AE/AWB, refocuses, then re-locks.
     */
    fun onTapToFocus(x: Float, y: Float) {
        viewModelScope.launch {
            hapticsController.triggerFocusConfirm()
            cameraRepository.triggerAutofocus(x, y)
        }
    }

    /**
     * Dismisses the current error message.
     */
    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Clean up when ViewModel is destroyed.
     * This is a safety net — onPause should have already closed the camera.
     */
    override fun onCleared() {
        super.onCleared()
        cameraJob?.cancel()
        // Force-close camera as safety net
        // (viewModelScope is already cancelled, so we use the data source directly)
    }
}
