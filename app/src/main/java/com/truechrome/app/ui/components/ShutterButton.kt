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

/**
 * ShutterButton — The primary capture button.
 *
 * Design: Minimalist, glass-like white circle.
 * - Press animation uses spring() for a fluid, bouncy mechanical feel
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Spring animation for press feedback
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.4f,  // Bouncy release
            stiffness = 800f
        ),
        label = "shutter_scale"
    )

    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .size(80.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,  // No ripple — the scale animation IS the feedback
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer translucent ring
        Box(
            modifier = Modifier
                .size(80.dp)
                .border(
                    width = 4.dp,
                    color = Color.White.copy(alpha = alpha * 0.3f),
                    shape = CircleShape
                )
        )

        // Inner solid white circle
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = Color.White.copy(alpha = alpha * 0.9f),
                    shape = CircleShape
                )
        )
    }
}
