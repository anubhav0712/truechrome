package com.truechrome.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.truechrome.app.ui.viewfinder.ViewfinderScreen

/**
 * TrueChromeApp — The root Composable that hosts all app navigation.
 *
 * Currently a single-screen app (viewfinder only). As features are added
 * (settings, gallery), this will become a NavHost with Compose Navigation.
 *
 * WHY NO NAVIGATION YET:
 * Phase 1 focuses on scaffolding. The camera app has one primary screen —
 * the viewfinder. Settings and gallery will be overlays/sheets, not separate
 * navigation destinations, to maintain the "zero-UI" philosophy.
 */
@Composable
fun TrueChromeApp(
    modifier: Modifier = Modifier
) {
    ViewfinderScreen(modifier = modifier)
}
