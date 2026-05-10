package com.truechrome.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truechrome.app.processing.color.FilmSimulation
import com.truechrome.app.ui.theme.SurfaceElevated
import com.truechrome.app.ui.theme.TextSecondary

/**
 * FilmSimSelector — Horizontal scrolling film simulation picker.
 *
 * Design: A row of pill-shaped buttons, each showing the simulation name and
 * its analog film stock subtitle. The selected simulation has a colored accent
 * border matching its characteristic color.
 *
 * This appears via bottom edge-swipe gesture on the viewfinder.
 * Switching simulations is instant because all LUT textures are pre-loaded in GPU memory.
 */
@Composable
fun FilmSimSelector(
    selectedSimulation: FilmSimulation,
    onSimulationSelected: (FilmSimulation) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilmSimulation.entries.forEach { simulation ->
            FilmSimChip(
                simulation = simulation,
                isSelected = simulation == selectedSimulation,
                onClick = { onSimulationSelected(simulation) }
            )
        }
    }
}

@Composable
private fun FilmSimChip(
    simulation: FilmSimulation,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            simulation.accentColor.copy(alpha = 0.15f)
        } else {
            SurfaceElevated
        },
        label = "chip_bg"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) {
            simulation.accentColor
        } else {
            Color.Transparent
        },
        label = "chip_border"
    )

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) {
                    Modifier.background(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = simulation.displayName,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) simulation.accentColor else MaterialTheme.colorScheme.onSurface
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = simulation.subtitle,
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextSecondary,
                fontSize = 9.sp
            )
        )
    }
}
