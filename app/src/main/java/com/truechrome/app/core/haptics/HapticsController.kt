package com.truechrome.app.core.haptics

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HapticsController — Provides deterministic haptic feedback for camera interactions.
 *
 * WHY DEDICATED CONTROLLER:
 * Haptic feedback in a camera app must feel "mechanical" — like a real shutter click.
 * Android's VibrationEffect.createPredefined() provides precisely tuned haptic primitives
 * that feel premium on devices with linear vibration motors (like the Nothing Phone 2a Plus).
 *
 * DETERMINISM:
 * Every call to triggerShutterClick() produces the exact same haptic pattern.
 * No randomness, no adaptive intensity — the user builds muscle memory.
 */
@Singleton
class HapticsController @Inject constructor(
    private val vibrator: Vibrator
) {
    /**
     * Triggers a sharp, satisfying shutter click haptic.
     *
     * Uses EFFECT_CLICK which is a single, crisp impulse designed to simulate
     * a mechanical button press. This is the most "shutter-like" predefined effect.
     *
     * On devices without haptic support, this is a no-op (graceful degradation).
     */
    fun triggerShutterClick() {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: Use predefined effect for the most precise, consistent haptic
            vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            )
        } else {
            // API 28 fallback: Short one-shot vibration
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    /**
     * Triggers a lighter haptic for focus confirmation.
     *
     * Uses EFFECT_TICK — a subtle, short impulse that indicates "something happened"
     * without the full weight of a shutter click. Used when autofocus locks.
     */
    fun triggerFocusConfirm() {
        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }
}
