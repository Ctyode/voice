package com.example.gendercolorvoice

import android.os.Handler
import android.os.Looper

data class Sample(val tMs: Long, val x: Float, val y: Float, val c: Float)

data class PlaybackStats(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val avgX: Float,
    val avgY: Float,
    val hitMale: Boolean
)

class SessionRecorder(private val splitX: () -> Float) {
    private val samples = mutableListOf<Sample>()
    private var isRecording = false
    private var startTime = 0L

    fun clear() { samples.clear() }

    fun start() {
        samples.clear()
        isRecording = true
        startTime = System.currentTimeMillis()
    }

    fun stop(): List<Sample> {
        isRecording = false
        return samples.toList()
    }

    fun recording(): Boolean = isRecording

    fun onPoint(x: Float, y: Float, c: Float) {
        if (!isRecording) return
        val t = System.currentTimeMillis() - startTime
        samples.add(Sample(t, x, y, c))
    }

    fun last(): List<Sample> = samples.toList()

    fun computeStats(seq: List<Sample>): PlaybackStats? {
        if (seq.isEmpty()) return null
        var minX = 1f; var maxX = 0f
        var minY = 1f; var maxY = 0f
        var sx = 0f; var sy = 0f; var n = 0
        var hitMale = false
        val split = splitX()
        for (s in seq) {
            if (s.x < minX) minX = s.x
            if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y
            if (s.y > maxY) maxY = s.y
            sx += s.x; sy += s.y; n++
            if (s.x <= split) hitMale = true
        }
        return PlaybackStats(minX, maxX, minY, maxY, sx / n, sy / n, hitMale)
    }

    fun play(
        seq: List<Sample>,
        field: GradientFieldView,
        onFinished: (() -> Unit)? = null
    ) {
        if (seq.isEmpty()) { onFinished?.let { it() }; return }
        val handler = Handler(Looper.getMainLooper())
        val t0 = seq.first().tMs
        for (s in seq) {
            val dt = (s.tMs - t0).coerceAtLeast(0L)
            handler.postDelayed({ field.setPoint(s.x, s.y) }, dt)
        }
        val total = (seq.last().tMs - t0).coerceAtLeast(0L)
        handler.postDelayed({ onFinished?.invoke() }, total + 50L)
    }
}

