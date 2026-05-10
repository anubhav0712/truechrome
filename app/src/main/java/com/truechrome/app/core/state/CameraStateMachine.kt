package com.truechrome.app.core.state

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraStateMachine — Enforces deterministic, valid camera state transitions.
 *
 * WHY A STATE MACHINE:
 * Camera2 API has notoriously complex lifecycle management. Common failure modes:
 * 1. Opening camera while it's already opening → crash
 * 2. Capturing while session is being torn down → crash
 * 3. Closing camera that was never opened → resource leak
 * 4. Race condition between onDisconnected and user-initiated close → crash
 *
 * This state machine prevents ALL of these by:
 * - Validating every transition against a whitelist of legal transitions
 * - Rejecting illegal transitions with a log warning (never crash)
 * - Using StateFlow for thread-safe, atomic state updates
 * - Providing a single source of truth that both UI and camera layers observe
 *
 * DETERMINISM:
 * The transition table is static and exhaustive. Given any (currentState, targetState) pair,
 * the result is always the same: either the transition succeeds or it's rejected.
 * No randomness, no timing-dependent behavior.
 */
@Singleton
class CameraStateMachine @Inject constructor() {

    companion object {
        private const val TAG = "CameraStateMachine"

        /**
         * Legal state transitions whitelist.
         *
         * This is the ONLY place where transitions are defined. If a transition
         * is not in this map, it is illegal and will be rejected.
         *
         * The map is: currentState::class → Set of allowed targetState::class
         */
        private val LEGAL_TRANSITIONS: Map<Class<out CameraState>, Set<Class<out CameraState>>> = mapOf(
            CameraState.Idle::class.java to setOf(
                CameraState.Opening::class.java
            ),
            CameraState.Opening::class.java to setOf(
                CameraState.Previewing::class.java,
                CameraState.Error::class.java,
                CameraState.Closed::class.java  // Camera disconnected during open
            ),
            CameraState.Previewing::class.java to setOf(
                CameraState.Capturing::class.java,
                CameraState.Closing::class.java,
                CameraState.Error::class.java
            ),
            CameraState.Capturing::class.java to setOf(
                CameraState.Previewing::class.java,  // Capture complete, return to preview
                CameraState.Error::class.java,
                CameraState.Closing::class.java
            ),
            CameraState.Closing::class.java to setOf(
                CameraState.Closed::class.java,
                CameraState.Error::class.java
            ),
            CameraState.Closed::class.java to setOf(
                CameraState.Idle::class.java  // Ready to reopen
            ),
            CameraState.Error::class.java to setOf(
                CameraState.Idle::class.java  // Recovery path: Error → Idle → Opening
            )
        )
    }

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)

    /** Observable camera state. UI and camera layers both observe this. */
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Current state snapshot for synchronous checks. */
    val currentState: CameraState get() = _state.value

    /**
     * Attempts a state transition. Returns true if the transition was legal and applied.
     *
     * WHY SYNCHRONIZED:
     * Although StateFlow itself is thread-safe, the check-then-set pattern
     * (read current → validate → write new) must be atomic to prevent TOCTOU races
     * between the camera handler thread and the UI thread.
     *
     * @param newState The target state to transition to
     * @return true if the transition was applied, false if it was rejected
     */
    @Synchronized
    fun transitionTo(newState: CameraState): Boolean {
        val current = _state.value
        val legalTargets = LEGAL_TRANSITIONS[current::class.java]

        if (legalTargets == null || newState::class.java !in legalTargets) {
            Log.w(
                TAG,
                "ILLEGAL transition rejected: ${current::class.simpleName} → ${newState::class.simpleName}"
            )
            return false
        }

        Log.d(TAG, "Transition: ${current::class.simpleName} → ${newState::class.simpleName}")
        _state.value = newState
        return true
    }

    /**
     * Force-resets to Idle state. Used only for catastrophic recovery.
     *
     * This bypasses the transition validation because in a catastrophic failure
     * (e.g., process death, unrecoverable error), we need to guarantee the state
     * machine returns to a known-good state regardless of the current state.
     */
    @Synchronized
    fun forceReset() {
        Log.w(TAG, "FORCE RESET from ${_state.value::class.simpleName} → Idle")
        _state.value = CameraState.Idle
    }

    /**
     * Checks if a specific transition would be legal without performing it.
     * Useful for UI to enable/disable buttons based on available actions.
     */
    fun canTransitionTo(targetState: CameraState): Boolean {
        val legalTargets = LEGAL_TRANSITIONS[_state.value::class.java]
        return legalTargets != null && targetState::class.java in legalTargets
    }
}
