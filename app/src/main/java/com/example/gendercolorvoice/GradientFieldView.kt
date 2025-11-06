package com.example.gendercolorvoice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GradientFieldView(context: Context) : View(context) {
    // Optional SVG layout info applied from config
    data class SvgMeta(
        val canvasX: Float, val canvasY: Float, val canvasW: Float, val canvasH: Float,
        val svgW: Float, val svgH: Float
    )
    private var svgMeta: SvgMeta? = null
    private var fieldSize: Int = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD700.toInt() // solid golden yellow
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var tx = 0.5f
    private var ty = 0.5f
    private var animX: ValueAnimator? = null
    private var animY: ValueAnimator? = null
    private val trail = ArrayDeque<Pair<Float, Float>>()

    fun setPoint(x: Float, y: Float) {
        // Match web app: marker X is direct resonance percent
        val nx = x.coerceIn(0f, 1f)
        val ny = 1f - y.coerceIn(0f, 1f) // invert Y so top=1 as in ref
        // store trail in normalized 0..1 space (after Y inversion for consistency)
        trail.addLast(nx to ny)
        if (width == 0 || height == 0) { tx = nx; ty = ny; invalidate(); return }
        animX?.cancel(); animY?.cancel()
        animX = ValueAnimator.ofFloat(tx, nx).apply {
            duration = 180
            addUpdateListener { tx = it.animatedValue as Float; invalidate() }
            start()
        }
        animY = ValueAnimator.ofFloat(ty, ny).apply {
            duration = 180
            addUpdateListener { ty = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun getPoint01(): Pair<Float, Float> = tx.coerceIn(0f,1f) to ty.coerceIn(0f,1f)
    fun clearTrail() { trail.clear(); invalidate() }

    fun applySvgLayout(cfg: AppConfig) {
        cfg.svg?.let { s ->
            val parsed = parseSvgViewBox()
            val svgW = parsed?.first ?: (s.canvas.x + s.canvas.width)
            val svgH = parsed?.second ?: (s.canvas.y + s.canvas.height)
            svgMeta = SvgMeta(
                s.canvas.x, s.canvas.y, s.canvas.width, s.canvas.height,
                svgW, svgH
            )
        }
        invalidate()
    }

    fun applySvgLayout(mapping: SvgMappingInfo?) {
        if (mapping?.canvas != null) {
            val parsed = parseSvgViewBox()
            val svgW = parsed?.first ?: (mapping.canvas.x + mapping.canvas.width)
            val svgH = parsed?.second ?: (mapping.canvas.y + mapping.canvas.height)
            svgMeta = SvgMeta(
                mapping.canvas.x, mapping.canvas.y, mapping.canvas.width, mapping.canvas.height,
                svgW, svgH
            )
            invalidate()
        }
    }

    // Try to read viewBox or width/height from assets/Scheme2.svg so our math matches WebView
    private fun parseSvgViewBox(): Pair<Float, Float>? {
        return try {
            val am = context.assets
            val txt = am.open("Scheme2.svg").bufferedReader().use { it.readText() }
            // viewBox="minX minY width height"
            val vb = Regex("viewBox=\\\"\\s*([\\-0-9.]+)\\s+([\\-0-9.]+)\\s+([0-9.]+)\\s+([0-9.]+)\\s*\\\"")
                .find(txt)
            if (vb != null) {
                val w = vb.groupValues[3].toFloat()
                val h = vb.groupValues[4].toFloat()
                return w to h
            }
            // Fallback to width/height attributes like width="680" height="680"
            val w = Regex("width=\\\"([0-9.]+)\\\"").find(txt)?.groupValues?.get(1)?.toFloat()
            val h = Regex("height=\\\"([0-9.]+)\\\"").find(txt)?.groupValues?.get(1)?.toFloat()
            if (w != null && h != null) w to h else null
        } catch (_: Throwable) { null }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fieldSize = kotlin.math.min(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background is provided by underlying WebView; only draw trail and marker

        // Compute chart rect where the SVG's plotting canvas appears on screen.
        val chartRect = run {
            val m = svgMeta
            if (m == null) {
                android.graphics.RectF(0f, 0f, fieldSize.toFloat(), fieldSize.toFloat())
            } else {
                val vw = width.toFloat(); val vh = height.toFloat()
                // Match WebView: it fits the WHOLE SVG (viewBox) with contain
                val scale = kotlin.math.min(vw / m.svgW, vh / m.svgH)
                val dispW = m.svgW * scale; val dispH = m.svgH * scale
                val imgLeft = (vw - dispW) / 2f
                val imgTop = 0f // object-position: center top in WebView
                // Our gradient canvas is an inner rect at (canvasX,canvasY) inside the SVG
                android.graphics.RectF(
                    imgLeft + m.canvasX * scale,
                    imgTop + m.canvasY * scale,
                    imgLeft + (m.canvasX + m.canvasW) * scale,
                    imgTop + (m.canvasY + m.canvasH) * scale
                )
            }
        }

        // Helper for external logging/mapping
        lastChartRect.set(chartRect)

        // Draw trail polyline
        if (trail.size >= 2) {
            var prev: Pair<Float, Float>? = null
            var idx = 0
            val total = trail.size.toFloat().coerceAtLeast(1f)
            for (p in trail) {
                if (prev != null) {
                    // лёгкое затухание по возрасту, но линия остаётся яркой и толстой
                    val age = idx / total
                    val baseColor = 0xFFFFD700.toInt()
                    val alpha = (255 * (0.35f + 0.65f * (1f - age))).toInt().coerceIn(64, 255)
                    trailPaint.color = (alpha shl 24) or (baseColor and 0x00FFFFFF)
                    val x1 = chartRect.left + (prev!!.first * chartRect.width())
                    val y1 = chartRect.top + (prev!!.second * chartRect.height())
                    val x2 = chartRect.left + (p.first * chartRect.width())
                    val y2 = chartRect.top + (p.second * chartRect.height())
                    canvas.drawLine(x1, y1, x2, y2, trailPaint)
                }
                prev = p
                idx++
            }
        }

        // Draw current marker "me"
        val cx = chartRect.left + (tx.coerceIn(0f,1f) * chartRect.width())
        val cy = chartRect.top + (ty.coerceIn(0f,1f) * chartRect.height())
        // outer ring
        markerStroke.color = Color.BLACK
        markerStroke.strokeWidth = 5f
        canvas.drawCircle(cx, cy, 18f, markerStroke)
        // fill
        markerPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 16f, markerPaint)
        // inner border
        markerStroke.color = Color.DKGRAY
        markerStroke.strokeWidth = 3f
        canvas.drawCircle(cx, cy, 16f, markerStroke)
        // label
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = 24f
        }
        canvas.drawText("me", cx, cy - 24f, tp)
    }

    // region: mapping helpers for logging
    private val lastChartRect = android.graphics.RectF(0f,0f,0f,0f)
    fun getChartRect(): android.graphics.RectF = android.graphics.RectF(lastChartRect)
    fun map01ToPx(x01: Float, y01: Float): Pair<Float, Float> {
        val r = getChartRect()
        val x = r.left + (x01.coerceIn(0f,1f) * r.width())
        val y = r.top + (y01.coerceIn(0f,1f) * r.height())
        return x to y
    }
    // endregion

    // Gradient generation removed
}
