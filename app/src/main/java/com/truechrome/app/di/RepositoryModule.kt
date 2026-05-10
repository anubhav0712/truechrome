package com.truechrome.app.di

import com.truechrome.app.camera.data.CameraRepositoryImpl
import com.truechrome.app.camera.domain.CameraRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * RepositoryModule — Binds repository interfaces to their implementations.
 *
 * WHY @Binds INSTEAD OF @Provides:
 * @Binds is more efficient for interface→implementation binding because Dagger
 * doesn't need to generate a factory method — it directly maps the type.
 * @Provides is used when construction logic is needed (e.g., system services).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCameraRepository(
        impl: CameraRepositoryImpl
    ): CameraRepository
}
