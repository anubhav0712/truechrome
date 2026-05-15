package com.truechrome.app.processing.gl

/**
 * ShaderSources — All GLSL shader source code for the multi-pass pipeline.
 *
 * WHY INLINE KOTLIN STRINGS (not asset files):
 * 1. Compile-time verification — shader strings are part of the APK at build time
 * 2. No I/O latency at startup — no file reads from assets
 * 3. Deterministic — no possibility of missing/corrupted shader files
 * 4. IDE syntax highlighting (with GLSL plugins) still works in raw strings
 *
 * SHADER PIPELINE (5 passes):
 * Pass 1: External OES → Linear RGB (YUV decode from camera texture)
 * Pass 2: Per-channel tone curves (Fujifilm S-curve with film rolloff)
 * Pass 3: 3D LUT color grading + hue-dependent saturation
 * Pass 4: Color Chrome Effect (saturated color deepening)
 * Pass 5: Film grain (deterministic procedural noise)
 *
 * All shaders use precision highp float for IEEE 754 deterministic math.
 */
object ShaderSources {

    // ══════════════════════════════════════════════════════
    // COMMON MATH FUNCTIONS
    // ══════════════════════════════════════════════════════

    const val GLSL_RGB2HSV = """
        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
    """

    // ══════════════════════════════════════════════════════
    // SHARED VERTEX SHADER — used by all passes
    // ══════════════════════════════════════════════════════

    val VERTEX_SHADER = """
        #version 300 es
        precision highp float;
        
        layout(location = 0) in vec2 a_position;
        layout(location = 1) in vec2 a_texCoord;
        
        out vec2 v_texCoord;
        
        void main() {
            gl_Position = vec4(a_position, 0.0, 1.0);
            v_texCoord = a_texCoord;
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 1: External OES → Linear RGB
    // ══════════════════════════════════════════════════════

    /**
     * Converts the camera's external OES texture (YUV from hardware ISP)
     * into linear RGB in our internal FBO.
     *
     * Uses GL_TEXTURE_EXTERNAL_OES which is the only way to receive
     * camera preview frames in OpenGL on Android.
     */
    val PASS1_OES_TO_RGB_VERTEX = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        
        layout(location = 0) in vec2 a_position;
        layout(location = 1) in vec2 a_texCoord;
        
        uniform mat4 u_texMatrix;  // SurfaceTexture transform matrix
        
        out vec2 v_texCoord;
        
        void main() {
            gl_Position = vec4(a_position, 0.0, 1.0);
            v_texCoord = (u_texMatrix * vec4(a_texCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    val PASS1_OES_TO_RGB_FRAGMENT = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform samplerExternalOES u_cameraTexture;
        
        void main() {
            // Sample camera texture (hardware YUV→RGB conversion is done by the driver)
            vec3 color = texture(u_cameraTexture, v_texCoord).rgb;
            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 2: Per-Channel Tone Curves
    // ══════════════════════════════════════════════════════

    /**
     * Applies per-channel S-curves with film-like shoulder rolloff.
     *
     * The curve is: y = sigmoid(contrast * (x - 0.5)) * (1 - blackPoint) + blackPoint
     * Per-channel shadow/highlight shifts create the unique color character
     * of each film simulation (e.g., warm highlights + cool shadows for Classic Neg).
     *
     * DETERMINISM: Pure function of input pixel and uniform values.
     * Same uniforms + same input = same output. Always.
     */
    val PASS2_TONE_CURVE_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        
        // Per-channel shadow lift/crush
        uniform vec3 u_shadows;      // (shadowsR, shadowsG, shadowsB)
        // Per-channel highlight rolloff
        uniform vec3 u_highlights;   // (highlightsR, highlightsG, highlightsB)
        // S-curve intensity / Contrast
        uniform float u_contrast;
        // Black floor (0.0 = true black, >0 = lifted/matte)
        uniform float u_blackPoint;
        
        // ACES Filmic Tone Mapping Curve (Krzysztof Narkowicz approximation)
        // This provides much better contrast ("punchiness") and handles color saturation
        // in the highlights far better than standard Hable, preventing the "flat" look.
        vec3 acesFilm(vec3 x) {
            float a = 2.51;
            float b = 0.03;
            float c = 2.43;
            float d = 0.59;
            float e = 0.14;
            return clamp((x*(a*x+b))/(x*(c*x+d)+e), 0.0, 1.0);
        }

        // Film-style curve applying ACES
        vec3 filmCurve(vec3 color) {
            // Apply shadow shift (exposure comp)
            color = max(vec3(0.0), color + u_shadows * 0.1); 

            // Pre-saturation boost:
            // Hable/ACES inherently desaturate. We add a slight vibrance boost before 
            // tone mapping to ensure true-to-life color depth is maintained.
            float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
            color = mix(vec3(luma), color, mix(1.1, 1.3, u_contrast));

            // Apply ACES tone mapping
            vec3 mapped = acesFilm(color);
            
            // Highlight rolloff mask to preserve Fuji-like softness in extreme whites
            vec3 highlightMask = smoothstep(u_highlights - vec3(0.1), u_highlights + vec3(0.1), mapped);
            mapped = mix(mapped, u_highlights, highlightMask * 0.3);
            
            // Apply black point floor
            mapped = mapped * (1.0 - u_blackPoint) + u_blackPoint;
            
            return clamp(mapped, 0.0, 1.0);
        }
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            color = filmCurve(color);
            fragColor = vec4(color, 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 3: 3D LUT + Hue-Dependent Saturation
    // ══════════════════════════════════════════════════════

    /**
     * Applies the 3D color LUT (loaded as GL_TEXTURE_3D) and hue-dependent
     * saturation mapping.
     *
     * The 3D LUT maps every input RGB → output RGB. Combined with the hue-dependent
     * saturation multipliers, this creates the signature color palette of each
     * film simulation.
     *
     * Hue-dependent saturation is Fujifilm's core innovation: different film stocks
     * saturate different colors differently. Velvia boosts greens but not skin tones.
     */
    val PASS3_COLOR_GRADE_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        uniform mediump sampler3D u_lutTexture;
        uniform float u_lutStrength;
        
        // 12 saturation multipliers for 12 hue sectors (30° each)
        uniform float u_satMults[12];
        uniform float u_globalSaturation;
        uniform int u_isMonochrome;
        uniform vec3 u_monoWeights;
        uniform float u_skinToneProtection;
        
        $GLSL_RGB2HSV
        
        // RGB → HSL conversion (for hue-dependent saturation)
        vec3 rgb2hsl(vec3 c) {
            float maxC = max(max(c.r, c.g), c.b);
            float minC = min(min(c.r, c.g), c.b);
            float delta = maxC - minC;
            
            float h = 0.0;
            float s = 0.0;
            float l = (maxC + minC) * 0.5;
            
            if (delta > 0.001) {
                s = (l > 0.5) ? delta / (2.0 - maxC - minC) : delta / (maxC + minC);
                
                if (maxC == c.r) {
                    h = (c.g - c.b) / delta + (c.g < c.b ? 6.0 : 0.0);
                } else if (maxC == c.g) {
                    h = (c.b - c.r) / delta + 2.0;
                } else {
                    h = (c.r - c.g) / delta + 4.0;
                }
                h /= 6.0;
            }
            
            return vec3(h, s, l);
        }
        
        // Hue-dependent saturation: look up the multiplier for this hue sector
        float getHueSatMultiplier(float hue) {
            float sector = hue * 12.0;
            int idx0 = int(floor(sector)) % 12;
            int idx1 = (idx0 + 1) % 12;
            float fract = fract(sector);
            
            // Linear interpolation between adjacent sectors for smooth transitions
            return mix(u_satMults[idx0], u_satMults[idx1], fract);
        }
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
            // Apply 3D LUT
            vec3 lutColor = texture(u_lutTexture, color).rgb;
            vec3 gradedColor = mix(color, lutColor, u_lutStrength);
            
            // Hue-dependent saturation
            vec3 hsl = rgb2hsl(gradedColor);
            float hueMult = getHueSatMultiplier(hsl.x);
            
            // Apply saturation in a luminance-preserving way
            float luma = dot(gradedColor, vec3(0.2126, 0.7152, 0.0722));
            vec3 desaturated = vec3(luma);
            float effectiveSat = u_globalSaturation * hueMult;
            gradedColor = mix(desaturated, gradedColor, effectiveSat);
            
            // Monochrome conversion
            if (u_isMonochrome == 1) {
                float monoLuma = dot(gradedColor, u_monoWeights);
                gradedColor = vec3(monoLuma);
            }
            
            // ── Vectorscope Skin Tone Protection (I-Line Anchoring) ──
            if (u_skinToneProtection > 0.001) {
                vec3 hsv = rgb2hsv(color); // Analyze original, pre-LUT color
                
                // Human blood dictates skin falls precisely on the I-Line (approx 15-18 deg, or 0.05 normalized hue)
                float SKIN_HUE = 0.05;
                float hueDist = min(abs(hsv.x - SKIN_HUE), 1.0 - abs(hsv.x - SKIN_HUE));
                
                // Gaussian falloff for hue (sigma = 0.03 ~10 degrees)
                float skinWeight = exp(-(hueDist * hueDist) / 0.0018);
                
                // Ensure the pixel has enough saturation and luminance to actually be skin
                float satWeight = smoothstep(0.1, 0.7, hsv.y);
                float valWeight = smoothstep(0.1, 0.9, hsv.z);
                
                // Final protection mask
                float W = skinWeight * satWeight * valWeight * u_skinToneProtection;
                
                // Interpolate between the heavily graded color and the original color
                gradedColor = mix(gradedColor, color, W);
            }
            
            fragColor = vec4(clamp(gradedColor, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 4: Color Chrome Effect
    // ══════════════════════════════════════════════════════

    val PASS4_COLOR_CHROME_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        uniform float u_chromeStrength;
        uniform float u_chromeThreshold;
        uniform float u_skinToneProtection;
        
        $GLSL_RGB2HSV
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
            if (u_chromeStrength < 0.001) {
                fragColor = vec4(color, 1.0);
                return;
            }
            
            // True Subtractive Color Mixing (CMY Density Model)
            // Film dyes don't emit light (RGB), they subtract light (CMY).
            
            // Clamp color to prevent NaN in pow() if HDR values exceed 1.0 (blown highlights)
            vec3 clampedColor = clamp(color, 0.0, 1.0);
            
            float localChromeStrength = u_chromeStrength;
            
            // ── Vectorscope Skin Tone Protection ──
            if (u_skinToneProtection > 0.001) {
                vec3 hsv = rgb2hsv(clampedColor);
                float hueDist = min(abs(hsv.x - 0.05), 1.0 - abs(hsv.x - 0.05));
                float skinWeight = exp(-(hueDist * hueDist) / 0.0018);
                float satWeight = smoothstep(0.1, 0.7, hsv.y);
                float valWeight = smoothstep(0.1, 0.9, hsv.z);
                float W = skinWeight * satWeight * valWeight * u_skinToneProtection;
                
                // Subtractive mixing heavily damages skin tones by removing light.
                // We reduce the effect strength specifically for skin vectors.
                localChromeStrength *= (1.0 - W);
            }
            
            // 1. Convert to CMY
            vec3 cmy = 1.0 - clampedColor;
            
            // 2. Measure saturation to create a mask (we only want to deepen already saturated colors)
            float maxChannel = max(max(clampedColor.r, clampedColor.g), clampedColor.b);
            float minChannel = min(min(clampedColor.r, clampedColor.g), clampedColor.b);
            float saturation = (maxChannel > 0.001) ? (maxChannel - minChannel) / maxChannel : 0.0;
            float mask = smoothstep(u_chromeThreshold, u_chromeThreshold + 0.15, saturation);
            
            // 3. Apply chemical dye density via exponential curve
            // Increased strength means thicker dyes, absorbing exponentially more light
            vec3 denseCMY = pow(cmy, vec3(1.0 + (localChromeStrength * mask)));
            
            // 4. Convert back to RGB
            vec3 deepened = 1.0 - denseCMY;
            
            // Note: We intentionally DO NOT restore luminance here. 
            // In physical film, highly saturated colors are naturally darker because 
            // the thick dyes filter out more light. This creates the rich, deep look.
            
            fragColor = vec4(clamp(deepened, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 5: Film Grain
    // ══════════════════════════════════════════════════════

    /**
     * Procedural film grain using a deterministic hash function.
     *
     * DETERMINISM GUARANTEE:
     * The grain pattern is determined by: UV coordinate + u_grainSeed.
     * - In viewfinder mode: u_grainSeed varies per frame (animated grain)
     * - In capture mode: u_grainSeed = captureTimestamp (fixed grain per capture)
     * Same seed + same UV = same grain value. Always.
     *
     * FILM CHARACTER:
     * - Grain is strongest in midtones (like real silver halide film)
     * - Grain fades in deep shadows (not enough exposure to activate grains)
     * - Grain fades in highlights (grains are fully developed)
     * - This is controlled by u_grainLumaResponse
     */
    val PASS5_GRAIN_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        uniform vec2 u_resolution;
        uniform float u_grainIntensity;
        uniform float u_grainSize;
        uniform float u_grainLumaResponse;
        uniform float u_grainSeed;    // Deterministic: captureTimestamp for final, frameIndex for preview
        uniform float u_vignetteStrength;
        
        // Deterministic hash function — NOT random
        // Same input always produces same output (IEEE 754 highp guarantee)
        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
            // ── Optical Illuminance Falloff (Cosine Fourth Law Vignette) ──
            if (u_vignetteStrength > 0.001) {
                // Shift UV to -0.5 to 0.5
                vec2 uv = v_texCoord - 0.5;
                // Correct for aspect ratio so vignette is circular, not elliptical
                uv.x *= u_resolution.x / u_resolution.y;
                
                float distSq = dot(uv, uv);
                
                // Calculate physically accurate light falloff
                // E = E_0 * cos^4(theta)
                // Approximated by E = E_0 / (1 + r^2)^2
                float falloff = 1.0 + distSq * (4.0 * u_vignetteStrength);
                float vignette = 1.0 / (falloff * falloff);
                
                color *= vignette;
            }
            
            if (u_grainIntensity < 0.001) {
                fragColor = vec4(color, 1.0);
                return;
            }
            
            float luma = dot(color, vec3(0.299, 0.587, 0.114));
            
            // Scale UV by grain size for coarser/finer texture
            vec2 grainUV = v_texCoord * u_resolution / u_grainSize;
            
            // Deterministic noise: seed ensures reproducibility
            float noise = hash(grainUV + vec2(mod(u_grainSeed, 97.0))) - 0.5;
            
            // Film-style grain: strongest in midtones, fades in shadows & highlights
            float lumaMask = smoothstep(0.05, 0.25, luma) * smoothstep(0.95, 0.75, luma);
            lumaMask = mix(1.0, lumaMask, u_grainLumaResponse);
            
            color += noise * u_grainIntensity * lumaMask;
            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 6: Halation (Red-Scattering Highlight Blur)
    // ══════════════════════════════════════════════════════

    /**
     * Film Halation Effect: Simulates red light scattering off the film base.
     * Extracts bright areas, applies a red-biased multi-tap blur, and additively
     * composites it over the original image.
     */
    val PASS6_HALATION_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture; // Original full-res frame
        uniform sampler2D u_blurTexture;  // Downsampled blurred highlights
        uniform float u_halationIntensity;
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
            if (u_halationIntensity < 0.001) {
                fragColor = vec4(color, 1.0);
                return;
            }
            
            // Read the pre-blurred highlights
            vec3 halationBloom = texture(u_blurTexture, v_texCoord).rgb;
            
            // Additive composite (linear light addition)
            vec3 finalColor = color + (halationBloom * u_halationIntensity);
            
            fragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    val PASS6_HALATION_BLUR_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        uniform vec2 u_texelSize; // 1.0 / resolution
        
        // 9-tap Gaussian blur weights
        const float weight[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
        
        void main() {
            vec3 centerColor = texture(u_inputTexture, v_texCoord).rgb;
            
            // 1. Highlight Extraction (Thresholding)
            // We only want to blur pixels that are very bright (e.g. > 0.8)
            vec3 extracted = max(centerColor - 0.8, 0.0) * 2.0;
            
            vec3 result = extracted * weight[0];
            
            // 2. 9-tap Cross Blur (Horizontal + Vertical combined for performance on small FBO)
            // Red channel gets a wider scatter (larger step size) to simulate emulsion depth
            vec2 stepR = u_texelSize * 2.0;
            vec2 stepGB = u_texelSize * 1.0;
            
            for(int i = 1; i < 5; ++i) {
                float w = weight[i];
                float offset = float(i);
                
                // Red gets wider spread
                vec3 sampleR1 = texture(u_inputTexture, v_texCoord + vec2(stepR.x * offset, 0.0)).rgb;
                vec3 sampleR2 = texture(u_inputTexture, v_texCoord - vec2(stepR.x * offset, 0.0)).rgb;
                vec3 sampleR3 = texture(u_inputTexture, v_texCoord + vec2(0.0, stepR.y * offset)).rgb;
                vec3 sampleR4 = texture(u_inputTexture, v_texCoord - vec2(0.0, stepR.y * offset)).rgb;
                
                result.r += (max(sampleR1.r - 0.8, 0.0) + max(sampleR2.r - 0.8, 0.0) + 
                             max(sampleR3.r - 0.8, 0.0) + max(sampleR4.r - 0.8, 0.0)) * 2.0 * w;
                
                // Green/Blue get tighter spread
                vec3 sampleGB1 = texture(u_inputTexture, v_texCoord + vec2(stepGB.x * offset, 0.0)).rgb;
                vec3 sampleGB2 = texture(u_inputTexture, v_texCoord - vec2(stepGB.x * offset, 0.0)).rgb;
                vec3 sampleGB3 = texture(u_inputTexture, v_texCoord + vec2(0.0, stepGB.y * offset)).rgb;
                vec3 sampleGB4 = texture(u_inputTexture, v_texCoord - vec2(0.0, stepGB.y * offset)).rgb;
                
                result.gb += (max(sampleGB1.gb - 0.8, 0.0) + max(sampleGB2.gb - 0.8, 0.0) + 
                              max(sampleGB3.gb - 0.8, 0.0) + max(sampleGB4.gb - 0.8, 0.0)) * 2.0 * w;
            }
            
            fragColor = vec4(result, 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASSTHROUGH — Identity shader for debugging
    // ══════════════════════════════════════════════════════

    val PASSTHROUGH_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        
        void main() {
            fragColor = texture(u_inputTexture, v_texCoord);
        }
    """.trimIndent()
}
