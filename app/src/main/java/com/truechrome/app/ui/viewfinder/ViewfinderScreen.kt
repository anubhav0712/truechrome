package com.truechrome.app.ui.viewfinder

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.truechrome.app.camera.presentation.CameraUiState
import com.truechrome.app.camera.presentation.CameraViewModel
import com.truechrome.app.processing.color.FilmSimulation
import com.truechrome.app.ui.components.FilmSimSelector
import com.truechrome.app.ui.components.ShutterButton
import com.truechrome.app.ui.theme.SurfaceBlack
import com.truechrome.app.ui.theme.TextSecondary
import com.truechrome.app.ui.theme.TrueChromeAccent

/**
 * ViewfinderScreen — The main camera screen.
 *
 * DESIGN PHILOSOPHY: "Zero-UI"
 * The viewfinder dominates the screen. Controls are minimal and only appear
 * when needed (via edge swipes or taps). The user's attention should be on
 * the scene, not the interface.
 *
 * LIFECYCLE INTEGRATION:
 * Uses LifecycleEventObserver to call ViewModel.onResume/onPause,
 * which triggers camera open/close. This ensures:
 * - Camera opens when the app is in the foreground
 * - Camera closes when the app goes to background (releases hardware for other apps)
 * - No camera session survives Activity destruction
 */
@Composable
fun ViewfinderScreen(
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ── Permission handling ──
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Lifecycle observer: camera open/close on resume/pause ──
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                else -> { /* ignore other events */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBlack)
    ) {
        // ── Viewfinder Area ──
        // Phase 3 will replace this with GLTextureView.
        // For now, show camera status information.
        ViewfinderContent(
            uiState = uiState,
            viewModel = viewModel,
            onTapToFocus = { x, y -> viewModel.onTapToFocus(x, y) },
            modifier = Modifier.fillMaxSize()
        )

        // ── Film Simulation Indicator (top-left) ──
        FilmSimIndicator(
            simulation = uiState.selectedSimulation,
            onClick = { viewModel.toggleSimSelector() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 16.dp)
        )

        // ── Camera Info (top-right) ──
        CameraInfoBadge(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 20.dp, top = 16.dp)
        )

        // ── Bottom Controls ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Film simulation picker (animated visibility)
            AnimatedVisibility(
                visible = uiState.isSimSelectorVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                FilmSimSelector(
                    selectedSimulation = uiState.selectedSimulation,
                    onSimulationSelected = { viewModel.selectSimulation(it) },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Shutter button
            ShutterButton(
                onClick = { viewModel.onShutterPressed() },
                enabled = uiState.isPreviewing && !uiState.isCapturing
            )
        }

        // ── Error overlay ──
        uiState.errorMessage?.let { error ->
            ErrorOverlay(
                message = error,
                onDismiss = { viewModel.dismissError() },
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * Viewfinder content area — handles tap-to-focus gestures.
 * Phase 3 will replace the placeholder with the actual GL surface.
 */
@Composable
private fun ViewfinderContent(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    onTapToFocus: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(SurfaceBlack)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Normalize tap coordinates to 0..1 range
                    val x = offset.x / size.width
                    val y = offset.y / size.height
                    onTapToFocus(x, y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            !uiState.hasPermission -> {
                Text(
                    text = "Grant camera permission to begin",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            uiState.isLoading -> {
                Text(
                    text = "Initializing camera...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            uiState.isPreviewing -> {
                val previewSize = uiState.cameraConfig?.previewSize
                // Camera2 sizes are landscape (width > height), so portrait aspect ratio is height / width
                val aspectRatio = if (previewSize != null) {
                    previewSize.height.toFloat() / previewSize.width.toFloat()
                } else {
                    3f / 4f
                }

                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { context ->
                        com.truechrome.app.ui.viewfinder.GLTextureView(
                            context,
                            viewModel.lutManager
                        ).apply {
                            onCameraSurfaceReady = { surface ->
                                viewModel.onCameraSurfaceReady(surface)
                            }
                        }
                    },
                    update = { view ->
                        view.currentSimulation = uiState.selectedSimulation
                        view.cameraPreviewSize = uiState.cameraConfig?.previewSize
                    },
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                )
            }
        }
    }
}

/**
 * Film simulation indicator — small label in the top-left corner.
 * Tapping it toggles the simulation picker.
 */
@Composable
private fun FilmSimIndicator(
    simulation: FilmSimulation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = simulation.displayName.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = simulation.accentColor,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        )
        Text(
            text = simulation.subtitle,
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextSecondary,
                fontSize = 8.sp
            )
        )
    }
}

/**
 * Camera info badge — shows hardware capabilities in top-right corner.
 * Helps confirm that the camera is operating in the expected mode.
 */
@Composable
private fun CameraInfoBadge(
    uiState: CameraUiState,
    modifier: Modifier = Modifier
) {
    if (!uiState.isPreviewing) return

    val config = uiState.cameraConfig ?: return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End
    ) {
        // Hardware level indicator
        Text(
            text = if (config.isLevel3) "LEVEL 3" else "LIMITED",
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (config.isLevel3) TrueChromeAccent else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )
        )

        // Resolution
        Text(
            text = "${config.captureSize.width}×${config.captureSize.height}",
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextSecondary,
                fontSize = 8.sp
            )
        )
    }
}

/**
 * Error overlay — semi-transparent overlay with error message.
 */
@Composable
private fun ErrorOverlay(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(32.dp)
        )
    }
}
