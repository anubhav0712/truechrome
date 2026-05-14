package com.truechrome.app.ui.viewfinder

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.truechrome.app.ui.components.ShutterButton

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.content.Intent
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Top Spacing (Status Bar + extra)
        Spacer(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(48.dp)
        )

        // 2. Viewfinder Area (4:3 Aspect Ratio)
        val previewSize = uiState.cameraConfig?.previewSize
        val aspectRatio = if (previewSize != null) {
            previewSize.height.toFloat() / previewSize.width.toFloat()
        } else {
            3f / 4f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(Color(0xFF111111))
        ) {
            ViewfinderContent(
                uiState = uiState,
                viewModel = viewModel,
                onTapToFocus = { x, y -> viewModel.onTapToFocus(x, y) },
                modifier = Modifier.fillMaxSize()
            )

            // Grid overlay (optional, adds to the camera feel)
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        // 3. Bottom Controls Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (uiState.isPreviewing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Filter Slider
                    FilterSlider(
                        simulations = FilmSimulation.entries,
                        selectedSimulation = uiState.selectedSimulation,
                        onSimulationSelected = { viewModel.selectSimulation(it) }
                    )

                    // Bottom Row: Thumbnail & Shutter
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Thumbnail on the left
                        uiState.latestPhotoUri?.let { uri ->
                            val context = LocalContext.current
                            AsyncImage(
                                model = uri,
                                contentDescription = "Latest Photo",
                                modifier = Modifier
                                    .size(56.dp)
                                    .align(Alignment.CenterStart)
                                    .clip(CircleShape)
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "image/jpeg")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        
                                        // Attempt to bypass the chooser by explicitly setting the default gallery package
                                        val resolveInfo = context.packageManager.resolveActivity(
                                            intent,
                                            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                                        )
                                        if (resolveInfo != null && resolveInfo.activityInfo.packageName != "android") {
                                            intent.setPackage(resolveInfo.activityInfo.packageName)
                                        }
                                        
                                        context.startActivity(intent)
                                    },
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Shutter Button in the center
                        ShutterButton(
                            onClick = { viewModel.onShutterPressed() },
                            enabled = !uiState.isCapturing
                        )
                    }
                }
            }
        }
    }

    // ── Error overlay ──
    uiState.errorMessage?.let { error ->
        ErrorOverlay(
            message = error,
            onDismiss = { viewModel.dismissError() },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.2f)
        
        // Horizontal lines
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f),
            strokeWidth = strokeWidth
        )
        
        // Vertical lines
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
private fun FilterSlider(
    simulations: List<FilmSimulation>,
    selectedSimulation: FilmSimulation,
    onSimulationSelected: (FilmSimulation) -> Unit,
    modifier: Modifier = Modifier
) {
    ScrollableTabRow(
        selectedTabIndex = simulations.indexOf(selectedSimulation),
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        contentColor = Color.White,
        edgePadding = 32.dp,
        indicator = { }, // Hide the default underline indicator
        divider = { }    // Hide the bottom divider
    ) {
        simulations.forEach { simulation ->
            val isSelected = simulation == selectedSimulation
            Tab(
                selected = isSelected,
                onClick = { onSimulationSelected(simulation) },
                text = {
                    Text(
                        text = simulation.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 1.sp,
                            color = if (isSelected) simulation.accentColor else Color.White.copy(alpha = 0.5f)
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun ViewfinderContent(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    onTapToFocus: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val simulations = FilmSimulation.entries.toTypedArray()
    val currentIndex = simulations.indexOf(uiState.selectedSimulation)

    fun selectNext() {
        val nextIndex = (currentIndex + 1) % simulations.size
        viewModel.selectSimulation(simulations[nextIndex])
    }

    fun selectPrev() {
        val prevIndex = (currentIndex - 1 + simulations.size) % simulations.size
        viewModel.selectSimulation(simulations[prevIndex])
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val x = offset.x / size.width
                    val y = offset.y / size.height
                    onTapToFocus(x, y)
                }
            }
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 50f) {
                            selectPrev()
                        } else if (totalDrag < -50f) {
                            selectNext()
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = {
                        totalDrag = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    totalDrag += dragAmount
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            !uiState.hasPermission -> {
                Text(
                    text = "Grant camera permission to begin",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            uiState.isLoading -> {
                Text(
                    text = "Initializing camera...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
            uiState.isPreviewing -> {
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Shutter Flash Animation
        var showFlash by remember { mutableStateOf(false) }
        LaunchedEffect(uiState.isCapturing) {
            if (uiState.isCapturing) {
                showFlash = true
                kotlinx.coroutines.delay(50)
                showFlash = false
            }
        }
        val flashAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (showFlash) 0.8f else 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = if (showFlash) 0 else 300
            ),
            label = "FlashAlpha"
        )
        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flashAlpha))
            )
        }
    }
}

@Composable
private fun ErrorOverlay(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
