package com.truechrome.app.core.state

/**
 * CameraState — Deterministic state machine states for camera lifecycle.
 *
 * WHY A SEALED INTERFACE:
 * Camera2 API has complex lifecycle transitions that can cause crash loops if not
 * managed carefully. A sealed interface enforces exhaustive `when` handling and
 * prevents illegal state transitions at compile time.
 *
 * STATE TRANSITIONS (deterministic):
 * ```
 * Idle → Opening → Previewing → Capturing → Previewing
 *                                         → Error → Idle
 *                             → Closing → Closed → Idle
 *         → Error → Idle
 * ```
 *
 * INVARIANTS:
 * - Only ONE state is active at any time (enforced by StateFlow)
 * - Transitions are validated by CameraStateMachine — illegal transitions are rejected
 * - Error state always transitions back to Idle (graceful recovery, never crash)
 */
sealed interface CameraState {

    /** Initial state. Camera is not opened, no resources held. */
    data object Idle : CameraState

    /** Camera device is being opened. Waiting for onOpened/onError callback. */
    data object Opening : CameraState

    /**
     * Camera is open and actively previewing.
     *
     * @param cameraId The active camera device ID (e.g., "0" for rear camera).
     *                 Stored to detect camera disconnection events.
     */
    data class Previewing(val cameraId: String) : CameraState

    /** Capture in progress. Ring buffer frame extraction + reprocessing active. */
    data object Capturing : CameraState

    /** Camera is being closed. Waiting for resources to release. */
    data object Closing : CameraState

    /** Camera is fully closed. All resources released. */
    data object Closed : CameraState

    /**
     * Error state. Contains the cause for diagnostics.
     *
     * WHY NOT JUST THROW:
     * Camera errors (device disconnected, session failed, etc.) happen asynchronously
     * on the camera handler thread. Throwing would crash a background thread silently.
     * Instead, we transition to Error state, log the cause, and allow the UI to
     * display a recovery option. The state machine then transitions back to Idle.
     *
     * @param cause The exception or error that caused the failure
     * @param message Human-readable description for UI display
     */
    data class Error(
        val cause: Throwable,
        val message: String = cause.localizedMessage ?: "Unknown camera error"
    ) : CameraState
}
