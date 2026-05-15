package com.truechrome.app.camera.domain.model

/**
 * SensorCalibrationData — Stores standard matrices and sensor data.
 * Includes baseline D65-to-ACES Color Correction Matrices (CCM) for the Samsung GN9 and JN1.
 *
 * NOTE: These are empirical baseline matrices meant for further fine-tuning via a physical MacBeth ColorChecker.
 */
object SensorCalibrationData {
    // D65-to-ACES baseline approximation for Samsung GN9 (Main 50MP)
    // Adjusted: Warmer red response, reduced blue bias to fix cool shifting
    val CCM_GN9_D65_TO_ACES = floatArrayOf(
        0.85f, 0.10f, 0.05f,
        0.05f, 0.85f, 0.10f,
        0.02f, 0.05f, 0.93f
    )

    // D65-to-ACES baseline approximation for Samsung JN1 (Front 32MP)
    // Adjusted: Warmer skin-tone bias (reds/yellows)
    val CCM_JN1_D65_TO_ACES = floatArrayOf(
        0.82f, 0.12f, 0.06f,
        0.06f, 0.82f, 0.12f,
        0.04f, 0.06f, 0.90f
    )

    // TrueChrome v2.0: Bradford Chromatic Adaptation (D65 to D50)
    // Mathematically shifts the native D65 Daylight white point to a warm D50 Print Film white point.
    // This provides a physically accurate cinematic warmth that simple matrix tweaks cannot achieve.
    val BRADFORD_D65_TO_D50 = floatArrayOf(
        1.0478112f, 0.0228866f, -0.0501270f,
        0.0295424f, 0.9904844f, -0.0170491f,
        -0.0092345f, 0.0150436f, 0.7521316f
    )
}
