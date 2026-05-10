package com.truechrome.app.processing.gl

import android.opengl.GLES30
import android.util.Log

/**
 * ShaderProgram — Compiles, links, and manages an OpenGL ES 3.0 shader program.
 *
 * Each ShaderProgram represents a single render pass in the multi-pass pipeline.
 * Programs are compiled once and reused every frame — no per-frame compilation.
 *
 * DETERMINISM:
 * Shader programs are pure functions of their source code. The same GLSL source
 * always compiles to the same GPU program on the same device. Uniform values
 * are set externally and are deterministic (from FilmSimulationParams).
 *
 * ERROR HANDLING:
 * Shader compilation errors are logged with full GLSL error messages.
 * In production, a failed shader falls back to passthrough (identity).
 */
class ShaderProgram(
    vertexShaderSource: String,
    fragmentShaderSource: String
) {
    companion object {
        private const val TAG = "ShaderProgram"
    }

    /** The OpenGL program ID. -1 if compilation failed. */
    val programId: Int

    /** Whether this program compiled and linked successfully */
    val isValid: Boolean

    // Cache uniform locations to avoid per-frame glGetUniformLocation calls
    private val uniformCache = mutableMapOf<String, Int>()

    init {
        var vertexShader = 0
        var fragmentShader = 0
        var program = 0
        var valid = false

        try {
            // Compile vertex shader
            vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderSource)
            if (vertexShader == 0) {
                throw RuntimeException("Vertex shader compilation failed")
            }

            // Compile fragment shader
            fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderSource)
            if (fragmentShader == 0) {
                throw RuntimeException("Fragment shader compilation failed")
            }

            // Link program
            program = GLES30.glCreateProgram()
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            // Check link status
            val linkStatus = intArrayOf(0)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                Log.e(TAG, "Program link failed: $log")
                GLES30.glDeleteProgram(program)
                program = 0
                throw RuntimeException("Program linking failed: $log")
            }

            valid = true
        } catch (e: Exception) {
            Log.e(TAG, "ShaderProgram creation failed", e)
        } finally {
            // Shaders can be deleted after linking — they're baked into the program
            if (vertexShader != 0) GLES30.glDeleteShader(vertexShader)
            if (fragmentShader != 0) GLES30.glDeleteShader(fragmentShader)
        }

        programId = program
        isValid = valid
    }

    /**
     * Activates this shader program for rendering.
     * Must be called before setting uniforms or drawing.
     */
    fun use() {
        if (isValid) {
            GLES30.glUseProgram(programId)
        }
    }

    /**
     * Sets a float uniform value.
     * Location is cached after first lookup for zero-overhead subsequent calls.
     */
    fun setFloat(name: String, value: Float) {
        GLES30.glUniform1f(getUniformLocation(name), value)
    }

    /** Sets a vec2 uniform. */
    fun setVec2(name: String, x: Float, y: Float) {
        GLES30.glUniform2f(getUniformLocation(name), x, y)
    }

    /** Sets a vec3 uniform. */
    fun setVec3(name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(getUniformLocation(name), x, y, z)
    }

    /** Sets an integer uniform (used for texture unit binding). */
    fun setInt(name: String, value: Int) {
        GLES30.glUniform1i(getUniformLocation(name), value)
    }

    /** Sets a float array uniform (used for hue-dependent saturation multipliers). */
    fun setFloatArray(name: String, values: FloatArray) {
        GLES30.glUniform1fv(getUniformLocation(name), values.size, values, 0)
    }

    /** Releases GPU resources. Call when the program is no longer needed. */
    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
        }
        uniformCache.clear()
    }

    /**
     * Gets (and caches) the uniform location for the given name.
     * Returns -1 if the uniform doesn't exist (this is not an error —
     * the GLSL compiler may optimize away unused uniforms).
     */
    private fun getUniformLocation(name: String): Int {
        return uniformCache.getOrPut(name) {
            GLES30.glGetUniformLocation(programId, name)
        }
    }

    /**
     * Compiles a single shader (vertex or fragment).
     *
     * @param type GLES30.GL_VERTEX_SHADER or GLES30.GL_FRAGMENT_SHADER
     * @param source The GLSL source code
     * @return The compiled shader ID, or 0 on failure
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val compileStatus = intArrayOf(0)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)

        if (compileStatus[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            val typeName = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            Log.e(TAG, "$typeName shader compilation failed: $log")
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }
}
