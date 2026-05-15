package com.truechrome.app.processing.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.truechrome.app.processing.color.FilmSimulationParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GLCameraRenderer — Multi-pass OpenGL ES 3.0 rendering engine.
 *
 * This is the core of TrueChrome's visual pipeline. It takes the camera preview
 * frames (as an external OES texture) and runs them through 5 shader passes
 * using ping-pong framebuffers to produce the final Fujifilm-inspired output.
 *
 * ARCHITECTURE: Ping-Pong FBO
 * ```
 * Camera OES → [Pass 1: YUV→RGB] → FBO_A
 *              [Pass 2: Tone]     → FBO_B (reads A)
 *              [Pass 3: LUT]      → FBO_A (reads B)
 *              [Pass 4: Chrome]   → FBO_B (reads A)
 *              [Pass 5: Grain]    → Screen (reads B)
 * ```
 * This avoids costly texture re-allocation. Only 2 FBOs are ever used.
 *
 * DETERMINISM:
 * - All uniforms come from immutable FilmSimulationParams
 * - Grain seed is deterministic (frame index for preview, timestamp for capture)
 * - GL_LINEAR filtering on all textures (hardware-deterministic trilinear)
 * - precision highp float in all shaders (IEEE 754 on Mali GPU)
 */
class GLCameraRenderer {

    companion object {
        private const val TAG = "GLCameraRenderer"
    }

    // ── Shader programs (one per pass) ──
    private var pass1OesToRgb: ShaderProgram? = null
    private var pass2ToneCurve: ShaderProgram? = null
    private var pass3ColorGrade: ShaderProgram? = null
    private var pass4ColorChrome: ShaderProgram? = null
    private var pass5Grain: ShaderProgram? = null
    private var pass6HalationBlur: ShaderProgram? = null
    private var pass6HalationComposite: ShaderProgram? = null

    // ── Ping-pong FBOs ──
    private var fboA = 0
    private var fboTexA = 0
    private var fboB = 0
    private var fboTexB = 0

    // ── Halation FBO (Downsampled) ──
    private var fboHalation = 0
    private var fboTexHalation = 0
    private var halationWidth = 0
    private var halationHeight = 0

    // ── Geometry ──
    private var vao = 0
    private var vbo = 0

    // ── Dimensions ──
    private var viewportWidth = 0
    private var viewportHeight = 0

    // ── State ──
    private var isInitialized = false
    private var frameIndex = 0L  // Monotonically increasing for deterministic grain in preview

    // Full-screen quad: 2D position + 2D texcoord
    private val quadVertices = floatArrayOf(
        // position    // texcoord
        -1f, -1f,      0f, 0f,
         1f, -1f,      1f, 0f,
        -1f,  1f,      0f, 1f,
         1f,  1f,      1f, 1f
    )

    /**
     * Initializes the GL rendering pipeline.
     * Must be called on the GL thread after EGL context creation.
     *
     * @param width Viewport width in pixels
     * @param height Viewport height in pixels
     */
    fun initialize(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height

        Log.d(TAG, "Initializing renderer: ${width}x${height}")

        // Compile all shader programs
        pass1OesToRgb = ShaderProgram(
            ShaderSources.PASS1_OES_TO_RGB_VERTEX,
            ShaderSources.PASS1_OES_TO_RGB_FRAGMENT
        )
        pass2ToneCurve = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS2_TONE_CURVE_FRAGMENT
        )
        pass3ColorGrade = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS3_COLOR_GRADE_FRAGMENT
        )
        pass4ColorChrome = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS4_COLOR_CHROME_FRAGMENT
        )
        pass5Grain = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS5_GRAIN_FRAGMENT
        )
        pass6HalationBlur = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS6_HALATION_BLUR_FRAGMENT
        )
        pass6HalationComposite = ShaderProgram(
            ShaderSources.VERTEX_SHADER,
            ShaderSources.PASS6_HALATION_FRAGMENT
        )

        // Create ping-pong FBOs
        createFbo(width, height).let { (fbo, tex) ->
            fboA = fbo; fboTexA = tex
        }
        createFbo(width, height).let { (fbo, tex) ->
            fboB = fbo; fboTexB = tex
        }

        // Create downsampled FBO for halation (1/4th resolution)
        halationWidth = width / 4
        halationHeight = height / 4
        createFbo(halationWidth, halationHeight).let { (fbo, tex) ->
            fboHalation = fbo; fboTexHalation = tex
        }

        // Create fullscreen quad VAO
        createQuadVao()

        isInitialized = true
        Log.d(TAG, "Renderer initialized successfully")
    }

    /**
     * Renders a single frame through the full 5-pass pipeline.
     *
     * @param cameraTextureId The OES texture ID from the camera SurfaceTexture
     * @param texMatrix The 4x4 texture transform matrix from SurfaceTexture.getTransformMatrix()
     * @param params The film simulation parameters driving all shader uniforms
     * @param lutTextureId The 3D LUT texture ID for the current simulation (-1 to skip)
     */
    fun renderFrame(
        cameraTextureId: Int,
        texMatrix: FloatArray,
        params: FilmSimulationParams,
        lutTextureId: Int
    ) {
        if (!isInitialized) return

        frameIndex++

        // ── PASS 1: External OES → Linear RGB → FBO_A ──
        renderPass1(cameraTextureId, texMatrix)

        // ── PASS 2: Tone Curves (read FBO_A → write FBO_B) ──
        renderPass2(params)

        // ── PASS 3: 3D LUT + Hue Saturation (read FBO_B → write FBO_A) ──
        renderPass3(params, lutTextureId)

        // ── PASS 4: Color Chrome (read FBO_A → write FBO_B) ──
        renderPass4(params)

        // ── PASS 6.1: Halation Blur (read FBO_B → write fboHalation) ──
        renderPassHalationBlur()

        // ── PASS 6.2: Halation Composite (read FBO_B + fboHalation → write FBO_A) ──
        renderPassHalationComposite(params)

        // ── PASS 5: Film Grain (read FBO_A → write SCREEN) ──
        renderPass5(params)
    }

    /**
     * Pass 1: Camera OES texture → FBO_A
     */
    private fun renderPass1(cameraTextureId: Int, texMatrix: FloatArray) {
        val program = pass1OesToRgb ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        // Bind camera texture to unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        program.setInt("u_cameraTexture", 0)

        // Set texture transform matrix
        val matLocation = GLES30.glGetUniformLocation(program.programId, "u_texMatrix")
        GLES30.glUniformMatrix4fv(matLocation, 1, false, texMatrix, 0)

        drawQuad()
    }

    /**
     * Pass 2: Per-channel tone curves. Read FBO_A → Write FBO_B
     */
    private fun renderPass2(params: FilmSimulationParams) {
        val program = pass2ToneCurve ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        // Bind FBO_A texture
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexA)
        program.setInt("u_inputTexture", 0)

        // Set tone curve uniforms
        program.setVec3("u_shadows", params.shadowsR, params.shadowsG, params.shadowsB)
        program.setVec3("u_highlights", params.highlightsR, params.highlightsG, params.highlightsB)
        program.setFloat("u_contrast", params.contrast)
        program.setFloat("u_blackPoint", params.blackPoint)

        drawQuad()
    }

    /**
     * Pass 3: 3D LUT + hue-dependent saturation. Read FBO_B → Write FBO_A
     */
    private fun renderPass3(params: FilmSimulationParams, lutTextureId: Int) {
        val program = pass3ColorGrade ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        // Bind FBO_B texture to unit 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexB)
        program.setInt("u_inputTexture", 0)

        // Bind 3D LUT to unit 1
        if (lutTextureId > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
            program.setInt("u_lutTexture", 1)
            program.setFloat("u_lutStrength", params.lutStrength)
        } else {
            program.setFloat("u_lutStrength", 0f)  // Bypass LUT
        }

        // Set hue-dependent saturation multipliers
        program.setFloatArray("u_satMults", params.saturationMultipliers)
        program.setFloat("u_globalSaturation", params.globalSaturation)

        // Monochrome settings
        program.setInt("u_isMonochrome", if (params.isMonochrome) 1 else 0)
        program.setVec3("u_monoWeights",
            params.monoRedWeight, params.monoGreenWeight, params.monoBlueWeight)

        // Skin Tone Protection
        program.setFloat("u_skinToneProtection", params.skinToneProtection)

        drawQuad()
    }

    /**
     * Pass 4: Color Chrome Effect. Read FBO_A → Write FBO_B
     */
    private fun renderPass4(params: FilmSimulationParams) {
        val program = pass4ColorChrome ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexA)
        program.setInt("u_inputTexture", 0)

        program.setFloat("u_chromeStrength", params.colorChromeStrength)
        program.setFloat("u_chromeThreshold", params.colorChromeThreshold)
        program.setFloat("u_skinToneProtection", params.skinToneProtection)

        drawQuad()
    }

    /**
     * Pass 6.1: Halation Blur. Read FBO_B → Write fboHalation
     */
    private fun renderPassHalationBlur() {
        val program = pass6HalationBlur ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboHalation)
        GLES30.glViewport(0, 0, halationWidth, halationHeight)

        program.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexB) // Read from Pass 4 output
        program.setInt("u_inputTexture", 0)

        program.setVec2("u_texelSize", 1.0f / halationWidth, 1.0f / halationHeight)

        drawQuad()
    }

    /**
     * Pass 6.2: Halation Composite. Read FBO_B + fboHalation → Write FBO_A
     */
    private fun renderPassHalationComposite(params: FilmSimulationParams) {
        val program = pass6HalationComposite ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        // Original image from Pass 4
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexB)
        program.setInt("u_inputTexture", 0)

        // Blurred bloom map
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexHalation)
        program.setInt("u_blurTexture", 1)

        program.setFloat("u_halationIntensity", params.bloomIntensity)

        drawQuad()
    }

    /**
     * Pass 5: Film grain → Screen framebuffer. Read FBO_A → Write Screen
     */
    private fun renderPass5(params: FilmSimulationParams) {
        val program = pass5Grain ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)  // Screen
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        program.use()

        // We read from FBO_A now because Halation Composite wrote its output there.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexA)
        program.setInt("u_inputTexture", 0)

        program.setVec2("u_resolution", viewportWidth.toFloat(), viewportHeight.toFloat())
        program.setFloat("u_grainIntensity", params.grainIntensity)
        program.setFloat("u_grainSize", params.grainSize)
        program.setFloat("u_grainLumaResponse", params.grainLumaResponse)
        program.setFloat("u_vignetteStrength", params.vignetteStrength)
        // Deterministic seed: monotonically increasing frame index
        // For final capture, the caller would pass captureTimestamp instead
        program.setFloat("u_grainSeed", (frameIndex % 10000).toFloat())

        drawQuad()
    }

    /**
     * Draws the full-screen quad. Called once per pass.
     */
    private fun drawQuad() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Creates a framebuffer object with an attached RGBA texture.
     *
     * @return Pair of (FBO ID, texture ID)
     */
    private fun createFbo(width: Int, height: Int): Pair<Int, Int> {
        // Create texture
        val texIds = intArrayOf(0)
        GLES30.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
            width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        // GL_LINEAR for deterministic trilinear interpolation
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Create FBO and attach texture
        val fboIds = intArrayOf(0)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texId, 0
        )

        // Verify FBO completeness
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete: status=$status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return Pair(fboId, texId)
    }

    /**
     * Creates the full-screen quad VAO with position + texcoord attributes.
     */
    private fun createQuadVao() {
        val vertexBuffer: FloatBuffer = ByteBuffer
            .allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadVertices)
            .also { it.position(0) }

        // Create VAO
        val vaoIds = intArrayOf(0)
        GLES30.glGenVertexArrays(1, vaoIds, 0)
        vao = vaoIds[0]
        GLES30.glBindVertexArray(vao)

        // Create VBO
        val vboIds = intArrayOf(0)
        GLES30.glGenBuffers(1, vboIds, 0)
        vbo = vboIds[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            quadVertices.size * 4,
            vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Position attribute (location = 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)

        // TexCoord attribute (location = 1)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Releases all GL resources.
     * Must be called on the GL thread before EGL context is destroyed.
     */
    fun release() {
        Log.d(TAG, "Releasing renderer resources")

        pass1OesToRgb?.release()
        pass2ToneCurve?.release()
        pass3ColorGrade?.release()
        pass4ColorChrome?.release()
        pass5Grain?.release()
        pass6HalationBlur?.release()
        pass6HalationComposite?.release()

        if (fboA != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        if (fboHalation != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboHalation), 0)
        if (fboTexA != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexA), 0)
        if (fboTexB != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexB), 0)
        if (fboTexHalation != 0) GLES30.glDeleteTextures(1, intArrayOf(fboTexHalation), 0)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)

        isInitialized = false
    }
}
