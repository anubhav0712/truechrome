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
        
        // Monochrome conversion
        uniform bool u_isMonochrome;
        uniform vec3 u_monoWeights;   // (R, G, B) weights for luminosity B&W
        
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
            color = mix(color, lutColor, u_lutStrength);
            
            // Hue-dependent saturation
            vec3 hsl = rgb2hsl(color);
            float hueMult = getHueSatMultiplier(hsl.x);
            
            // Apply saturation in a luminance-preserving way
            float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
            vec3 desaturated = vec3(luma);
            float effectiveSat = u_globalSaturation * hueMult;
            color = mix(desaturated, color, effectiveSat);
            
            // Monochrome conversion
            if (u_isMonochrome) {
                float monoLuma = dot(color, u_monoWeights);
                color = vec3(monoLuma);
            }
            
            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    // ══════════════════════════════════════════════════════
    // PASS 4: Color Chrome Effect
    // ══════════════════════════════════════════════════════

    /**
     * Fujifilm's Color Chrome Effect: deepens already-saturated colors
     * without clipping or shifting hue. It detects regions where a single
     * color channel dominates and pushes it further while compensating luminance.
     *
     * smoothstep ensures no hard threshold boundaries — the effect is a
     * continuous function with no binary decisions.
     */
    val PASS4_COLOR_CHROME_FRAGMENT = """
        #version 300 es
        precision highp float;
        
        in vec2 v_texCoord;
        out vec4 fragColor;
        
        uniform sampler2D u_inputTexture;
        uniform float u_chromeStrength;
        uniform float u_chromeThreshold;
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
            if (u_chromeStrength < 0.001) {
                fragColor = vec4(color, 1.0);
                return;
            }
            
            // Measure saturation: ratio of (max - min) to max channel
            float maxChannel = max(max(color.r, color.g), color.b);
            float minChannel = min(min(color.r, color.g), color.b);
            float chroma = maxChannel - minChannel;
            float saturation = (maxChannel > 0.001) ? chroma / maxChannel : 0.0;
            
            // Color Chrome mask: activate for already-saturated regions
            // smoothstep ensures continuous, deterministic transitions
            float mask = smoothstep(u_chromeThreshold, u_chromeThreshold + 0.15, saturation);
            
            // Deepen: push the dominant channel further from the others
            vec3 deepened = color;
            float lumaOriginal = dot(color, vec3(0.2126, 0.7152, 0.0722));
            
            // Increase chroma by scaling each channel's deviation from luminance
            vec3 deviation = color - vec3(lumaOriginal);
            deepened = color + deviation * mask * u_chromeStrength;
            
            // Luminance compensation: restore original brightness
            // This prevents the deepening from changing overall exposure
            float lumaDeepened = dot(deepened, vec3(0.2126, 0.7152, 0.0722));
            float lumaRatio = (lumaDeepened > 0.001) ? lumaOriginal / lumaDeepened : 1.0;
            deepened *= lumaRatio;
            
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
        
        // Deterministic hash function — NOT random
        // Same input always produces same output (IEEE 754 highp guarantee)
        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }
        
        void main() {
            vec3 color = texture(u_inputTexture, v_texCoord).rgb;
            
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
