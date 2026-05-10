package com.truechrome.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.truechrome.app.ui.theme.TrueChromeAccent
import com.truechrome.app.ui.theme.TextPrimary

/**
 * ShutterButton — The primary capture button.
 *
 * Design: Double-ring circle inspired by Fujifilm's X-series shutter release.
 * - Outer ring: thin white border (always visible)
 * - Inner circle: gold fill that scales down on press (spring physics)
 * - Press animation uses spring() for a satisfying mechanical "depress" feel
 *
 * HAPTIC FEEDBACK:
 * The actual haptic is triggered by the ViewModel (not here) to ensure it fires
 * at the exact moment the ring buffer frame is extracted, not when the user touches
 * the button. This guarantees the haptic correlates with actual capture, not UI latency.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Spring animation for press feedback — mimics mechanical shutter depression
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.4f,  // Slightly underdamped — small bounce on release
            stiffness = 800f      // Crisp response
        ),
        label = "shutter_scale"
    )

    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,  // No ripple — the scale animation IS the feedback
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(72.dp)
                .border(
                    width = 3.dp,
                    color = TextPrimary.copy(alpha = alpha),
                    shape = CircleShape
                )
        )

        // Inner circle — gold accent, the visual "button"
        Box(
            modifier = Modifier
                .size(58.dp)
                .background(
                    color = TrueChromeAccent.copy(alpha = alpha),
                    shape = CircleShape
                )
        )
    }
}
