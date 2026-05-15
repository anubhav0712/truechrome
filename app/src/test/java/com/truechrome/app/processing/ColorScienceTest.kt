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
    fun testBradfordMatrixIntegrity() {
        val expectedBradfordMatrix = floatArrayOf(
            1.0478112f, 0.0228866f, -0.0501270f,
            0.0295424f, 0.9904844f, -0.0170491f,
            -0.0092345f, 0.0150436f, 0.7521316f
        )
        assertArrayEquals(
            "Bradford D65-to-D50 Adaptation Matrix has been altered!",
            expectedBradfordMatrix,
            SensorCalibrationData.BRADFORD_D65_TO_D50,
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
        // Ensures the True Subtractive (CMY Density) Color Chrome effect remains intact.
        val chromeShader = ShaderSources.PASS4_COLOR_CHROME_FRAGMENT
        assertTrue(
            "Subtractive CMY inversion is missing! Colors will act additively instead of like film dyes.",
            chromeShader.contains("vec3 cmy = 1.0 - color;")
        )
        assertTrue(
            "Dye density exponentiation is missing! Film depth simulation will fail.",
            chromeShader.contains("vec3 denseCMY = pow(cmy, vec3(1.0 + (u_chromeStrength * mask)));")
        )
    }

    @Test
    fun testHalationEffectExists() {
        // Ensures the red-biased film halation shaders are present.
        val blurShader = ShaderSources.PASS6_HALATION_BLUR_FRAGMENT
        val compositeShader = ShaderSources.PASS6_HALATION_FRAGMENT
        assertTrue(
            "Halation highlight extraction is missing!",
            blurShader.contains("vec3 extracted = max(centerColor - 0.8, 0.0) * 2.0;")
        )
        assertTrue(
            "Halation red-biased scattering is missing!",
            blurShader.contains("vec2 stepR = u_texelSize * 2.0;") && blurShader.contains("vec2 stepGB = u_texelSize * 1.0;")
        )
        assertTrue(
            "Halation additive composite is missing!",
            compositeShader.contains("vec3 finalColor = color + (halationBloom * u_halationIntensity);")
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
