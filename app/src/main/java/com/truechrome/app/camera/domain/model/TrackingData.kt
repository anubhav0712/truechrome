package com.truechrome.app.camera.domain.model

import android.graphics.Rect

/**
 * Reusable data structure for holding predictive tracking state.
 * Mutate fields instead of re-instantiating to maintain zero heap allocations
 * during the 60fps tracking loop.
 */
class TrackingData {
    val sensorRect = Rect() // The bounding box in physical sensor coordinates
    val uiRect = Rect()     // The bounding box scaled for the UI overlay
    var confidence = 0f     // 0.0 to 1.0 confidence from ML model
    var isActive = false    // True if tracking is currently active
    
    // UI tracking reticle normalized coordinates (0.0 to 1.0)
    val normalizedCenter = NormalizedPoint()

    fun reset() {
        sensorRect.setEmpty()
        uiRect.setEmpty()
        confidence = 0f
        isActive = false
        normalizedCenter.set(0.5f, 0.5f)
    }
}

class NormalizedPoint(var x: Float = 0.5f, var y: Float = 0.5f) {
    fun set(newX: Float, newY: Float) {
        x = newX
        y = newY
    }
}
