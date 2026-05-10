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
}
