package com.example.gendercolorvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Simple overlay that draws a vertical split and labels for zones.
 * The split is defined in normalized X (0..1), default ~0.82 matching the mock.
 */
class LabelsOverlayView(context: Context) : View(context) {
    // Static male zone boundary based on resonance and slight pitch dependency.
    // We shade everything left of X(y) where X is resonance percent.
    // By default: stricter at high pitch (top), more forgiving at low pitch (bottom).
    var maleXAtHighPitch: Float = 0.70f   // fallback male/androgynous boundary at y=0
    var maleXAtLowPitch: Float = 0.78f    // fallback male/androgynous boundary at y=1
    var femaleXAtHighPitch: Float = 0.85f // fallback androgynous/female boundary at y=0
    var femaleXAtLowPitch: Float = 0.90f  // fallback androgynous/female boundary at y=1

    // Diagonal mode params from config
    private var diagBias: Float? = null
    private var maleBase: Float = -0.32f
    private var maleSlope: Float = -0.08f
    private var androHighBase: Float = -0.12f
    private var androHighSlope: Float = 0.04f
    private var useMaleAsAndroLow: Boolean = true

    fun applyZonesFromConfig(cfg: AppConfig) {
        // Prefer UI-specific lines if present
        cfg.uiLines?.let { u ->
            diagBias = u.bias ?: (cfg.score?.bias ?: cfg.zones.bias)
            maleBase = u.maleBase; maleSlope = u.maleSlope
            androHighBase = u.androHighBase; androHighSlope = u.androHighSlope
            useMaleAsAndroLow = true
            invalidate(); return
        }
        // Else use diagonal scoring zones if present
        cfg.zonesDiag?.let { z ->
            diagBias = (cfg.score?.bias ?: cfg.zones.bias)
            maleBase = z.maleBase; maleSlope = z.maleSlope
            androHighBase = z.androHighBase; androHighSlope = z.androHighSlope
            useMaleAsAndroLow = z.useMaleAsAndroLow
            invalidate(); return
        }
        // Fallback: still draw diagonal (visible tilt) using score.bias or 0
        val xc = 0.5f + (cfg.score?.bias ?: 0f)
        val maleMid = (xc - 0.22f).coerceIn(0f,1f)
        val femaleMid = (xc - 0.02f).coerceIn(0f,1f)
        maleXAtHighPitch = (maleMid - 0.06f).coerceIn(0f,1f)
        maleXAtLowPitch  = (maleMid + 0.04f).coerceIn(0f,1f)
        femaleXAtHighPitch = (femaleMid - 0.04f).coerceIn(0f,1f)
        femaleXAtLowPitch  = (femaleMid + 0.02f).coerceIn(0f,1f)
        diagBias = null
        invalidate()
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt() // deep black
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.BUTT
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA111111.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22111111
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88111111.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(24f, 14f), 0f)
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00111111
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 64f
    }

    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 36f
    }

    var stats: PlaybackStats? = null
        set(value) { field = value; invalidate() }
    var statsExtra: String? = null
    var liveLines: List<String>? = null
        set(value) { field = value; invalidate() }
    var debugLines: List<String>? = null
        set(value) { field = value; invalidate() }
    var categoryText: String? = null
        set(value) { field = value; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Scale text and line thickness relative to width
        textPaint.textSize = (w * 0.11f).coerceAtLeast(36f)
        linePaint.strokeWidth = (w * 0.02f).coerceAtLeast(8f)
        statsPaint.textSize = (w * 0.045f).coerceAtLeast(24f)
        axisPaint.strokeWidth = (w * 0.015f).coerceAtLeast(6f)
        gridPaint.strokeWidth = (w * 0.004f).coerceAtLeast(2f)
        dashPaint.strokeWidth = (w * 0.014f).coerceAtLeast(6f)
        circlePaint.strokeWidth = (w * 0.012f).coerceAtLeast(5f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = kotlin.math.min(width, height)

        // Category label in top-right
        categoryText?.let { txt ->
            val x = size - paddingRight - statsPaint.measureText(txt) - statsPaint.textSize * 0.3f
            val y = paddingTop + statsPaint.textSize * 1.2f
            canvas.drawText(txt, x, y, statsPaint)
        }

        // Stats block (if present) in top-left corner
        var yCursor = paddingTop + statsPaint.textSize * 1.2f
        stats?.let { s ->
            val lines = listOf(
                "range x=[${fmt(s.minX)}..${fmt(s.maxX)}]",
                "range y=[${fmt(s.minY)}..${fmt(s.maxY)}]",
                "avg=(${fmt(s.avgX)}, ${fmt(s.avgY)})",
                if (s.hitMale) "hit male: yes" else "hit male: no"
            )
            for (ln in lines) { drawLine(canvas, ln, yCursor); yCursor += statsPaint.textSize * 1.1f }
        }
        statsExtra?.let { drawLine(canvas, it, yCursor).also { yCursor += statsPaint.textSize * 1.1f } }

        // Live values (updated each 500ms window)
        liveLines?.let { ls ->
            for (ln in ls) {
                if (yCursor > height - statsPaint.textSize * 1.2f) break
                drawLine(canvas, ln, yCursor)
                yCursor += statsPaint.textSize * 1.05f
            }
        }

        // Debug summary of the last recording (ranges)
        debugLines?.let { ls ->
            // leave a small gap
            yCursor += statsPaint.textSize * 0.6f
            for (ln in ls) {
                if (yCursor > height - statsPaint.textSize * 1.2f) break
                drawLine(canvas, ln, yCursor)
                yCursor += statsPaint.textSize * 1.05f
            }
        }
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)

    private fun drawLine(canvas: Canvas, text: String, y: Float) {
        canvas.drawText(text, paddingLeft + statsPaint.textSize * 0.5f, y, statsPaint)
    }
}
