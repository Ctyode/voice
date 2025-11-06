package com.example.gendercolorvoice

// Simple exponential moving average used for smoothing
class Ema(private val alpha: Float) {
    private var initialized = false
    private var y = 0f
    fun update(x: Float): Float {
        y = if (!initialized) { initialized = true; x } else { alpha * x + (1 - alpha) * y }
        return y
    }
    fun value(): Float = y
}

