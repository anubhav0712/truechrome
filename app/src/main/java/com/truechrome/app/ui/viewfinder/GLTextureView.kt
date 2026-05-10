package com.truechrome.app.ui.viewfinder

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.truechrome.app.processing.color.FilmSimulationParams
import com.truechrome.app.processing.gl.GLCameraRenderer
import com.truechrome.app.processing.gl.LutManager

/**
 * GLTextureView — Custom TextureView that manages an EGL 3.0 context and
 * drives the multi-pass shader rendering pipeline.
 *
 * WHY TextureView (not GLSurfaceView):
 * - GLSurfaceView doesn't compose well with Jetpack Compose's AndroidView
 * - TextureView allows proper z-ordering with Compose overlays
 * - We manage the EGL context ourselves for maximum control over lifecycle
 *
 * THREADING:
 * - Creates a dedicated render thread for GL operations
 * - SurfaceTexture.OnFrameAvailableListener triggers render on each camera frame
 * - GL calls ONLY happen on the render thread (enforced by Handler)
 *
 * LIFECYCLE:
 * - EGL context created when the surface becomes available
 * - EGL context destroyed when the surface is destroyed
 * - Camera preview surface is obtained from SurfaceTexture
 */
class GLTextureView(
    context: Context,
    private val lutManager: LutManager
) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "GLTextureView"
    }

    // ── EGL state ──
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // ── Renderer ──
    private val renderer = GLCameraRenderer()

    // ── Camera texture ──
    private var cameraTextureId = -1
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    // ── Render thread ──
    private var renderThread: RenderThread? = null

    // ── Current film simulation ──
    @Volatile
    var currentSimulation: com.truechrome.app.processing.color.FilmSimulation? = null

    // ── Camera Preview Size ──
    @Volatile
    var cameraPreviewSize: android.util.Size? = null

    // ── Callback for when the camera surface is ready ──
    var onCameraSurfaceReady: ((Surface) -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    // ══════════════════════════════════════════════════════
    // SurfaceTextureListener — manages EGL lifecycle
    // ══════════════════════════════════════════════════════

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "Surface available: ${width}x${height}")

        renderThread = RenderThread(surface, width, height).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "Surface size changed: ${width}x${height}")
        renderThread?.updateSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        Log.d(TAG, "Surface destroyed — releasing GL resources")
        renderThread?.quit()
        renderThread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Not used — we drive rendering from camera frame callbacks
    }

    /**
     * Releases all GL and camera resources.
     * Call this when the view is being removed from the hierarchy.
     */
    fun release() {
        renderThread?.quit()
        renderThread = null
    }

    // ══════════════════════════════════════════════════════
    // Render Thread
    // ══════════════════════════════════════════════════════

    /**
     * Dedicated render thread for GL operations.
     *
     * All GL calls MUST happen on this thread. Camera frames arrive
     * on the camera handler thread and signal this thread to render.
     */
    private inner class RenderThread(
        private val hostSurface: SurfaceTexture,
        private var width: Int,
        private var height: Int
    ) : Thread("TrueChrome-GL") {

        @Volatile
        private var running = true

        @Volatile
        private var frameAvailable = false

        private val lock = Object()

        override fun run() {
            // Initialize EGL
            if (!initEgl(hostSurface)) {
                Log.e(TAG, "EGL initialization failed")
                return
            }

            // Initialize renderer
            renderer.initialize(width, height)

            // Create camera texture (OES)
            cameraTextureId = createOesTexture()
            cameraSurfaceTexture = SurfaceTexture(cameraTextureId).also { st ->
                val previewSize = cameraPreviewSize
                if (previewSize != null) {
                    st.setDefaultBufferSize(previewSize.width, previewSize.height)
                }
                st.setOnFrameAvailableListener {
                    synchronized(lock) {
                        frameAvailable = true
                        lock.notifyAll()
                    }
                }
                cameraSurface = Surface(st)
            }

            // Notify that camera surface is ready
            post { onCameraSurfaceReady?.invoke(cameraSurface!!) }

            // Render loop
            while (running) {
                synchronized(lock) {
                    while (!frameAvailable && running) {
                        try {
                            lock.wait(100)  // Timeout prevents deadlock on quit
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    if (!running) return
                    frameAvailable = false
                }

                // Update the camera texture with latest frame
                try {
                    cameraSurfaceTexture?.updateTexImage()
                    cameraSurfaceTexture?.getTransformMatrix(texMatrix)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update camera texture", e)
                    continue
                }

                // Render through the 5-pass pipeline
                val simulation = currentSimulation ?: continue
                val lutTextureId = lutManager.uploadLutToGL(simulation)

                renderer.renderFrame(
                    cameraTextureId,
                    texMatrix,
                    simulation.params,
                    lutTextureId
                )

                // Swap buffers (display the result)
                val display = eglDisplay ?: continue
                val surface = eglSurface ?: continue
                EGL14.eglSwapBuffers(display, surface)
            }

            // Cleanup
            renderer.release()
            releaseEgl()
            cameraSurfaceTexture?.release()
            cameraSurface?.release()
        }

        fun updateSize(newWidth: Int, newHeight: Int) {
            width = newWidth
            height = newHeight
            // TODO: Recreate FBOs at new size
        }

        fun quit() {
            running = false
            synchronized(lock) {
                lock.notifyAll()
            }
            try {
                join(2000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Render thread join interrupted")
            }
        }

        /**
         * Initializes EGL 3.0 context.
         */
        private fun initEgl(surface: SurfaceTexture): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "No EGL display")
                return false
            }

            val version = intArrayOf(0, 0)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            // Request OpenGL ES 3.0 context
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = intArrayOf(0)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            if (numConfigs[0] == 0) {
                Log.e(TAG, "No suitable EGL config found")
                return false
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
            )

            eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
            )

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], surface, surfaceAttribs, 0
            )

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            Log.d(TAG, "EGL initialized: GL_VERSION=${GLES30.glGetString(GLES30.GL_VERSION)}")
            return true
        }

        private fun releaseEgl() {
            eglDisplay?.let { display ->
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
                EGL14.eglTerminate(display)
            }
            eglDisplay = null
            eglContext = null
            eglSurface = null
        }

        private fun createOesTexture(): Int {
            val texIds = intArrayOf(0)
            GLES30.glGenTextures(1, texIds, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return texIds[0]
        }
    }
}
