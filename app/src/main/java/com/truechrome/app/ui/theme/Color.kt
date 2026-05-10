package com.truechrome.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * TrueChrome Color Palette — Dark-mode-first, camera-optimized colors.
 *
 * WHY DARK THEME ONLY:
 * Camera apps should ALWAYS use dark UI to:
 * 1. Prevent the screen from washing out the viewfinder with reflected light
 * 2. Minimize pupil contraction when looking between screen and scene
 * 3. Reduce battery drain during extended shooting sessions (OLED)
 * 4. Match the professional, focused aesthetic of Fujifilm cameras
 */

// ── Primary: Warm amber accent (inspired by Fujifilm's signature green-gold branding) ──
val TrueChromeAccent = Color(0xFFD4A853)          // Warm gold — shutter button, active states
val TrueChromeAccentDim = Color(0xFF8B7036)       // Dimmed gold — inactive/disabled
val TrueChromeAccentBright = Color(0xFFE8C36A)    // Bright gold — pressed states

// ── Surface: Deep blacks and charcoals ──
val SurfaceBlack = Color(0xFF000000)               // True black — viewfinder background
val SurfaceDark = Color(0xFF0D0D0D)                // Near-black — panels, overlays
val SurfaceElevated = Color(0xFF1A1A1A)            // Elevated surface — bottom sheets
val SurfaceCard = Color(0xFF242424)                // Card surfaces — settings items

// ── Text ──
val TextPrimary = Color(0xFFE8E8E8)                // Primary text — high contrast
val TextSecondary = Color(0xFF8A8A8A)              // Secondary text — labels, captions
val TextDisabled = Color(0xFF4A4A4A)               // Disabled text

// ── Film simulation accent colors (for UI indicators) ──
val FilmClassicNeg = Color(0xFFE07B4C)             // Warm orange — Classic Negative
val FilmClassicChrome = Color(0xFF7B9EAE)          // Muted cyan — Classic Chrome
val FilmVelvia = Color(0xFF4CAF50)                 // Vivid green — Velvia
val FilmAstia = Color(0xFFE8B4B8)                  // Soft pink — Astia
val FilmProNeg = Color(0xFFB0B0B0)                 // Neutral gray — Pro Neg
val FilmAcros = Color(0xFFFFFFFF)                  // Pure white — Acros

// ── Status colors ──
val ErrorRed = Color(0xFFCF6679)
val SuccessGreen = Color(0xFF81C784)
