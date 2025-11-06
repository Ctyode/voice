package com.example.gendercolorvoice

import kotlin.math.ln

object PitchToGender {
    // Normalize F0 to [0..1] where 0 ~ low pitch, 1 ~ high pitch.
    // Defaults match typical speaking ranges, but can be overridden at runtime
    // from dataset-derived config (see MainActivity setup).
    private const val DEFAULT_MIN_HZ = 85f
    private const val DEFAULT_MAX_HZ = 255f

    @Volatile private var minHzRuntime: Float = DEFAULT_MIN_HZ
    @Volatile private var maxHzRuntime: Float = DEFAULT_MAX_HZ

    fun setRange(minHz: Float?, maxHz: Float?) {
        val mn = (minHz ?: DEFAULT_MIN_HZ).coerceAtLeast(10f)
        val mx = (maxHz ?: DEFAULT_MAX_HZ).coerceAtLeast(mn + 1f)
        minHzRuntime = mn
        maxHzRuntime = mx
    }

    fun scoreFromF0(f0: Float, minHz: Float? = null, maxHz: Float? = null): Float {
        val mn = (minHz ?: minHzRuntime)
        val mx = (maxHz ?: maxHzRuntime)
        val span = (mx - mn).coerceAtLeast(1f)
        val v = (f0 - mn) / span
        return v.coerceIn(0f, 1f)
    }
}
