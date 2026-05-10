package com.truechrome.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * TrueChromeApplication — The application entry point.
 *
 * WHY @HiltAndroidApp:
 * This annotation triggers Hilt's code generation, creating the base class that serves
 * as the application-level dependency container. All @Inject constructors, @Module providers,
 * and @AndroidEntryPoint components are wired through this generated class.
 *
 * For a camera app, this is critical because:
 * - CameraManager (system service) needs to be provided as a singleton
 * - The GL rendering context and LUT manager need application-scoped lifecycle
 * - ViewModels need injected UseCases which need injected Repositories
 * - The entire dependency graph must be deterministic and leak-free
 */
@HiltAndroidApp
class TrueChromeApplication : Application()
