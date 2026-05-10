package com.truechrome.app.processing.color

/**
 * FilmSimulationParams — The complete, immutable parameter set for a single film simulation.
 *
 * DETERMINISM GUARANTEE:
 * Every field in this data class is a `val` — immutable at construction time.
 * The same FilmSimulation enum variant ALWAYS produces the exact same instance of
 * this class with the exact same values. There is NO runtime adaptation, NO ML-based
 * scene detection, and NO histogram analysis that modifies these values.
 *
 * This is the single source of truth for how a film simulation looks.
 * The viewfinder shader reads these values as uniforms, and the capture pipeline
 * applies the exact same values to the final JPEG. What you see = what you get.
 *
 * ARCHITECTURE:
 * These parameters map directly to shader uniforms in the 5-pass GPU pipeline:
 * - Pass 2 (Tone Curves): shadows*, highlights*, contrast, blackPoint
 * - Pass 3 (3D LUT): lutAssetPath, lutStrength, saturationMultipliers, globalSaturation
 * - Pass 4 (Color Chrome): colorChromeStrength, colorChromeThreshold
 * - Pass 5 (Film Grain): grainIntensity, grainSize, grainLumaResponse
 */
data class FilmSimulationParams(
    // ── Tone Curve (per-channel) ──
    // Each channel has independent shadow and highlight control,
    // mimicking how film emulsions have different spectral responses per dye layer.
    val shadowsR: Float,        // Red shadow shift (-1.0 crush to 1.0 lift)
    val shadowsG: Float,        // Green shadow shift
    val shadowsB: Float,        // Blue shadow shift
    val highlightsR: Float,     // Red highlight rolloff (0.0 = hard clip, 1.0 = soft film shoulder)
    val highlightsG: Float,     // Green highlight rolloff
    val highlightsB: Float,     // Blue highlight rolloff
    val contrast: Float,        // S-curve intensity (0.0 = flat/linear, 1.0 = maximum)
    val blackPoint: Float,      // Lifted black floor (0.0 = true black, 0.15 = matte/lifted)

    // ── 3D LUT ──
    val lutAssetPath: String,   // Path to 33³ .cube file in assets/
    val lutStrength: Float,     // Blend factor with identity (0.0 = bypass, 1.0 = full LUT)

    // ── Hue-Dependent Saturation ──
    // 12 multipliers for 12 hue sectors (each covering 30° of the hue wheel).
    // This replicates Fujifilm's core innovation: different film stocks saturate
    // different colors differently. e.g., Velvia boosts greens/blues but not skin tones.
    val saturationMultipliers: FloatArray,
    val globalSaturation: Float,  // Master saturation multiplier (1.0 = neutral)

    // ── Color Chrome Effect ──
    // Fujifilm's Color Chrome deepens already-saturated colors without clipping.
    // It detects regions where a single channel dominates and pushes it further.
    val colorChromeStrength: Float,   // 0.0 = off, 0.5 = weak, 1.0 = strong
    val colorChromeThreshold: Float,  // Minimum saturation to trigger (0.0-1.0)

    // ── Film Grain ──
    // Grain is NOT random noise — it's a deterministic hash function that produces
    // the same pattern for the same pixel coordinate + capture timestamp.
    val grainIntensity: Float,      // 0.0 = none, 0.15 = heavy analog grain
    val grainSize: Float,           // Texel scale (1.0 = fine like Acros, 3.0 = coarse like pushed 800)
    val grainLumaResponse: Float,   // How grain varies with brightness (0.0 = uniform, 1.0 = midtone-focused)

    // ── Monochrome ──
    val isMonochrome: Boolean,
    val monoRedWeight: Float,       // R channel weight for luminosity B&W conversion
    val monoGreenWeight: Float,     // G channel weight (human eye is most sensitive to green)
    val monoBlueWeight: Float,      // B channel weight

    // ── Bloom / Halation ──
    val bloomIntensity: Float,      // 0.0 = no bloom, 0.3 = subtle film halation
    val bloomThreshold: Float,      // Luminance threshold to trigger bloom (0.0-1.0)
    val bloomTintR: Float,          // Halation tint color (warm = film-like)
    val bloomTintG: Float,
    val bloomTintB: Float,

    // ── Adaptive Sharpening ──
    val sharpenStrength: Float,     // 0.0 = none (portraits), 1.0 = strong (landscapes)

    // ── Local Tone Mapping ──
    val localToneMapStrength: Float  // 0.0 = none (preserve natural contrast), 1.0 = aggressive HDR
) {
    /**
     * Custom equals/hashCode to handle FloatArray comparison.
     * FloatArray.contentEquals is required because == on arrays checks reference equality.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FilmSimulationParams) return false
        return shadowsR == other.shadowsR &&
                shadowsG == other.shadowsG &&
                shadowsB == other.shadowsB &&
                highlightsR == other.highlightsR &&
                highlightsG == other.highlightsG &&
                highlightsB == other.highlightsB &&
                contrast == other.contrast &&
                blackPoint == other.blackPoint &&
                lutAssetPath == other.lutAssetPath &&
                lutStrength == other.lutStrength &&
                saturationMultipliers.contentEquals(other.saturationMultipliers) &&
                globalSaturation == other.globalSaturation &&
                colorChromeStrength == other.colorChromeStrength &&
                colorChromeThreshold == other.colorChromeThreshold &&
                grainIntensity == other.grainIntensity &&
                grainSize == other.grainSize &&
                grainLumaResponse == other.grainLumaResponse &&
                isMonochrome == other.isMonochrome &&
                monoRedWeight == other.monoRedWeight &&
                monoGreenWeight == other.monoGreenWeight &&
                monoBlueWeight == other.monoBlueWeight &&
                bloomIntensity == other.bloomIntensity &&
                bloomThreshold == other.bloomThreshold &&
                bloomTintR == other.bloomTintR &&
                bloomTintG == other.bloomTintG &&
                bloomTintB == other.bloomTintB &&
                sharpenStrength == other.sharpenStrength &&
                localToneMapStrength == other.localToneMapStrength
    }

    override fun hashCode(): Int {
        var result = shadowsR.hashCode()
        result = 31 * result + saturationMultipliers.contentHashCode()
        result = 31 * result + lutAssetPath.hashCode()
        return result
    }
}
