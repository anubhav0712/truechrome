package com.truechrome.app.di

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * CameraModule — Provides camera-specific dependencies.
 *
 * WHY DEDICATED MODULE:
 * Camera2 API requires careful thread management. All camera callbacks MUST run on
 * a background HandlerThread (never the main thread) to prevent ANRs and ensure
 * the UI thread remains free for 60fps rendering. This module ensures every
 * component that touches Camera2 receives the same Handler/Thread pair.
 *
 * THREAD SAFETY:
 * The "CameraHandler" is the single point of serialization for all Camera2 callbacks.
 * This eliminates race conditions between onOpened, onConfigured, onCaptureCompleted, etc.
 */
@Module
@InstallIn(SingletonComponent::class)
object CameraModule {

    /**
     * Provides the system CameraManager.
     *
     * CameraManager is the entry point for all Camera2 operations:
     * - Enumerating camera IDs
     * - Querying CameraCharacteristics (hardware capabilities)
     * - Opening camera devices
     *
     * Singleton because the system service is inherently single-instance.
     */
    @Provides
    @Singleton
    fun provideCameraManager(
        @ApplicationContext context: Context
    ): CameraManager = context.getSystemService(CameraManager::class.java)

    /**
     * Provides a dedicated background HandlerThread for Camera2 callbacks.
     *
     * WHY NOT Dispatchers.IO:
     * Camera2's callback API requires an Android Handler, not a coroutine dispatcher.
     * While we bridge callbacks to Flows using callbackFlow, the underlying callbacks
     * still need a Handler. Using a dedicated thread (vs. the main thread) prevents:
     * - ANRs from blocking camera operations
     * - Frame drops from preview callback contention with UI rendering
     *
     * The thread is started immediately and lives for the app's lifetime.
     * This is intentional — camera operations can happen at any time and the
     * cost of thread creation/destruction during rapid lifecycle changes is prohibitive.
     */
    @Provides
    @Singleton
    @Named("CameraThread")
    fun provideCameraHandlerThread(): HandlerThread {
        return HandlerThread("TrueChrome-Camera").also { it.start() }
    }

    /**
     * Provides the Handler bound to the camera background thread.
     *
     * This Handler is passed to ALL Camera2 API calls:
     * - cameraManager.openCamera(..., handler)
     * - cameraDevice.createCaptureSession(..., handler)
     * - cameraCaptureSession.setRepeatingRequest(..., handler)
     *
     * By using a single Handler, all camera callbacks are serialized on one thread,
     * eliminating race conditions between session state changes and capture results.
     */
    @Provides
    @Singleton
    @Named("CameraHandler")
    fun provideCameraHandler(
        @Named("CameraThread") thread: HandlerThread
    ): Handler = Handler(thread.looper)
}
