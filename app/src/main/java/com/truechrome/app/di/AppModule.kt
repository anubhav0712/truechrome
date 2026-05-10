package com.truechrome.app.di

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Vibrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AppModule — Provides application-scoped dependencies.
 *
 * WHY SINGLETON SCOPE:
 * System services like CameraManager and Vibrator are inherently singletons —
 * the OS maintains a single instance. We mirror this in our DI graph to avoid
 * redundant lookups and ensure consistent state across all injection sites.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the system Vibrator service for haptic feedback.
     *
     * Used by HapticsController to deliver deterministic shutter-click feedback.
     * Singleton because the underlying system service is already a singleton.
     */
    @Provides
    @Singleton
    fun provideVibrator(
        @ApplicationContext context: Context
    ): Vibrator = context.getSystemService(Vibrator::class.java)
}
