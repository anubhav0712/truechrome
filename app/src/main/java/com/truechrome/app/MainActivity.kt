package com.truechrome.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.truechrome.app.ui.TrueChromeApp
import com.truechrome.app.ui.theme.TrueChromeTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity — The single Activity host for all Compose UI.
 *
 * WHY SINGLE ACTIVITY:
 * Camera apps benefit enormously from single-Activity architecture because:
 * 1. Camera sessions are tied to Activity lifecycle — fewer Activities = fewer session teardowns
 * 2. The OpenGL rendering context (EGL) is expensive to create/destroy
 * 3. SurfaceTexture must be managed carefully across config changes
 * 4. Compose handles all "screen" navigation internally without Activity recreation
 *
 * WHY @AndroidEntryPoint:
 * Enables Hilt injection into this Activity. The CameraViewModel (injected via hiltViewModel())
 * receives its UseCases and Repository through Hilt's generated code.
 *
 * LIFECYCLE NOTES:
 * - screenOrientation is locked to portrait in manifest to prevent config-change session teardowns
 * - enableEdgeToEdge() ensures the viewfinder extends behind system bars for immersive experience
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge rendering — the camera viewfinder should fill the entire screen
        // including the area behind the status bar and navigation bar
        enableEdgeToEdge()

        setContent {
            TrueChromeTheme {
                TrueChromeApp(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
