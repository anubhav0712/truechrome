package com.truechrome.app.camera.domain.model

enum class FocusState {
    IDLE,       // Passive continuous autofocus
    SCANNING,   // Tap-to-focus initiated, waiting for lock
    LOCKED,     // Tap-to-focus locked successfully
    TRACKING    // Real-time predictive tracking active
}
