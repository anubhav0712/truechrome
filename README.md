# TrueChrome

TrueChrome is an advanced computational photography engine built for Android, specifically optimized for the **Nothing Phone (2a) Plus** (MediaTek Dimensity 7350 Pro, Samsung GN9 & JN1 sensors). 

The goal of TrueChrome is to bypass standard, over-processed smartphone aesthetics and recreate the organic, "true-to-life" color science characteristic of Fujifilm X-Trans sensors.

## Color Science Architecture

TrueChrome implements a hardware-aware, multi-pass GPU pipeline using OpenGL ES 3.0 (with 16-bit half-float precision via `GL_RGBA16F` to prevent banding). 

The color pipeline consists of five distinct phases:

### 1. Sensor-Specific Calibration (RAW to Linear)
Before any stylized grading occurs, the raw sensor data must be standardized.
- **Optical Black Clamping:** We utilize `SENSOR_BLACK_LEVEL_PATTERN` to strictly clamp the RGGB channels. This prevents the purple tinting often seen in shadows when pushing exposure.
- **D65-to-ACES Matrices:** The Samsung GN9 and JN1 have custom-tuned Color Correction Matrices (CCM) in `SensorCalibrationData.kt`. These matrices have been explicitly warmed (higher red response, lower blue bias) to counter the traditionally cool output of mobile ISP conversions, establishing a solid, true-to-life baseline.
- **ISP Bypass:** OEM Noise Reduction, Edge Enhancement, and Tone Mapping are forced to `OFF` or `FAST` via Camera2 API to preserve maximum pixel integrity.

### 2. ACES Filmic Tone Mapping
Standard digital highlight clipping looks harsh. We employ the **Krzysztof Narkowicz ACES approximation** within our GLSL shader (`PASS2_TONE_CURVE_FRAGMENT`) to roll off highlights beautifully, much like analog film.
- **Pre-Saturation Boost:** Because ACES and Hable curves inherently desaturate extreme brights, we inject a dynamic vibrance boost *prior* to the tone curve. This ensures that skies and bright objects retain their "punch" and true-to-life color depth rather than flattening out.

### 3. Hue-Dependent Saturation & 3D LUTs
The core of the "Fuji Look" is that different hues saturate differently (e.g., Velvia pushes greens but protects skin tones).
- **3D LUT Grading:** Film simulations are applied using trilinearly interpolated `.cube` LUTs.
- **Hue Multipliers:** A custom HSV conversion in `PASS3_COLOR_GRADE_FRAGMENT` isolates 12 hue sectors (30° each) and applies specific saturation multipliers without altering the underlying luminance.

### 4. Color Chrome Effect
Replicating Fujifilm's Color Chrome technology, `PASS4_COLOR_CHROME_FRAGMENT` targets highly saturated areas (like deep red flowers or yellow signs) and deepens them to add texture.
- **Luma-Preserving:** The shader identifies the dominant color channel, pushes it further to increase chromaticity, and then mathematically restores the original pixel luminance. This prevents the deepening effect from changing the global exposure.

### 5. Deterministic Procedural Grain
Analog film grain is fundamentally different from digital noise. Silver halide crystals respond to light intensity.
- **Luminance-Weighted:** Our procedural GLSL noise function (`PASS5_GRAIN_FRAGMENT`) uses a `smoothstep` luma mask. The grain peaks in the mid-tones where it is most pleasing, and smoothly fades to zero in pure blacks (shadows) and pure whites (blown highlights).
- **Deterministic:** The noise generation uses a mathematical hash seeded by the exact `captureTimestamp`. This guarantees that the grain pattern is identical every time a specific photo is re-rendered.

## Zero-Shutter Lag & Night Burst Engine
TrueChrome utilizes an `AHardwareBuffer` backed ZSL (Zero Shutter Lag) ring buffer in C++ / Kotlin to maintain a rolling window of the last 5 frames. 
- The **NightSceneDetector** monitors real-time Auto-Exposure (EV) metadata. If low light is detected, it triggers a specialized **Bracketed Night Burst**, leveraging the GN9's Dual Conversion Gain to capture multi-ISO frames (e.g., 100 ISO HCG, 800 ISO LCG) and fuse them, vastly reducing noise without motion blur.

## Testing
To ensure the highly-tuned color science is not degraded by future development, the `ColorScienceTest` suite strictly asserts the mathematical integrity of the calibration matrices and the presence of the advanced GLSL shader logic. Run tests via:
`./gradlew testDebugUnitTest`
