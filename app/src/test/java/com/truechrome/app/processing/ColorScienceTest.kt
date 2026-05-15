package com.truechrome.app.processing

import com.truechrome.app.camera.domain.model.SensorCalibrationData
import com.truechrome.app.processing.gl.ShaderSources
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ColorScienceTest — Protects the TrueChrome color science engine from regression.
 *
 * This test suite ensures that other feature development does not inadvertently
 * alter the highly tuned D65-to-ACES matrices or remove critical filmic shader logic.
 */
class ColorScienceTest {

    private val DELTA = 0.0001f

    @Test
    fun testGN9CalibrationMatrixIntegrity() {
        // The Samsung GN9 CCM is tuned specifically for the Dimensity 7350 Pro.
        // It features a warmer red response and reduced blue bias.
        // If this matrix is altered, the global color temperature will shift.
        val expectedGN9Matrix = floatArrayOf(
            0.85f, 0.10f, 0.05f,
            0.05f, 0.85f, 0.10f,
            0.02f, 0.05f, 0.93f
        )
        assertArrayEquals(
            "GN9 Calibration Matrix has been altered! This will break color science.",
            expectedGN9Matrix,
            SensorCalibrationData.CCM_GN9_D65_TO_ACES,
            DELTA
        )
    }

    @Test
    fun testJN1CalibrationMatrixIntegrity() {
        // The Samsung JN1 CCM is tuned for warmer skin-tones on the front camera.
        val expectedJN1Matrix = floatArrayOf(
            0.82f, 0.12f, 0.06f,
            0.06f, 0.82f, 0.12f,
            0.04f, 0.06f, 0.90f
        )
        assertArrayEquals(
            "JN1 Calibration Matrix has been altered! This will break selfie skin-tones.",
            expectedJN1Matrix,
            SensorCalibrationData.CCM_JN1_D65_TO_ACES,
            DELTA
        )
    }

    @Test
    fun testAcesFilmicToneMappingExists() {
        // Ensures the ACES Filmic Curve (Krzysztof Narkowicz approximation) is present.
        // Removing this will cause the image to look flat and lack punchiness.
        val toneCurveShader = ShaderSources.PASS2_TONE_CURVE_FRAGMENT
        assertTrue(
            "ACES Filmic curve function is missing from ShaderSources!",
            toneCurveShader.contains("vec3 acesFilm(vec3 x)")
        )
        assertTrue(
            "Pre-saturation vibrance boost is missing! Highlights will desaturate.",
            toneCurveShader.contains("color = mix(vec3(luma), color, mix(1.1, 1.3, u_contrast));")
        )
    }

    @Test
    fun testColorChromeEffectExists() {
        // Ensures the Luma-preserving Color Chrome effect remains intact.
        val chromeShader = ShaderSources.PASS4_COLOR_CHROME_FRAGMENT
        assertTrue(
            "Color Chrome saturation masking is missing!",
            chromeShader.contains("float mask = smoothstep(u_chromeThreshold, u_chromeThreshold + 0.15, saturation);")
        )
        assertTrue(
            "Color Chrome luminance preservation is missing! Colors will shift exposure.",
            chromeShader.contains("float lumaRatio = (lumaDeepened > 0.001) ? lumaOriginal / lumaDeepened : 1.0;")
        )
    }

    @Test
    fun testProceduralGrainIsDeterministic() {
        // Ensures the film grain uses the deterministic hash and luminance masking.
        val grainShader = ShaderSources.PASS5_GRAIN_FRAGMENT
        assertTrue(
            "Grain is not using the deterministic hash function!",
            grainShader.contains("float hash(vec2 p)")
        )
        assertTrue(
            "Grain is not luminance-weighted! It will ruin pure blacks and whites.",
            grainShader.contains("float lumaMask = smoothstep(0.05, 0.25, luma) * smoothstep(0.95, 0.75, luma);")
        )
    }
}
