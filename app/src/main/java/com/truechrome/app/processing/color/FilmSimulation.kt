package com.truechrome.app.processing.color

import androidx.compose.ui.graphics.Color
import com.truechrome.app.ui.theme.*

/**
 * FilmSimulation — The 6 Fujifilm-inspired film simulations available in TrueChrome.
 *
 * Each enum variant contains:
 * - displayName: Short name shown in the viewfinder overlay
 * - subtitle: The analog film stock this simulation is inspired by
 * - accentColor: UI color for the film simulation indicator
 * - params: The complete, immutable parameter set driving the GPU pipeline
 *
 * DETERMINISM:
 * All params are hardcoded compile-time constants. The same enum variant
 * ALWAYS produces the exact same visual output. No adaptation, no randomness.
 */
enum class FilmSimulation(
    val displayName: String,
    val subtitle: String,
    val accentColor: Color,
    val params: FilmSimulationParams
) {
    /**
     * Classic Negative — Inspired by Fujifilm Superia 400
     *
     * Character: Nostalgic, snapshot-like. High contrast with warm highlights
     * and cool shadows. Desaturated midtones give a "memory" quality.
     * Grain is visible but not overpowering.
     */
    CLASSIC_NEGATIVE(
        displayName = "Classic Neg",
        subtitle = "Superia 400",
        accentColor = FilmClassicNeg,
        params = FilmSimulationParams(
            // Warm highlights (R holds longer), cool shadows (B/G lift)
            shadowsR = -0.05f, shadowsG = 0.03f, shadowsB = 0.08f,
            highlightsR = 0.85f, highlightsG = 0.7f, highlightsB = 0.6f,
            contrast = 0.72f,
            blackPoint = 0.04f,  // Slightly lifted — film never reaches absolute zero
            lutAssetPath = "luts/classic_negative_33.cube",
            lutStrength = 0.85f,
            // Desaturate greens/cyans, boost warm tones
            saturationMultipliers = floatArrayOf(
                1.1f, 1.05f, 0.85f, 0.7f, 0.75f, 0.8f,
                0.9f, 1.0f, 1.1f, 1.15f, 1.1f, 1.05f
            ),
            globalSaturation = 0.88f,
            colorChromeStrength = 0.3f,
            colorChromeThreshold = 0.6f,
            grainIntensity = 0.06f,
            grainSize = 1.5f,
            grainLumaResponse = 0.7f,
            isMonochrome = false,
            monoRedWeight = 0f, monoGreenWeight = 0f, monoBlueWeight = 0f,
            bloomIntensity = 0.12f,
            bloomThreshold = 0.82f,
            bloomTintR = 1.0f, bloomTintG = 0.85f, bloomTintB = 0.7f,  // Warm halation
            sharpenStrength = 0.4f,
            localToneMapStrength = 0.3f
        )
    ),

    /**
     * Classic Chrome — Inspired by Kodachrome documentary look
     *
     * Character: Muted, desaturated, journalistic. Hard shadow crush with
     * soft highlight rolloff. Colors feel "weathered" and timeless.
     */
    CLASSIC_CHROME(
        displayName = "Classic Chrome",
        subtitle = "Documentary",
        accentColor = FilmClassicChrome,
        params = FilmSimulationParams(
            shadowsR = -0.08f, shadowsG = -0.06f, shadowsB = -0.03f,
            highlightsR = 0.75f, highlightsG = 0.78f, highlightsB = 0.82f,
            contrast = 0.68f,
            blackPoint = 0.02f,  // Near-true black — hard shadow character
            lutAssetPath = "luts/classic_chrome_33.cube",
            lutStrength = 0.90f,
            // Heavily desaturate reds/oranges, slightly boost blues→cyan
            saturationMultipliers = floatArrayOf(
                0.75f, 0.7f, 0.8f, 0.85f, 0.9f, 1.05f,
                1.1f, 1.0f, 0.85f, 0.8f, 0.75f, 0.7f
            ),
            globalSaturation = 0.78f,
            colorChromeStrength = 0.15f,
            colorChromeThreshold = 0.65f,
            grainIntensity = 0.03f,
            grainSize = 1.2f,
            grainLumaResponse = 0.5f,
            isMonochrome = false,
            monoRedWeight = 0f, monoGreenWeight = 0f, monoBlueWeight = 0f,
            bloomIntensity = 0.06f,
            bloomThreshold = 0.88f,
            bloomTintR = 0.95f, bloomTintG = 0.93f, bloomTintB = 0.9f,  // Neutral/cool
            sharpenStrength = 0.5f,
            localToneMapStrength = 0.25f
        )
    ),

    /**
     * Velvia Vivid — Inspired by Fujifilm Velvia 50
     *
     * Character: Hyper-saturated landscapes. Deep crushed blacks, vivid greens/blues/reds.
     * Maximum color "punch" — makes nature scenes explode with color.
     */
    VELVIA(
        displayName = "Velvia Vivid",
        subtitle = "Landscape",
        accentColor = FilmVelvia,
        params = FilmSimulationParams(
            shadowsR = -0.1f, shadowsG = -0.08f, shadowsB = -0.06f,
            highlightsR = 0.9f, highlightsG = 0.88f, highlightsB = 0.85f,
            contrast = 0.82f,  // Deep S-curve for maximum punch
            blackPoint = 0.01f,  // Nearly crushed blacks
            lutAssetPath = "luts/velvia_33.cube",
            lutStrength = 0.95f,
            // Boost everything except skin-tone adjacent hues
            saturationMultipliers = floatArrayOf(
                1.15f, 1.1f, 1.3f, 1.35f, 1.25f, 1.2f,
                1.15f, 1.1f, 1.2f, 1.25f, 1.3f, 1.2f
            ),
            globalSaturation = 1.25f,
            colorChromeStrength = 0.6f,  // Strong — deepen saturated regions
            colorChromeThreshold = 0.5f,
            grainIntensity = 0f,  // Velvia 50 is extremely fine-grained — effectively invisible
            grainSize = 1.0f,
            grainLumaResponse = 0f,
            isMonochrome = false,
            monoRedWeight = 0f, monoGreenWeight = 0f, monoBlueWeight = 0f,
            bloomIntensity = 0.08f,
            bloomThreshold = 0.85f,
            bloomTintR = 1.0f, bloomTintG = 0.95f, bloomTintB = 0.88f,
            sharpenStrength = 0.7f,  // Strong — landscape detail
            localToneMapStrength = 0.45f  // More aggressive HDR compression for landscapes
        )
    ),

    /**
     * Astia Soft — Inspired by Fujifilm Astia 100F
     *
     * Character: Gentle, flattering portrait rendering. Faithful skin tones,
     * slightly warm overall. Low contrast with open shadows.
     */
    ASTIA(
        displayName = "Astia Soft",
        subtitle = "Portrait",
        accentColor = FilmAstia,
        params = FilmSimulationParams(
            shadowsR = 0.02f, shadowsG = 0.01f, shadowsB = 0f,
            highlightsR = 0.92f, highlightsG = 0.90f, highlightsB = 0.88f,
            contrast = 0.45f,  // Gentle S-curve — soft, open
            blackPoint = 0.06f,  // Lifted blacks for a soft, airy feel
            lutAssetPath = "luts/astia_33.cube",
            lutStrength = 0.80f,
            // Reduce green-yellow saturation, keep skin tones faithful
            saturationMultipliers = floatArrayOf(
                1.05f, 0.95f, 0.85f, 0.9f, 0.95f, 1.0f,
                1.05f, 1.08f, 1.05f, 1.0f, 0.95f, 1.0f
            ),
            globalSaturation = 0.95f,
            colorChromeStrength = 0.1f,  // Minimal — don't distort skin
            colorChromeThreshold = 0.7f,
            grainIntensity = 0f,  // Clean rendering for portraits
            grainSize = 1.0f,
            grainLumaResponse = 0f,
            isMonochrome = false,
            monoRedWeight = 0f, monoGreenWeight = 0f, monoBlueWeight = 0f,
            bloomIntensity = 0.15f,  // Subtle glow for dreamy portraits
            bloomThreshold = 0.80f,
            bloomTintR = 1.0f, bloomTintG = 0.92f, bloomTintB = 0.88f,  // Warm soft glow
            sharpenStrength = 0.2f,  // Light — preserve skin softness
            localToneMapStrength = 0.15f  // Minimal — preserve natural contrast
        )
    ),

    /**
     * Pro Neg Standard — Inspired by Fujifilm Pro 160NS
     *
     * Character: Ultra-flat, maximum latitude studio rendering.
     * Very muted colors, smooth skin, wide tonal range for post-processing headroom.
     */
    PRO_NEG_STD(
        displayName = "Pro Neg Std",
        subtitle = "Studio",
        accentColor = FilmProNeg,
        params = FilmSimulationParams(
            shadowsR = 0.03f, shadowsG = 0.03f, shadowsB = 0.03f,
            highlightsR = 0.95f, highlightsG = 0.95f, highlightsB = 0.95f,
            contrast = 0.3f,  // Flat — maximum tonal range
            blackPoint = 0.08f,  // Lifted — never true black
            lutAssetPath = "luts/pro_neg_std_33.cube",
            lutStrength = 0.75f,
            // Everything desaturated equally — neutral palette
            saturationMultipliers = floatArrayOf(
                0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f,
                0.85f, 0.85f, 0.85f, 0.85f, 0.85f, 0.85f
            ),
            globalSaturation = 0.82f,
            colorChromeStrength = 0f,  // Off — don't distort flat rendering
            colorChromeThreshold = 1.0f,
            grainIntensity = 0f,
            grainSize = 1.0f,
            grainLumaResponse = 0f,
            isMonochrome = false,
            monoRedWeight = 0f, monoGreenWeight = 0f, monoBlueWeight = 0f,
            bloomIntensity = 0.04f,
            bloomThreshold = 0.9f,
            bloomTintR = 1.0f, bloomTintG = 0.98f, bloomTintB = 0.96f,
            sharpenStrength = 0f,  // None — maximum softness for studio
            localToneMapStrength = 0.1f  // Minimal — preserve flat latitude
        )
    ),

    /**
     * Acros Monochrome — Inspired by Fujifilm Neopan Acros 100
     *
     * Character: Fine art black & white. Extended highlight range, rich blacks,
     * fine organic grain. Luminosity-weighted B&W conversion with Fujifilm's
     * signature emphasis on green channel (for natural tonal separation in foliage/skin).
     */
    ACROS(
        displayName = "Acros",
        subtitle = "Fine B&W",
        accentColor = FilmAcros,
        params = FilmSimulationParams(
            shadowsR = -0.02f, shadowsG = -0.02f, shadowsB = -0.02f,
            highlightsR = 0.88f, highlightsG = 0.88f, highlightsB = 0.88f,
            contrast = 0.65f,
            blackPoint = 0.02f,  // Rich, deep blacks
            lutAssetPath = "luts/acros_33.cube",
            lutStrength = 0.90f,
            // Saturation irrelevant for B&W, but set to neutral
            saturationMultipliers = floatArrayOf(
                1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f
            ),
            globalSaturation = 0f,  // Zero — full desaturation
            colorChromeStrength = 0f,
            colorChromeThreshold = 1.0f,
            // Fine, organic grain — Acros 100's signature
            grainIntensity = 0.04f,
            grainSize = 1.0f,  // Very fine grain structure
            grainLumaResponse = 0.85f,  // Strongly midtone-focused
            isMonochrome = true,
            // Fujifilm-style luminosity weights (emphasizes green for natural tonal sep)
            monoRedWeight = 0.2f,
            monoGreenWeight = 0.7f,
            monoBlueWeight = 0.1f,
            bloomIntensity = 0.1f,
            bloomThreshold = 0.83f,
            bloomTintR = 1.0f, bloomTintG = 1.0f, bloomTintB = 1.0f,  // Pure white bloom
            sharpenStrength = 0.45f,  // Medium — reveal fine grain structure
            localToneMapStrength = 0.2f
        )
    );

    companion object {
        val DEFAULT = CLASSIC_NEGATIVE
    }
}
