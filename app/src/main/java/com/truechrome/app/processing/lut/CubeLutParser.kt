package com.truechrome.app.processing.lut

import javax.inject.Inject
import javax.inject.Singleton

/**
 * CubeLutParser — Parses .cube 3D LUT files into float arrays for GPU upload.
 *
 * The .cube format is an industry standard for 3D color look-up tables:
 * - Header declares LUT size (e.g., LUT_3D_SIZE 33 → 33×33×33 = 35,937 entries)
 * - Each line contains R G B float triplet (0.0 to 1.0)
 * - Data is stored in row-major order: B varies fastest, then G, then R
 *
 * DETERMINISM:
 * Parsing is a pure function: same .cube file → same FloatArray output.
 * No floating-point rounding ambiguity because we use Float.parseFloat
 * which follows IEEE 754 deterministic parsing rules.
 */
@Singleton
class CubeLutParser @Inject constructor() {

    data class LutData(
        val size: Int,          // Cube dimension (e.g., 33)
        val data: FloatArray    // Flat array: [R0,G0,B0, R1,G1,B1, ...] size³×3 elements
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LutData) return false
            return size == other.size && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * size + data.contentHashCode()
    }

    /**
     * Parses a .cube file content string into a LutData object.
     *
     * @param content The full text content of a .cube file
     * @return Parsed LUT data with size and flat float array
     * @throws IllegalArgumentException if the file format is invalid
     */
    fun parse(content: String): LutData {
        var size = 0
        val values = mutableListOf<Float>()

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) return@forEach

            // Parse LUT size declaration
            if (line.startsWith("LUT_3D_SIZE")) {
                size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                return@forEach
            }

            // Skip DOMAIN_MIN / DOMAIN_MAX (we assume 0.0-1.0 standard range)
            if (line.startsWith("DOMAIN_MIN") || line.startsWith("DOMAIN_MAX")) return@forEach

            // Parse RGB triplet
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 3) {
                try {
                    values.add(parts[0].toFloat())  // R
                    values.add(parts[1].toFloat())  // G
                    values.add(parts[2].toFloat())  // B
                } catch (e: NumberFormatException) {
                    // Skip malformed lines (some .cube files have metadata we don't recognize)
                }
            }
        }

        require(size > 0) { "LUT_3D_SIZE not found in .cube file" }

        val expectedValues = size * size * size * 3
        require(values.size == expectedValues) {
            "Expected $expectedValues values for ${size}³ LUT, got ${values.size}"
        }

        return LutData(
            size = size,
            data = values.toFloatArray()
        )
    }
}
