package com.truechrome.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * TrueChrome Dark Color Scheme — the ONLY color scheme.
 *
 * WHY NO LIGHT THEME:
 * A camera app with a light theme would:
 * 1. Blind the user when shooting in dark environments
 * 2. Wash out the viewfinder preview with reflected UI light
 * 3. Cause the camera's AE to compensate for screen brightness
 * 4. Look unprofessional — no serious camera has a white UI
 */
private val TrueChromeDarkColorScheme = darkColorScheme(
    primary = TrueChromeAccent,
    onPrimary = SurfaceBlack,
    primaryContainer = TrueChromeAccentDim,
    onPrimaryContainer = TrueChromeAccentBright,

    secondary = TextSecondary,
    onSecondary = SurfaceBlack,

    background = SurfaceBlack,
    onBackground = TextPrimary,

    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,

    error = ErrorRed,
    onError = SurfaceBlack
)

@Composable
fun TrueChromeTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Make status bar and navigation bar fully transparent
            // so the viewfinder extends edge-to-edge
            window.statusBarColor = SurfaceBlack.toArgb()
            window.navigationBarColor = SurfaceBlack.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = TrueChromeDarkColorScheme,
        typography = TrueChromeTypography,
        content = content
    )
}
