package com.truechrome.app.di

import com.truechrome.app.camera.data.ZslRingBuffer
import com.truechrome.app.processing.computational.BurstEngine
import com.truechrome.app.processing.computational.ImageProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ProcessingModule — Provides computational photography dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProcessingModule {

    @Provides
    @Singleton
    fun provideZslRingBuffer(): ZslRingBuffer {
        // Reduced to 8 to prevent ImageReader starvation
        return ZslRingBuffer(capacity = 8)
    }

    @Provides
    @Singleton
    fun provideBurstEngine(): BurstEngine {
        return BurstEngine()
    }

    @Provides
    @Singleton
    fun provideImageProcessor(burstEngine: BurstEngine): ImageProcessor {
        return ImageProcessor(burstEngine)
    }
}
